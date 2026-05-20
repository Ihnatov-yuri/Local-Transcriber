package nl.ihnatov.transcriber.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
// Two `Font` constructors live in different packages:
//   - androidx.compose.ui.text.googlefonts.Font(GoogleFont, …) — what we
//     use for the downloadable Saira / Fraunces / IBM Plex / Inter
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
 * Four families, each with one job, per design principle #4.
 *
 *   Saira Condensed — display / brand / big stats / inverse-block CTAs
 *   Fraunces        — the one statement per screen, italic by default
 *   IBM Plex Mono   — metadata / indexing / "this is data"
 *   Inter           — body prose
 *
 * Loaded from Google Fonts via the Compose downloadable-font provider.
 * Requires `androidx.compose.ui:ui-text-google-fonts` and the
 * com_google_android_gms_fonts_certs string-array in res/values.
 *
 * Big numerals (timer, counts) use Saira Condensed Bold — NOT Fraunces.
 * Earlier iterations tried Fraunces and it felt too display-serif at
 * phone sizes; Saira Condensed at SemiBold/Bold with lineHeight 0.88em
 * and tnum/lnum features is the spec.
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
// system-font fallback for every weight/style we care about. Compose
// walks the family and picks the first Font that resolves — so until
// the GoogleFont fetch completes (~1-2 sec on first run, instant on
// every subsequent run thanks to the Play Services cache), the
// fallback is what users see. Without these the screen renders blank
// during the fetch window.

val SairaCondensed = FontFamily(
    gf("Saira Condensed", FontWeight.Medium),
    gf("Saira Condensed", FontWeight.SemiBold),
    gf("Saira Condensed", FontWeight.Bold),
    // Closest system substitute on Android: sans-serif-condensed.
    sysFont("sans-serif-condensed", FontWeight.Medium),
    sysFont("sans-serif-condensed", FontWeight.SemiBold),
    sysFont("sans-serif-condensed", FontWeight.Bold),
)

val Fraunces = FontFamily(
    gf("Fraunces", FontWeight.Normal),
    gf("Fraunces", FontWeight.Normal, FontStyle.Italic),
    gf("Fraunces", FontWeight.Medium, FontStyle.Italic),
    sysFont("serif", FontWeight.Normal),
    sysFont("serif", FontWeight.Normal, FontStyle.Italic),
    sysFont("serif", FontWeight.Medium, FontStyle.Italic),
)

val IbmPlexMono = FontFamily(
    gf("IBM Plex Mono", FontWeight.Medium),
    gf("IBM Plex Mono", FontWeight.SemiBold),
    sysFont("monospace", FontWeight.Medium),
    sysFont("monospace", FontWeight.SemiBold),
)

val Inter = FontFamily(
    gf("Inter", FontWeight.Normal),
    gf("Inter", FontWeight.Medium),
    gf("Inter", FontWeight.SemiBold),
    sysFont("sans-serif", FontWeight.Normal),
    sysFont("sans-serif", FontWeight.Medium),
    sysFont("sans-serif", FontWeight.SemiBold),
)

private val baseDisplay = TextStyle(
    fontFamily = SairaCondensed,
    fontFeatureSettings = "tnum, lnum",
)

/**
 * The M3 [Typography] mapping. Composables read these through
 * `MaterialTheme.typography.X` — the slot names are reused for their
 * scale, not their semantics. Map:
 *
 *   displayLarge / displayMedium / displaySmall → Saira Condensed
 *   headlineLarge / headlineMedium               → Fraunces italic statement
 *   headlineSmall                                → Fraunces prose (Detail "continuous prose")
 *   bodyLarge / bodyMedium / bodySmall          → Inter
 *   labelLarge / labelMedium / labelSmall       → IBM Plex Mono caps
 */
val TR = Typography(
    // Display (Saira Condensed)
    displayLarge = baseDisplay.copy(
        fontSize = 28.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium,
    ),
    displayMedium = baseDisplay.copy(
        fontSize = 20.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium,
        letterSpacing = 0.1.sp,
    ),
    displaySmall = baseDisplay.copy(
        fontSize = 17.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium,
        letterSpacing = 0.4.sp,
    ),

    // Statement (Fraunces italic)
    headlineLarge = TextStyle(
        fontFamily = Fraunces, fontStyle = FontStyle.Italic,
        fontSize = 30.sp, lineHeight = 35.sp, letterSpacing = (-0.45).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = Fraunces, fontStyle = FontStyle.Italic,
        fontSize = 24.sp, lineHeight = 28.sp, letterSpacing = (-0.36).sp,
    ),
    // Magazine prose for Detail's "continuous prose" variant
    headlineSmall = TextStyle(
        fontFamily = Fraunces, fontSize = 15.5.sp, lineHeight = 23.sp,
    ),

    // Body (Inter)
    bodyLarge = TextStyle(fontFamily = Inter, fontSize = 15.sp, lineHeight = 20.sp),
    bodyMedium = TextStyle(fontFamily = Inter, fontSize = 13.5.sp, lineHeight = 18.sp),
    bodySmall = TextStyle(fontFamily = Inter, fontSize = 13.sp, lineHeight = 18.sp),

    // Mono caps (IBM Plex Mono) — always uppercase + tracked at call site
    labelLarge = TextStyle(
        fontFamily = IbmPlexMono, fontSize = 10.5.sp, lineHeight = 14.sp,
        letterSpacing = 1.0.sp, fontWeight = FontWeight.Medium,
    ),
    labelMedium = TextStyle(
        fontFamily = IbmPlexMono, fontSize = 9.5.sp, lineHeight = 13.sp,
        letterSpacing = 0.85.sp, fontWeight = FontWeight.Medium,
    ),
    labelSmall = TextStyle(
        fontFamily = IbmPlexMono, fontSize = 9.sp, lineHeight = 12.sp,
        letterSpacing = 0.8.sp, fontWeight = FontWeight.Medium,
    ),
)

/** Kept as a back-compat alias for code that already references this name. */
val TranscriberTypography = TR
