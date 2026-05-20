package nl.ihnatov.transcriber.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The editorial-design palette. Two surfaces (paper + paper edge), one
 * ink (with soft/muted variants), two hairline weights, one accent.
 *
 * Per design principle #2: [Accent] is the ONLY chromatic move in the
 * system and is used as punctuation, never decoration. Before adding
 * orange to a new element, find another orange element on the same
 * screen and ask which one should win.
 *
 * Values match `theme.jsx` in the design folder; do not "tune" them
 * here without updating the spec and screenshots together.
 */

// ─── Light (the sheet, ink-on-paper) ─────────────────────────────────
val PaperLight = Color(0xFFF6F2EA)
val PaperEdgeLight = Color(0xFFEDE7DC)
val InkLight = Color(0xFF16130F)
val InkSoftLight = Color(0xA316130F)        // 0.62
val InkMutedLight = Color(0x6616130F)       // 0.40
val HairlineLight = Color(0x2916130F)       // 0.16
val HairlineSoftLight = Color(0x1A16130F)   // 0.10

// ─── Dark (inverted, same vibe) ──────────────────────────────────────
val PaperDark = Color(0xFF16130F)
/**
 * The page background AROUND the sheet — darker than [PaperDark]
 * (the sheet itself). Only matters when something explicitly renders
 * the "outside the paper" region; most of our screens fill with
 * PaperDark edge-to-edge.
 */
val PaperEdgeDark = Color(0xFF0E0C0A)
/**
 * Slightly LIFTED warm-dark surface. Used as `surfaceVariant` in the
 * dark colorScheme so elevated chrome (bottom nav, player bar, the
 * editorial-design "raised" elements) reads as ON TOP OF the paper
 * sheet rather than recessed into it. Light mode handles this via
 * the paper-edge being darker than the sheet; dark mode flips — the
 * raised surface needs to be lighter than the sheet.
 */
val PaperRaisedDark = Color(0xFF22201D)
val InkDark = Color(0xFFF6F2EA)
val InkSoftDark = Color(0xA3F6F2EA)
val InkMutedDark = Color(0x5CF6F2EA)
val HairlineDark = Color(0x2EF6F2EA)
val HairlineSoftDark = Color(0x1AF6F2EA)

/** THE orange. Only chromatic move in the system. */
val Accent = Color(0xFFFF4726)
