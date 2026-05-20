package nl.ihnatov.transcriber.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The editorial spacing scale. Tighter than typical M3 because the design
 * has no cards — there are no 24-dp gaps between containers. All vertical
 * rhythm comes from hairlines, not whitespace.
 */
object Spacing {
    val xs: Dp = 4.dp
    val s: Dp = 8.dp
    val m: Dp = 12.dp
    val l: Dp = 18.dp
    val xl: Dp = 28.dp

    /** Inner padding of the [nl.ihnatov.transcriber.ui.components.Sheet]. */
    val sheetPadding: Dp = 18.dp

    /** Vertical padding for ledger-style rows. */
    val rowVPad: Dp = 11.dp
}

/** Top-level rule between header / list / section blocks. */
val InkRuleStroke: Dp = 1.5.dp

/** Interior divider between rows. */
val HairlineStroke: Dp = 1.dp
