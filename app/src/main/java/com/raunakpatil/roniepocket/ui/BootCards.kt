package com.raunakpatil.roniepocket.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.raunakpatil.roniepocket.BootStep
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * What Ronie is doing while he wakes up, in the chat area: one calm card in the middle showing the current
 * stage with its own quiet abstract art. When a stage finishes, the card flips over to the next one
 * (1 → 6). A row of small dots underneath shows how far along he is, and the last finished stage's time.
 */
@Composable
fun BootCards(steps: List<BootStep>, name: String?, modifier: Modifier = Modifier) {
    if (steps.isEmpty()) return
    // The card shows the running stage (or the last one once everything is done).
    val current = steps.indexOfFirst { it.active }.takeIf { it >= 0 } ?: (steps.indexOfLast { it.done }).coerceAtLeast(0)
    val rot by animateFloatAsState(current * 180f, spring(dampingRatio = 0.78f, stiffness = 140f), label = "flip")
    val face = ((rot + 90f) / 180f).toInt().coerceIn(0, steps.lastIndex)    // which stage is facing us mid-flip
    val lastDone = steps.lastOrNull { it.done }

    Column(modifier.fillMaxWidth().padding(top = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(name?.takeIf { it.isNotBlank() }?.let { "Getting ready for you, $it" } ?: "Getting ready for you",
            color = R.muted, fontSize = 15.sp)
        Spacer(Modifier.height(22.dp))
        val shape = RoundedCornerShape(28.dp)
        Box(
            Modifier.fillMaxWidth(0.82f).height(210.dp)
                .graphicsLayer { rotationY = rot; cameraDistance = 16f * density },
        ) {
            val st = steps[face]
            // Each face is un-mirrored by its own half-turn.
            Box(Modifier.fillMaxSize().graphicsLayer { rotationY = face * 180f }.clip(shape).background(R.surface)
                .border(1.dp, Brush.linearGradient(FACES[face % FACES.size].map { it.copy(alpha = 0.35f) }), shape)) {
                // ModulateAlpha: dim the art without an off-screen buffer every frame.
                Art(face, st.active, Modifier.fillMaxSize().graphicsLayer {
                    alpha = 0.55f; compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha })
                Column(Modifier.align(Alignment.BottomStart).padding(18.dp)) {
                    EyebrowText("Step ${face + 1} of ${steps.size}", color = FACES[face % FACES.size].first().copy(alpha = 0.8f))
                    Spacer(Modifier.height(4.dp))
                    Text(st.label, color = R.text, fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    Text(st.detail, color = R.muted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        // Progress: a quiet row of dots (done = lit, current = breathing, waiting = faint).
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            steps.forEachIndexed { i, s ->
                val c = FACES[i % FACES.size].first()
                Box(Modifier.size(if (s.active) 8.dp else 6.dp).clip(R.pill).background(when {
                    s.done -> c.copy(alpha = 0.85f); s.active -> c; else -> Color.White.copy(alpha = 0.12f)
                }))
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(lastDone?.let { d -> "${d.label} · " + (d.ms?.let { if (it < 1000) "$it ms" else "%.1f s".format(it / 1000.0) } ?: "done") } ?: " ",
            color = R.faint, style = MaterialTheme.typography.labelSmall)
    }
}

private val FACES = listOf(R.ocean, R.aurora, R.meadow, R.sunset, listOf(R.cyan, R.blue), R.ember)

/** Live abstract art for each stage (still when animations are off or the stage isn't running). */
@Composable
private fun Art(i: Int, live: Boolean, modifier: Modifier) {
    val moving = live && !reducedMotion()
    // The clock is read inside the draw block (not during composition), so each frame only redraws this
    // canvas instead of rebuilding the card: that's what made the waves stutter while the GPU was busy.
    val clock = if (moving) rememberInfiniteTransition(label = "art").animateFloat(0f, (2 * PI).toFloat(),
        infiniteRepeatable(tween(3600, easing = LinearEasing)), label = "t") else null
    Canvas(modifier) {
        val t = clock?.value ?: 0.8f
        when (i) {
            0 -> rings(t)
            1 -> neurons(t)
            2 -> dotGrid(t)
            3 -> waves(t)
            4 -> bars(t)
            else -> orbit(t)
        }
    }
}

// Engine: concentric arcs sweeping at different speeds (like the reference ring charts).
private fun DrawScope.rings(t: Float) {
    val c = Offset(size.width * 0.68f, size.height * 0.40f)
    listOf(Triple(R.cyan, 1f, 0.34f), Triple(R.blue, -1.4f, 0.25f), Triple(R.violet, 2f, 0.16f)).forEachIndexed { k, (col, sp, rr) ->
        val r = size.minDimension * rr
        drawCircle(col.copy(alpha = 0.12f), r, c, style = Stroke(5.dp.toPx()))
        drawArc(Brush.sweepGradient(listOf(Color.Transparent, col), c), (t * sp * 57.3f) + k * 70f, 230f, false,
            Offset(c.x - r, c.y - r), Size(r * 2, r * 2), style = Stroke(5.dp.toPx(), cap = StrokeCap.Round))
    }
}

// Brain: a little neuron grid with pulses travelling through it.
private fun DrawScope.neurons(t: Float) {
    val cols = 6; val rows = 3
    val pts = (0 until rows).flatMap { r -> (0 until cols).map { c ->
        Offset(size.width * (0.12f + 0.76f * c / (cols - 1)) + 6.dp.toPx() * sin(t + r + c), size.height * (0.14f + 0.42f * r / (rows - 1)))
    } }
    pts.forEachIndexed { k, p ->
        val right = if ((k + 1) % cols != 0) pts[k + 1] else null
        val down = pts.getOrNull(k + cols)
        listOfNotNull(right, down).forEach { q ->
            val glow = 0.5f + 0.5f * sin(t * 2 + k * 0.9f)
            drawLine(R.violet.copy(alpha = 0.15f + 0.35f * glow), p, q, 1.5.dp.toPx())
        }
    }
    pts.forEachIndexed { k, p ->
        val b = 0.5f + 0.5f * sin(t * 3 + k * 1.7f)
        drawCircle(Brush.radialGradient(listOf(R.pink.copy(alpha = 0.6f * b), Color.Transparent), p, 10.dp.toPx()), 10.dp.toPx(), p)
        drawCircle(if (b > 0.7f) Color.White else R.violet, 3.dp.toPx(), p)
    }
}

// "Who I am": a habit-style grid of squares filling in.
private fun DrawScope.dotGrid(t: Float) {
    val cols = 9; val rows = 4
    val cell = size.width * 0.82f / cols
    val filled = ((t / (2 * PI)) * cols * rows * 1.2).toInt()
    for (r in 0 until rows) for (c in 0 until cols) {
        val k = r * cols + c
        val on = k < filled || ((k * 37) % 11 == 0)
        val col = if (on) listOf(R.lime, R.green, Color(0xFF9BE15D))[k % 3] else Color.White.copy(alpha = 0.06f)
        drawRoundRect(col.copy(alpha = if (on) 0.9f else 0.06f), Offset(size.width * 0.09f + c * cell, size.height * 0.1f + r * cell),
            Size(cell * 0.78f, cell * 0.78f), androidx.compose.ui.geometry.CornerRadius(cell * 0.2f))
    }
}

// Warm-up: flowing sunset waves.
private fun DrawScope.waves(t: Float) {
    R.sunset.forEachIndexed { k, col ->
        val path = androidx.compose.ui.graphics.Path()
        for (s in 0..40) {
            val x = size.width * s / 40f
            val y = size.height * (0.32f + 0.08f * k) + size.height * 0.14f * sin(x / size.width * 6f + t * (1 + k * 0.4f) + k)
            if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, col.copy(alpha = 0.9f - k * 0.2f), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
    }
}

// Speed: an equalizer of bars.
private fun DrawScope.bars(t: Float) {
    val n = 11
    val w = size.width * 0.8f / n
    for (k in 0 until n) {
        val h = size.height * (0.12f + 0.4f * abs(sin(t * 1.6f + k * 0.55f)))
        val x = size.width * 0.1f + k * w
        drawRoundRect(Brush.verticalGradient(listOf(R.cyan, R.blue), startY = size.height * 0.62f - h, endY = size.height * 0.62f),
            Offset(x, size.height * 0.62f - h), Size(w * 0.6f, h), androidx.compose.ui.geometry.CornerRadius(w * 0.3f))
    }
}

// Tools: little moons orbiting a core.
private fun DrawScope.orbit(t: Float) {
    val c = Offset(size.width * 0.66f, size.height * 0.38f)
    drawCircle(Brush.radialGradient(listOf(R.orange, R.red.copy(alpha = 0f)), c, 26.dp.toPx()), 26.dp.toPx(), c)
    listOf(18f to 1.6f, 30f to -1f, 42f to 0.7f).forEachIndexed { k, (rd, sp) ->
        val r = rd.dp.toPx()
        drawCircle(Color.White.copy(alpha = 0.07f), r, c, style = Stroke(1.dp.toPx()))
        val a = t * sp + k * 2f
        drawCircle(listOf(R.orange, R.pink, R.lime)[k], 4.dp.toPx(), Offset(c.x + r * cos(a), c.y + r * sin(a)))
    }
}
