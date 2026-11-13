package com.raunakpatil.roniepocket.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp

/**
 * The top-bar title: a softly glowing status dot right beside the dot-matrix "RONIE", with the tagline
 * centred underneath. When Ronie works, the dot inflates like a bubble into the status pill ("Thinking…")
 * and swallows the title; when he's done it deflates back into the dot.
 *
 * Everything here is drawn at fixed positions (offsets and sizes driven by springs), so nothing gets
 * re-measured mid-animation: that's what made the first version jitter. The height springs a beat ahead of
 * the width with a little overshoot, which gives the bubble feel.
 */
@Composable
fun TitleMorph(
    pill: Pair<String, Mood>?,
    dotColor: Color,
    tagline: String,
    onPillClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    /** While waking up: how much of the dot-matrix title has lit up (0..1); 1 = fully built. */
    build: Float = 1f,
) {
    val active = pill != null
    var last by remember { mutableStateOf(pill ?: ("Thinking…" to Mood.THINKING)) }
    if (pill != null) last = pill
    val still = reducedMotion()

    // Bubble: height leads (stiffer, bouncier), width follows.
    val th by animateFloatAsState(if (active) 1f else 0f, spring(dampingRatio = 0.58f, stiffness = 760f), label = "h")
    val tw by animateFloatAsState(if (active) 1f else 0f, spring(dampingRatio = 0.72f, stiffness = 480f), label = "w")

    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium)
    val density = LocalDensity.current
    val labelW = with(density) { measurer.measure(last.first, labelStyle).size.width.toDp() }
    val pillW by animateDpAsState(max(150.dp, labelW + 44.dp), spring(dampingRatio = 0.8f, stiffness = 500f), label = "pillW")
    val pillH = 44.dp
    val dot = 9.dp

    // Fixed layout of the resting title: RONIE with the tagline centred under it; the dot sits outside, left of the R.
    val pitch = 2.6.dp
    val titleW = pitch * 29                 // R O N I E: 5 glyphs × 5 columns + 4 gaps
    val titleH = pitch * 7
    val dotBox = 12.dp
    val gap = 4.dp
    val taglineGap = 2.dp
    val taglineH = 15.dp
    val dotX = -titleW / 2 - gap - dotBox / 2      // where the dot sits, relative to the centre
    val dotY = -(taglineGap + taglineH) / 2

    // Animation clocks are read only inside drawBehind, so the top bar redraws but never recomposes per frame.
    val phaseClock = if (still) null else rememberInfiniteTransition(label = "glow").animateFloat(
        0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(9000, easing = LinearEasing)), label = "phase")
    val pulseClock = if (still) null else rememberInfiniteTransition(label = "pulse").animateFloat(
        0.35f, 1f, infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
    val dc by animateColorAsState(dotColor, tween(600), label = "dotColor")

    Box(modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) {
        // Resting title (fades and shrinks a touch as the bubble takes over).
        Column(
            Modifier.graphicsLayer {
                val k = (tw * 1.7f).coerceIn(0f, 1f)
                alpha = 1f - k; scaleX = 1f - 0.12f * k; scaleY = 1f - 0.12f * k
            },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The dot is drawn by the bubble below, just left of the R (outside this centred column).
            // While waking up, the title builds itself dot by dot; then it's the plain dot-matrix title.
            val built by animateFloatAsState(build, tween(900, easing = FastOutSlowInEasing), label = "build")
            if (built < 0.999f) LedText("RONIE", built, dot = pitch, on = R.text)
            else DotMatrix("RONIE", Modifier.semantics { contentDescription = "Ronie" }, dot = pitch,
                on = R.text, off = Color.White.copy(alpha = 0.06f))
            Spacer(Modifier.height(taglineGap))
            Text(tagline, style = MaterialTheme.typography.labelSmall, color = R.muted, maxLines = 1,
                textAlign = TextAlign.Center, modifier = Modifier.height(taglineH))
        }

        // The bubble: a dot at rest, the pill while working.
        val w: Dp = lerp(dot, pillW, tw).coerceAtLeast(dot)
        val h: Dp = lerp(dot, pillH, th).coerceAtLeast(dot)
        val src = remember { MutableInteractionSource() }
        Box(
            Modifier
                .offset(x = lerp(dotX, 0.dp, tw), y = lerp(dotY, 0.dp, th.coerceIn(0f, 1f)))
                .size(w, h)
                .clickable(src, null, enabled = active && onPillClick != null) { onPillClick?.invoke() }
                .drawBehind {
                    val p = ((tw + th) / 2f).coerceIn(0f, 1f)
                    val phase = phaseClock?.value ?: 0.6f
                    val pulse = pulseClock?.value ?: 0.7f
                    // Dot glow, fading away as it inflates (drawn beyond the bounds on purpose).
                    if (p < 0.5f) {
                        val gr = 8.dp.toPx() + size.minDimension / 2
                        drawCircle(Brush.radialGradient(listOf(dc.copy(alpha = 0.6f * pulse * (1f - p * 2f)), Color.Transparent),
                            center, gr), gr, center)
                    }
                    val r = size.height / 2
                    val shape = Path().apply {
                        addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, size.width, size.height, CornerRadius(r, r)))
                    }
                    clipPath(shape) {
                        if (p > 0.02f) drawPillGlow(last.second, phase)
                        drawRect(dc.copy(alpha = (1f - p * 1.6f).coerceIn(0f, 1f)))   // the dot's own colour melts into the glow
                    }
                    if (p > 0.3f) drawRoundRect(Color.White.copy(alpha = 0.28f * p), cornerRadius = CornerRadius(r, r), style = Stroke(1.dp.toPx()))
                    if (p < 0.4f) drawCircle(Color.White.copy(alpha = 0.55f * (1f - p * 2.5f).coerceIn(0f, 1f)), 1.4.dp.toPx(),
                        center.copy(x = center.x - 1.2.dp.toPx(), y = center.y - 1.2.dp.toPx()))
                },
            contentAlignment = Alignment.Center,
        ) {
            val labelAlpha = ((minOf(tw, th) - 0.6f) / 0.4f).coerceIn(0f, 1f)
            if (labelAlpha > 0f) Crossfade(last.first, label = "label") { text ->
                Text(text, style = labelStyle, color = Color.White, maxLines = 1,
                    modifier = Modifier.graphicsLayer { alpha = labelAlpha })
            }
        }
    }
}
