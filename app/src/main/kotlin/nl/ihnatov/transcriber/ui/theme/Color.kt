package nl.ihnatov.transcriber.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The Lit Field palette — ported from `../Landing/docs/lit-field-tokens.css`
 * (the personal-brand design language as of 2026-09). Replaces the old
 * paper/ink "Editorial Tech" palette wholesale; do not "tune" these without
 * diffing against the source CSS.
 *
 * Dark mode reuses the CSS's own `--night`/`--on-night` group rather than
 * inventing new values: `--ink` (light-mode text) and `--night` (dark-mode
 * background) are literally the same hex in the source, so light mode is
 * "ink on base" and dark mode is "on-night on night" — the same shape,
 * mirrored. The raised/lifted-surface trick from the old palette (surface
 * darker than background in light mode, LIGHTER in dark mode, so elevated
 * chrome always reads as "on top of the page") carries over unchanged, now
 * sourced from `--base-deep` and `--night-2` respectively.
 */

// ─── Light (base / ink) ────────────────────────────────────────────────
val BaseLight = Color(0xFFECEEED)
val BaseDeepLight = Color(0xFFE2E6E6)
val InkLight = Color(0xFF0B0C0E)
val Ink2Light = Color(0xFF23262B)
val Ink3Light = Color(0xFF454A52)
val Ink4Light = Color(0xFF5C626B)

// ─── Dark (night / on-night) ───────────────────────────────────────────
val BaseDark = Color(0xFF0B0C0E) // == --night
val BaseDeepDark = Color(0xFF16181C) // == --night-2 — LIGHTER than base, raised chrome in dark mode
val InkDark = Color(0xFFF4F5F5) // == --on-night
val Ink2Dark = Color(0xFFA9B0B8) // == --on-night-2

// The source CSS only carries two on-night steps. These two extra ones are
// alpha-derived from on-night rather than invented solid colors — the same
// technique the old palette's ink-soft/ink-muted dark values used.
val Ink3Dark = Color(0xA8F4F5F5) // on-night @ ~0.66
val Ink4Dark = Color(0x6BF4F5F5) // on-night @ ~0.42

/**
 * THE orange. Unchanged hex from the old system — still the only
 * chromatic move. Two contrast-tuned values per the source CSS's
 * "Two-Oranges Rule": [Accent] fills, and any text/icon on a NIGHT ground
 * (5.76:1 on `#0b0c0e`). On a light ground it's only 2.92:1 — fills and
 * large graphics only, never text there. [AccentOnLight] is every accent
 * use on a light ground, any size (4.86:1 on base, 5.29:1 on veil). Never
 * mix the two on the same ground.
 */
val Accent = Color(0xFFFF4726)
val AccentOnLight = Color(0xFFC03210)

/**
 * Frosted-panel fill. Opacities are bumped from the source CSS's
 * marketing-page defaults (0.54 / 0.74) toward its own "Operate mode"
 * guidance (0.72-0.84) — this is a dense transcription tool, not a hero
 * landing page, and label text needs to stay legible over whatever the
 * panel is sitting on. Dark veil isn't in the source CSS (a marketing page
 * doesn't need one) — derived from `--night-2`'s RGB at matching opacity so
 * it reads as the same "lighter, raised, translucent" surface in dark mode.
 */
val VeilLight = Color(0xD9FFFFFF) // white @ ~0.85
val VeilStrongLight = Color(0xF0FFFFFF) // white @ ~0.94
val VeilDark = Color(0xD316181C) // night-2 @ ~0.82
val VeilStrongDark = Color(0xEB16181C) // night-2 @ ~0.92

/** Internal dividers / chip outlines ONLY — never a panel's outer edge. */
val HairLight = Color(0x1F0B0C0E) // 0.12
val HairStrongLight = Color(0x380B0C0E) // 0.22
val HairDark = Color(0x29F4F5F5) // ~0.16, mirrored for a dark ground
val HairStrongDark = Color(0x47F4F5F5) // ~0.28

// ─── Status — functional, never decorative. Pushed further from the
// brand hue than feels natural on purpose: the brand chroma is warm, so a
// status red sitting close to Accent would read as brand, not as state.
// (The old theme mapped M3 `error` straight to Accent — this fixes that.)
val StatusErrorLight = Color(0xFF9F1239)
val StatusErrorDark = Color(0xFFFF8FA3)
val StatusSuccessLight = Color(0xFF0F5D3A)
val StatusSuccessDark = Color(0xFF6FD3A8)
val StatusWarningLight = Color(0xFF7A4B00)
val StatusWarningDark = Color(0xFFECB862)
val StatusInfoLight = Color(0xFF14507A)
val StatusInfoDark = Color(0xFF8CC4EE)
