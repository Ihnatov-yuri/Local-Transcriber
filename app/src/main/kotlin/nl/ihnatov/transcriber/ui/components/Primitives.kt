package nl.ihnatov.transcriber.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import nl.ihnatov.transcriber.ui.theme.Accent
import nl.ihnatov.transcriber.ui.theme.Archivo
import nl.ihnatov.transcriber.ui.theme.BaseDark
import nl.ihnatov.transcriber.ui.theme.BaseLight
import nl.ihnatov.transcriber.ui.theme.Spacing
import nl.ihnatov.transcriber.ui.theme.VeilDark
import nl.ihnatov.transcriber.ui.theme.VeilLight
import nl.ihnatov.transcriber.ui.theme.VeilStrongDark
import nl.ihnatov.transcriber.ui.theme.VeilStrongLight

/**
 * Lit Field primitives — the small set of composables every screen uses to
 * render the veil-and-shadow look. Ported from `../Landing/docs/design.md`
 * §5 / `lit-field-tokens.css`'s component layer (see memory:
 * project-design-migration-lit-field-2026-09).
 *
 * The old editorial primitives built structure out of ruled lines
 * (`InkRule`, a heavy 1.5dp top-level joint) — Lit Field has no such
 * concept at all ("a panel's outer edge is never stroked"). Structure now
 * comes from grouping content inside a [Panel] instead. [Hairline] /
 * [HairlineSoft] survive, rescoped: Lit Field keeps a soft internal
 * divider for INSIDE a panel (row-to-row), just never as the primary
 * structural device between sections.
 *
 * Keep these dumb and theme-driven; if a screen needs a different shape,
 * build it from these, don't fork them.
 */

// ─── Sheet ───────────────────────────────────────────────────────────

/**
 * Full-screen page wrapper. Replaces M3's [androidx.compose.material3.Scaffold]
 * content area. Edge-to-edge is OFF — the system bars are painted with the
 * page color in [nl.ihnatov.transcriber.ui.theme.TranscriberTheme] so the
 * page feels continuous from edge to edge.
 *
 * Deliberately does NOT paint Lit Field's animated gradient/grain
 * "atmosphere" layer — the source CSS itself calls for muting that on
 * dense "Operate mode" surfaces (this is a tool app, not a hero landing
 * page), and an always-on animated background would fight this app's
 * battery/thermal budget during recording and on-device transcription.
 * A plain flat background carries the palette; [Panel] and [GlassPanel]
 * carry the rest of the identity.
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

// ─── Panel — the resting-surface primitive ────────────────────────────

/**
 * Lit Field's "veil" panel — the resting surface everything else builds
 * on. Frosted-glass-COLORED fill (no real blur; see [GlassPanel] for the
 * one surface that gets actual backdrop blur) with a soft lifted shadow
 * and rounded corners. This is what structure looks like now instead of
 * [Hairline] rules: group related content in one Panel rather than
 * separating rows with ink lines.
 *
 * [strong] bumps both fill opacity and shadow depth — use for at most one
 * panel per screen, the single most-lifted surface there (mirrors the
 * source CSS's own `.panel-strong` guidance).
 */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    strong: Boolean = false,
    shape: Shape = RoundedCornerShape(14.dp),
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val fill = when {
        dark && strong -> VeilStrongDark
        dark -> VeilDark
        strong -> VeilStrongLight
        else -> VeilLight
    }
    val ink = MaterialTheme.colorScheme.onBackground
    Column(
        modifier = modifier
            .shadow(
                elevation = if (strong) 14.dp else 7.dp,
                shape = shape,
                ambientColor = ink.copy(alpha = 0.10f),
                spotColor = ink.copy(alpha = 0.18f),
            )
            .clip(shape)
            .background(fill)
            .padding(contentPadding),
        content = content,
    )
}

