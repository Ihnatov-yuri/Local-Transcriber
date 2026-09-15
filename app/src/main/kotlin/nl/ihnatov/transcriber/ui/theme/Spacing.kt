package nl.ihnatov.transcriber.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The spacing scale. Kept from the old editorial system rather than
 * widened to match Material 3 Expressive's airier defaults — panels
 * (see [nl.ihnatov.transcriber.ui.components.Panel]) now provide their own
 * internal padding, so tightening/loosening this shared scale would touch
 * every screen at once for a benefit that's better delivered locally.
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
