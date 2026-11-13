package com.raunakpatil.roniepocket.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * Voice mode's waveform (from the user's reference): a thin white line with soft glowing lobes in blue,
 * cyan, pink and violet that bloom from the middle and swell with the user's voice. [level] is microphone
 * loudness 0..1 (smoothed here). [grow] 0..1 lets the line open out from the centre as the mic turns into it.
 */
@Composable
fun VoiceWave(level: Float, modifier: Modifier = Modifier, grow: Float = 1f) {
    val still = reducedMotion()
    val lvl by animateFloatAsState(level, spring(dampingRatio = 0.6f, stiffness = 220f), label = "level")
    val clock = if (still) null else rememberInfiniteTransition(label = "wave").animateFloat(
        0f, (2 * PI).toFloat(), infiniteRepeatable(tween(5200, easing = LinearEasing)), label = "t")

    // Each lobe: colour, centre drift speed, phase, width (fraction of the line), how much it reacts.
    data class Lobe(val color: Color, val speed: Float, val phase: Float, val width: Float, val gain: Float)
    val lobes = listOf(
        Lobe(Color(0xFF3D8BFF), 1.0f, 0.0f, 0.16f, 1.00f),
        Lobe(Color(0xFF3DDCFF), 1.3f, 2.1f, 0.11f, 0.80f),
        Lobe(Color(0xFFFF5FA2), 0.8f, 4.0f, 0.10f, 0.70f),
        Lobe(Color(0xFF8B5CFF), 1.6f, 5.2f, 0.09f, 0.60f),
        Lobe(Color(0xFF7FE7FF), 2.1f, 1.2f, 0.06f, 0.55f),
    )

    Canvas(modifier.semantics { contentDescription = "Listening. Tap when you're done." }) {
        val t = clock?.value ?: 0.8f   // read at draw time: redraw, no recomposition
        val w = size.width
        val h = size.height
        val cy = h / 2
        val cx = w / 2
        val half = w / 2 * grow.coerceIn(0f, 1f)
        if (half < 1f) return@Canvas

        // Lobes, drawn additively so overlaps glow brighter (like the reference).
        lobes.forEachIndexed { i, l ->
            val center = cx + half * 0.45f * sin(t * l.speed + l.phase)
            val sigma = w * l.width * (0.8f + 0.5f * lvl)
            // Height: a quiet idle shimmer, then mostly the voice; each lobe breathes on its own.
            val amp = h * 0.48f * (0.10f + 0.90f * lvl * l.gain) * (0.75f + 0.25f * abs(sin(t * 2.3f * l.speed + i)))
            val top = Path()
            val bottom = Path()
            val steps = 40
            for (s in 0..steps) {
                val x = center - 3 * sigma + 6 * sigma * s / steps
                if (x < cx - half || x > cx + half) continue
                val g = exp(-((x - center) / sigma).let { it * it })
                val y = amp * g
                if (top.isEmpty) { top.moveTo(x, cy); bottom.moveTo(x, cy) }
                top.lineTo(x, cy - y)
                bottom.lineTo(x, cy + y * 0.9f)
            }
            top.lineTo(center + 3 * sigma, cy); top.close()
            bottom.lineTo(center + 3 * sigma, cy); bottom.close()
            val fill = Brush.verticalGradient(listOf(l.color.copy(alpha = 0f), l.color.copy(alpha = 0.75f), l.color.copy(alpha = 0f)),
                startY = cy - amp - 1, endY = cy + amp + 1)
            drawPath(top, fill, blendMode = BlendMode.Plus)
            drawPath(bottom, fill, blendMode = BlendMode.Plus)
        }

        // Soft white glow in the middle, then the thin line, fading out at both ends.
        drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.25f + 0.35f * lvl), Color.Transparent),
            Offset(cx, cy), h * (0.35f + 0.4f * lvl)), h * (0.35f + 0.4f * lvl), Offset(cx, cy), blendMode = BlendMode.Plus)
        drawLine(
            Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.9f), Color.White, Color.White.copy(alpha = 0.9f),
                Color.Transparent), startX = cx - half, endX = cx + half),
            Offset(cx - half, cy), Offset(cx + half, cy), strokeWidth = 1.6f * density,
        )
    }
}