/**
 * The ONE hero glass surface per screen — real backdrop blur via Haze,
 * not just a translucent fill. Per the 2026-09 Android-conventions
 * research behind this migration: native Android's own blur (Android 17)
 * is scoped tight — a few chrome surfaces, always tinted, never applied
 * wall-to-wall — and Telegram's full translucent-panel reskin drew real
 * backlash for reading as an iOS transplant that abandoned Android's own
 * identity. So: [GlassPanel] is deliberately reserved for one persistent,
 * floating surface per screen (today: RecordingDetailScreen's
 * `DetailPlayerBar`, floating over the transcript/output). Everything
 * else is [Panel].
 *
 * [hazeState] must be [dev.chrisbanes.haze.rememberHazeState]'d once at
 * the screen level and shared with a `Modifier.hazeSource(hazeState)` on
 * the scrolling content that sits BEHIND this panel — Haze blurs whatever
 * source content it captures, so a GlassPanel with nothing marked as its
 * source just renders its [HazeStyle.fallbackTint] scrim. On API ≤31 (and
 * automatically wherever the platform doesn't support it) Haze itself
 * substitutes a flat translucent scrim in place of real blur — no
 * fallback code needed here.
 */
@Composable
fun GlassPanel(
    hazeState: HazeState,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(18.dp),
    contentPadding: PaddingValues = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    content: @Composable RowScope.() -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val base = if (dark) BaseDark else BaseLight
    val veil = if (dark) VeilDark else VeilLight
    val ink = MaterialTheme.colorScheme.onBackground
    Row(
        modifier = modifier
            .shadow(
                elevation = 12.dp,
                shape = shape,
                ambientColor = ink.copy(alpha = 0.12f),
                spotColor = ink.copy(alpha = 0.22f),
            )
            .clip(shape)
            .hazeEffect(
                state = hazeState,
                style = HazeStyle(
                    backgroundColor = base,
                    tint = HazeTint(veil),
                    blurRadius = 18.dp,
                    noiseFactor = 0.1f,
                    fallbackTint = HazeTint(veil),
                ),
            )
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = horizontalArrangement,
        content = content,
    )
}

// ─── Mono caps ───────────────────────────────────────────────────────

/**
 * Tracked-caps metadata label. Uppercased, letter-spaced, ink-soft by
 * default. Used for section indices, dates, durations, eyebrows — every
 * place the old system reached for a dedicated mono typeface. Lit Field
 * carries no mono voice at all (see `ui/theme/Type.kt`), so this is now
 * Schibsted Grotesk via `MaterialTheme.typography.labelLarge` rather than
 * a literal monospace face — kept the name since ~20 call sites across
 * the app read it as "the metadata style," not literally "monospace."
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
 * Optional [summary] paragraph in ink-soft, width-capped so it doesn't
 * run edge-to-edge on tablets.
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
 * 1-dp ink-at-16%-alpha line. Interior division between rows WITHIN a
 * [Panel] or list — never between top-level sections (group those in
 * separate Panels instead; Lit Field has no structural top-level rule).
 */
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
 * Archivo big numerals with optional mono-caps orange suffix. Used for
 * the library metric strip, the record-screen timer, etc. Tabular-old-
 * style numerals (`tnum, lnum`) keep digit widths uniform.
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
                fontFamily = Archivo,
                fontWeight = FontWeight.SemiBold,
                fontSize = size,
                lineHeight = (size.value * 0.92f).sp,
                fontFeatureSettings = "tnum, lnum",
                letterSpacing = (-0.01 * size.value).sp,
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
 * expand-and-fade outer ring. Per design principle #6 (motion stays
 * almost still — the CSS's own animated gradient "bloom" is deliberately
 * NOT ported here, see [Sheet]), this remains one of a small handful of
 * intentional animations in the app. Don't add more without a reason.
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
 * The one inverse block per design principle #5 — a night-ground bar
 * pinned to the bottom of the screen, page-colored text. Used for the
 * single CTA on Record / Library. Rounded top corners + a lifted shadow
 * now (was a sharp full-bleed rectangle under the old flat/hairline
 * system) — reads as a floating bar docked to the bottom edge rather than
 * a slab, matching Lit Field's shape language.
 *
 * [left] / [right] are optional slots for a leading indicator (PulseDot
 * + label) or trailing icon (→ arrow). [body] is the centred main label
 * — usually a display-style uppercase title plus mono-caps subtitle.
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
    val pageFg = MaterialTheme.colorScheme.background
    val shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = 18.dp,
                shape = shape,
                ambientColor = inkBg.copy(alpha = 0.22f),
                spotColor = inkBg.copy(alpha = 0.34f),
            )
            .clip(shape)
            .background(inkBg)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ProvideTextStyle(MaterialTheme.typography.displaySmall.copy(color = pageFg)) {
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

/** Alias for [androidx.compose.foundation.layout.RowScope] for the slots above. */
typealias RowScope = androidx.compose.foundation.layout.RowScope
