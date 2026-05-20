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
import androidx.core.view.WindowCompat

/**
 * The editorial theme. Material 3 stays as the engine (theming, scaffolds,
 * navigation) but the look is overridden almost everywhere — no rounded
 * corners, no elevation, hairlines instead of cards, paper background, the
 * one accent. Per design principle #1: the page is a sheet of paper, not
 * a screen.
 *
 * Specifically removed from M3 defaults:
 *   - dynamic color (we want brand-specific paper/ink, not wallpaper)
 *   - rounded corners on every shape slot (RectangleShape across the board)
 *   - bright system bars (status + nav painted with the paper color so the
 *     sheet feels continuous from edge to edge)
 *
 * The optional [dynamicColor] parameter is accepted (and ignored) for
 * backward compatibility with the previous TranscriberTheme signature.
 */
@Composable
fun TranscriberTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    @Suppress("UNUSED_PARAMETER") dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) {
        darkColorScheme(
            background = PaperDark,
            surface = PaperDark,
            // Lifted surface uses PaperRaisedDark (slightly LIGHTER than
            // PaperDark) so the bottom nav, player bar, and other
            // "raised" chrome reads as on top of the paper sheet rather
            // than sunken into a darker hole. Light mode uses
            // PaperEdgeLight (slightly DARKER than PaperLight) for the
            // same "edge of the sheet" effect — the polarity flips
            // between modes, the visual hierarchy is consistent.
            surfaceVariant = PaperRaisedDark,
            onBackground = InkDark,
            onSurface = InkDark,
            onSurfaceVariant = InkSoftDark,
            primary = Accent,
            onPrimary = PaperDark,
            secondary = InkDark,
            onSecondary = PaperDark,
            error = Accent,
            onError = PaperDark,
            outline = HairlineDark,
            outlineVariant = HairlineSoftDark,
        )
    } else {
        lightColorScheme(
            background = PaperLight,
            surface = PaperLight,
            surfaceVariant = PaperEdgeLight,
            onBackground = InkLight,
            onSurface = InkLight,
            onSurfaceVariant = InkSoftLight,
            primary = Accent,
            onPrimary = PaperLight,
            secondary = InkLight,
            onSecondary = PaperLight,
            error = Accent,
            onError = PaperLight,
            outline = HairlineLight,
            outlineVariant = HairlineSoftLight,
        )
    }

    // Make system bars paper-colored so the sheet metaphor extends edge
    // to edge. SideEffect re-applies on every recomposition, which is
    // fine for window colors.
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
        // Every shape slot is rectangular — no rounded corners anywhere
        // by default. Components that explicitly want a curve override
        // their own shape parameter.
        shapes = Shapes(
            // RoundedCornerShape(0) is a CornerBasedShape (which M3's
            // Shapes slot requires) that's functionally identical to
            // RectangleShape — zero corner radius across the board.
            extraSmall = RoundedCornerShape(0),
            small = RoundedCornerShape(0),
            medium = RoundedCornerShape(0),
            large = RoundedCornerShape(0),
            extraLarge = RoundedCornerShape(0),
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
