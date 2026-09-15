package nl.ihnatov.transcriber.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

/**
 * The Lit Field theme. Material 3 stays the engine (theming, scaffolds,
 * navigation) but the shape and depth language flip from the old editorial
 * system: pills and generous corner radii instead of `RectangleShape`
 * everywhere, real shadow-lifted surfaces instead of hairline-only
 * division. This is a convergence with where Android's own Material 3
 * Expressive direction already went (pill buttons and large radii are
 * first-class M3 tokens as of 2025), not a departure from it the way the
 * old flat/hairline look deliberately was.
 *
 * Specifically changed from the old editorial theme:
 *   - shape slots are a real radius scale (10/14/18/22/26dp), not zero
 *   - `error` now maps to a dedicated status-red, not the brand accent —
 *     the source CSS calls this out explicitly: a status color sitting
 *     close to the brand hue reads as brand, not as state
 *   - `tertiary` and `secondaryContainer` are now actually wired (the old
 *     theme left them unset, so anything reading those slots — Settings'
 *     `Pill()`, the model-update-reason text — silently fell back to M3's
 *     default purple-ish roles, quietly breaking the "one accent" rule)
 *
 * The optional [dynamicColor] parameter is accepted (and ignored) for
 * backward compatibility with the previous TranscriberTheme signature —
 * per the 2026-09 Android-conventions research, a strong single custom
 * accent (not per-wallpaper dynamic color) is the normal, accepted pattern
 * for a branded app in 2026, so this app deliberately never turns it on.
 */
@Composable
fun TranscriberTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    @Suppress("UNUSED_PARAMETER") dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) {
        darkColorScheme(
            background = BaseDark,
            surface = BaseDark,
            // Lifted surface uses BaseDeepDark (== --night-2, slightly
            // LIGHTER than BaseDark) so raised chrome reads as on top of
            // the page rather than sunken into a darker hole. Light mode
            // uses BaseDeepLight (slightly DARKER than BaseLight) for the
            // same "edge of the page" effect — the polarity flips between
            // modes, the visual hierarchy stays consistent. See Color.kt.
            surfaceVariant = BaseDeepDark,
            onBackground = InkDark,
            onSurface = InkDark,
            onSurfaceVariant = Ink3Dark,
            primary = Accent,
            onPrimary = BaseDark,
            secondary = InkDark,
            onSecondary = BaseDark,
            secondaryContainer = BaseDeepDark,
            onSecondaryContainer = InkDark,
            tertiary = StatusWarningDark,
            onTertiary = BaseDark,
            error = StatusErrorDark,
            onError = BaseDark,
            outline = HairDark,
            outlineVariant = HairStrongDark,
        )
    } else {
        lightColorScheme(
            background = BaseLight,
            surface = BaseLight,
            surfaceVariant = BaseDeepLight,
            onBackground = InkLight,
            onSurface = InkLight,
            onSurfaceVariant = Ink3Light,
            primary = Accent,
            onPrimary = BaseLight,
            secondary = InkLight,
            onSecondary = BaseLight,
            secondaryContainer = BaseDeepLight,
            onSecondaryContainer = InkLight,
            tertiary = StatusWarningLight,
            onTertiary = BaseLight,
            error = StatusErrorLight,
            onError = BaseLight,
            outline = HairLight,
            outlineVariant = HairStrongLight,
        )
    }

    // Make system bars page-colored so the atmosphere/page extends edge to
    // edge. SideEffect re-applies on every recomposition, which is fine
    // for window colors.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            (view.context as? Activity)?.window?.let { window ->
                window.statusBarColor = colors.background.toArgb()
                window.navigationBarColor = colors.background.toArgb()
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
        }
    }

    MaterialTheme(
        colorScheme = colors,
        typography = TR,
        // A real radius scale — the shape-language reversal from the old
        // zero-radius editorial theme. Values match the source CSS's own
        // anchors (10dp control-radius, 14dp radius-sm, 26dp radius) with
        // two interpolated mid-steps since M3 wants five slots where the
        // CSS only names three.
        shapes = Shapes(
            extraSmall = RoundedCornerShape(10.dp),
            small = RoundedCornerShape(14.dp),
            medium = RoundedCornerShape(18.dp),
            large = RoundedCornerShape(22.dp),
            extraLarge = RoundedCornerShape(26.dp),
        ),
    ) {
        // M3 ripples default to a bright cyan-tinted splash. Replace
        // with an ink-tinted RippleConfiguration so taps register as a
        // faint paper-feedback flash, not a cyan splash.
        @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
        CompositionLocalProvider(
            androidx.compose.material3.LocalRippleConfiguration provides
                RippleConfiguration(color = colors.onBackground),
            content = content,
        )
    }
}
