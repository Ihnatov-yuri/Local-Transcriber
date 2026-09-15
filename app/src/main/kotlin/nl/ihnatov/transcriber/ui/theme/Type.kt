package nl.ihnatov.transcriber.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
// Two `Font` constructors live in different packages:
//   - androidx.compose.ui.text.googlefonts.Font(GoogleFont, …) — what we
//     use for the downloadable Archivo / Schibsted Grotesk
//   - androidx.compose.ui.text.font.Font(DeviceFontFamilyName, …) — what
//     we use as a SYSTEM-FONT FALLBACK below each GoogleFont entry, so
//     the app still has readable text in the ~1–2 second window before
//     Google Play Services delivers the downloadable face. Without
//     fallbacks the screen renders blank until the fetch completes, and
//     users with no Play Services (rare but possible) see nothing
//     forever. The two are aliased here for clarity at the call site.
import androidx.compose.ui.text.font.Font as DeviceFont
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.sp
import nl.ihnatov.transcriber.R

/**
 * Two families, each with one job — ported from `--display`/`--text` in
 * `../Landing/docs/lit-field-tokens.css`. The old system's four-family
 * split (condensed display / italic serif statement / mono metadata / UI
 * prose) is gone along with it: Lit Field carries no serif and no mono
 * voice at all, so the "quiet statement" and "this is metadata" roles that
 * used to come from a whole separate typeface now come from size, weight,
 * and tracking on these same two families instead.
 *
 *   Archivo           — display: brand, big numerals, CTA labels, headers
 *   Schibsted Grotesk  — everything else: body prose, the one statement
 *                        per screen (now a larger/lighter cut of the text
 *                        face rather than a switch to italic serif), and
 *                        tracked-caps metadata labels (replacing mono)
 *
 * Loaded from Google Fonts via the Compose downloadable-font provider.
 * Requires `androidx.compose.ui:ui-text-google-fonts` and the
 * com_google_android_gms_fonts_certs string-array in res/values.
 */
private val GfProvider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs,
)

private fun gf(name: String, weight: FontWeight, style: FontStyle = FontStyle.Normal) =
    Font(GoogleFont(name), GfProvider, weight, style)

private fun sysFont(family: String, weight: FontWeight, style: FontStyle = FontStyle.Normal) =
    DeviceFont(DeviceFontFamilyName(family), weight, style)

// Each FontFamily lists the downloadable Google Fonts first, then a
// system-font fallback for every weight we care about. Compose walks the
// family and picks the first Font that resolves — so until the GoogleFont
// fetch completes, the fallback is what users see. Neither Archivo nor
// Schibsted Grotesk has a genuinely distinct Android system-font bucket to
// fall back to (unlike the old condensed/serif/mono families), so both
// fall back to plain "sans-serif" (Roboto) — same as the source CSS's own
// fallback stacks, which both end in generic sans-serif.

val Archivo = FontFamily(
    gf("Archivo", FontWeight.Medium),
    gf("Archivo", FontWeight.SemiBold),
    gf("Archivo", FontWeight.Bold),
    gf("Archivo", FontWeight.Black),
    sysFont("sans-serif", FontWeight.Medium),
    sysFont("sans-serif", FontWeight.SemiBold),
    sysFont("sans-serif", FontWeight.Bold),
    sysFont("sans-serif", FontWeight.Black),
)

val SchibstedGrotesk = FontFamily(
    gf("Schibsted Grotesk", FontWeight.Normal),
    gf("Schibsted Grotesk", FontWeight.Medium),
    gf("Schibsted Grotesk", FontWeight.SemiBold),
    gf("Schibsted Grotesk", FontWeight.Bold),
    sysFont("sans-serif", FontWeight.Normal),
    sysFont("sans-serif", FontWeight.Medium),
    sysFont("sans-serif", FontWeight.SemiBold),
    sysFont("sans-serif", FontWeight.Bold),
)

private val baseDisplay = TextStyle(
    fontFamily = Archivo,
    fontFeatureSettings = "tnum, lnum",
)

/**
 * The M3 [Typography] mapping. Composables read these through
 * `MaterialTheme.typography.X` — the slot names are reused for their
 * scale, not their semantics. Map:
 *
 *   displayLarge / displayMedium / displaySmall → Archivo
 *   headlineLarge / headlineMedium               → Schibsted Grotesk statement
 *                                                   (the one quiet "voice"
 *                                                   moment per screen — was
 *                                                   Fraunces italic, now a
 *                                                   larger/lighter cut of
 *                                                   the text face instead)
 *   headlineSmall                                → Schibsted Grotesk prose
 *   bodyLarge / bodyMedium / bodySmall          → Schibsted Grotesk
 *   labelLarge / labelMedium / labelSmall       → Schibsted Grotesk tracked
 *                                                   caps (replaces IBM Plex
 *                                                   Mono — Lit Field has no
 *                                                   mono voice)
 */
val TR = Typography(
    // Display (Archivo)
    displayLarge = baseDisplay.copy(
        fontSize = 28.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold,
    ),
    displayMedium = baseDisplay.copy(
        fontSize = 20.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.1.sp,
    ),
    displaySmall = baseDisplay.copy(
        fontSize = 17.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.2.sp,
    ),

    // Statement (Schibsted Grotesk, larger + lighter than body — the one
    // quiet "voice" moment per screen)
    headlineLarge = TextStyle(
        fontFamily = SchibstedGrotesk, fontWeight = FontWeight.Normal,
        fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = (-0.2).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = SchibstedGrotesk, fontWeight = FontWeight.Normal,
        fontSize = 21.sp, lineHeight = 27.sp, letterSpacing = (-0.15).sp,
    ),
    // Continuous-prose variant for Detail's long-form reading mode.
    headlineSmall = TextStyle(
        fontFamily = SchibstedGrotesk, fontWeight = FontWeight.Normal,
        fontSize = 15.5.sp, lineHeight = 23.sp,
    ),

    // Body (Schibsted Grotesk)
    bodyLarge = TextStyle(fontFamily = SchibstedGrotesk, fontSize = 15.sp, lineHeight = 20.sp),
    bodyMedium = TextStyle(fontFamily = SchibstedGrotesk, fontSize = 13.5.sp, lineHeight = 18.sp),
    bodySmall = TextStyle(fontFamily = SchibstedGrotesk, fontSize = 13.sp, lineHeight = 18.sp),

    // Tracked caps (Schibsted Grotesk) — metadata, eyebrows, indexing.
    // Sized a touch larger than the old mono scale since Grotesk capitals
    // read denser than IBM Plex Mono at the same size.
    labelLarge = TextStyle(
        fontFamily = SchibstedGrotesk, fontSize = 11.sp, lineHeight = 14.sp,
        letterSpacing = 1.0.sp, fontWeight = FontWeight.SemiBold,
    ),
    labelMedium = TextStyle(
        fontFamily = SchibstedGrotesk, fontSize = 10.sp, lineHeight = 13.sp,
        letterSpacing = 0.85.sp, fontWeight = FontWeight.SemiBold,
    ),
    labelSmall = TextStyle(
        fontFamily = SchibstedGrotesk, fontSize = 9.5.sp, lineHeight = 12.sp,
        letterSpacing = 0.8.sp, fontWeight = FontWeight.Medium,
    ),
)
