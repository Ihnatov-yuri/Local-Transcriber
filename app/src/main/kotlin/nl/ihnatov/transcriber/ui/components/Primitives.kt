package nl.ihnatov.transcriber.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.ihnatov.transcriber.ui.theme.Accent
import nl.ihnatov.transcriber.ui.theme.SairaCondensed
import nl.ihnatov.transcriber.ui.theme.Spacing

/**
 * Editorial-design primitives — the small set of composables every screen
 * uses to render the paper-and-ink look. Mirrors `primitives.jsx` in the
 * design folder one-to-one. Keep these dumb and theme-driven; if a screen
 * needs a different shape, build it from these, don't fork them.
 */

// ─── Sheet ───────────────────────────────────────────────────────────

/**
 * Full-screen paper wrapper. Replaces M3's [androidx.compose.material3.Scaffold]
 * content area for the editorial layout. Edge-to-edge is OFF — the system
 * bars are painted with the paper color in [nl.ihnatov.transcriber.ui.theme.TranscriberTheme]
 * so the sheet feels continuous from edge to edge.
 */
@Composable
fun Sheet(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(
        horizontal = Spacing.sheetPadding,
        vertical = 14.dp,
    ),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(padding),
        content = content,
    )
}

// ─── Mono caps ───────────────────────────────────────────────────────

/**
 * Mono-caps text wrapper. IBM Plex Mono, uppercased, tracked, ink-soft
 * by default. Used for all metadata-style labels — section indices,
 * dates, durations, eyebrows.
 */
@Composable
fun Mono(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.labelLarge,
    color: Color = LocalContentColor.current.copy(alpha = 0.62f),
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        style = style,
        color = color,
        maxLines = maxLines,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
    )
}

// ─── Brand strip ─────────────────────────────────────────────────────

/**
 * "transcriber" wordmark + 7-dp orange brand dot, baseline-aligned.
 * Optional [right] slot for a meta label or status indicator (mono caps,
 * usually ink-soft).
 */
@Composable
fun BrandStrip(
    modifier: Modifier = Modifier,
    right: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            "transcriber",
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.width(4.dp))
        Box(
            Modifier
                .size(7.dp)
                .offset(y = (-2).dp)
                .clip(CircleShape)
                .background(Accent),
        )
        if (right != null) {
            Spacer(Modifier.weight(1f))
            right()
        }
    }
}

// ─── Section index ───────────────────────────────────────────────────

/**
 * Mono "01 / LIBRARY" header — orange index number, dim slash, ink label.
 * Optional [summary] paragraph in Inter ink-soft, width-capped so it
 * doesn't run edge-to-edge on tablets.
 */
@Composable
fun SectionIndex(
    n: Int,
    label: String,
    summary: String? = null,
    modifier: Modifier = Modifier,
) {
    val ink = MaterialTheme.colorScheme.onBackground
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Mono(n.toString().padStart(2, '0'), color = Accent)
            Text(
                " / ",
                style = MaterialTheme.typography.labelLarge,
                color = ink.copy(alpha = 0.45f),
            )
            Mono(label, color = ink)
        }
        summary?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = ink.copy(alpha = 0.62f),
                modifier = Modifier.widthIn(max = 320.dp),
            )
        }
    }
}

// ─── Rules ───────────────────────────────────────────────────────────

/**
 * 1.5-dp ink line. Used as top-level joint (header → list, section →
 * section). NEVER for row → row separation — use [Hairline] for that.
 */
@Composable
fun InkRule(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.5.dp)
            .background(MaterialTheme.colorScheme.onBackground),
    )
}

/** 1-dp ink-at-16%-alpha line. Interior division between rows. */
@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.16f)),
    )
}

/** Even softer hairline (10% alpha) for tightly-packed sub-rows. */
@Composable
fun HairlineSoft(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.10f)),
    )
}

// ─── Big number ──────────────────────────────────────────────────────

/**
 * Saira Condensed big numerals with optional mono-caps orange suffix.
 * Used for the library metric strip, the record-screen timer, etc.
 * Tabular-old-style numerals (`tnum, lnum`) keep digit widths uniform.
 */
@Composable
fun BigNumber(
    value: String,
    suffix: String? = null,
    size: TextUnit = 42.sp,
    color: Color = MaterialTheme.colorScheme.onBackground,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            text = value,
            color = color,
            style = TextStyle(
                fontFamily = SairaCondensed,
                fontWeight = FontWeight.SemiBold,
                fontSize = size,
                lineHeight = (size.value * 0.88f).sp,
                fontFeatureSettings = "tnum, lnum",
                letterSpacing = (-0.015 * size.value).sp,
            ),
        )
        suffix?.let {
            Mono(
                it,
                style = MaterialTheme.typography.labelMedium,
                color = Accent,
                modifier = Modifier.padding(top = (size.value * 0.18f).dp),
            )
        }
    }
}

// ─── Ledger row ──────────────────────────────────────────────────────

/**
 * The workhorse row. 74-dp mono-caps left label / flexible body / optional
 * right meta. Used in Settings + as the chrome for many list rows.
 */
@Composable
fun LedgerRow(
    label: String,
    value: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    right: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    labelWidth: Dp = 74.dp,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = Spacing.rowVPad),
        verticalAlignment = Alignment.Top,
    ) {
        Mono(label, modifier = Modifier.width(labelWidth))
        Spacer(Modifier.width(Spacing.m))
        Box(Modifier.weight(1f)) {
            ProvideTextStyle(MaterialTheme.typography.bodyMedium) { value() }
        }
        right?.let {
            Spacer(Modifier.width(Spacing.m))
            it()
        }
    }
}

// ─── Pulse dot ───────────────────────────────────────────────────────

/**
 * The recording indicator. Solid 8-dp orange dot with a continuous
 * expand-and-fade outer ring. Per design principle #8 (motion is almost
 * still), this is the ONLY infinite animation in the entire app — every
 * other transition is 150–650 ms ease-out. Don't add more.
 */
@Composable
fun PulseDot(
    modifier: Modifier = Modifier,
    size: Dp = 8.dp,
    color: Color = Accent,
) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 2.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pulse-scale",
    )
    val alpha by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pulse-alpha",
    )
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(size * 2.4f),
    ) {
        Box(
            Modifier
                .size(size)
                .graphicsLayer(scaleX = scale, scaleY = scale, alpha = alpha)
                .clip(CircleShape)
                .background(color),
        )
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(color),
        )
    }
}

// ─── Inverse footer bar ──────────────────────────────────────────────

/**
 * The one inverse block per principle #7 — black bar at the bottom of
 * the screen, paper-colored text. Used for the single CTA on Record /
 * Library. Bleeds past the sheet's horizontal padding to the screen
 * edges via the negative-margin modifier in the call site.
 *
 * [left] / [right] are optional slots for a leading indicator (PulseDot
 * + label) or trailing icon (→ arrow). [body] is the centred main label
 * — usually a Saira Condensed Medium uppercase title plus mono-caps
 * subtitle.
 */
@Composable
fun InverseFooter(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    left: (@Composable () -> Unit)? = null,
    right: (@Composable () -> Unit)? = null,
    body: @Composable RowScope.() -> Unit,
) {
    val inkBg = MaterialTheme.colorScheme.onBackground
    val paperFg = MaterialTheme.colorScheme.background
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(inkBg)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ProvideTextStyle(MaterialTheme.typography.displaySmall.copy(color = paperFg)) {
            left?.invoke()
            Box(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    content = body,
                )
            }
            right?.invoke()
        }
    }
}

/** Alias for [androidx.compose.foundation.layout.RowScope] for the slot above. */
typealias RowScope = androidx.compose.foundation.layout.RowScope
