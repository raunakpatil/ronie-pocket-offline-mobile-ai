package com.raunakpatil.roniepocket.ui

import androidx.compose.ui.graphics.asImageBitmap
import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

/** True when the user turned animations off in Android settings (we then show still versions). */
@Composable
fun reducedMotion(): Boolean {
    val ctx = LocalContext.current
    return remember {
        runCatching { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
}

/** Shrinks slightly while pressed (immediate tap feedback). */
@Composable
private fun pressScale(source: MutableInteractionSource): Float {
    val pressed by source.collectIsPressedAsState()
    return animateFloatAsState(if (pressed) 0.95f else 1f, tween(120), label = "press").value
}

// ---------------------------------------------------------------------- tiles

/**
 * The basic building block: a dark glassy tile with big rounded corners and a faint 1 dp edge, or a vivid
 * gradient tile when [brush] is given. A soft highlight along the top gives the "glass" feel.
 */
@Composable
fun Tile(
    modifier: Modifier = Modifier,
    brush: Brush? = null,
    shape: Shape = R.tileShape,
    padding: PaddingValues = PaddingValues(18.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .clip(shape)
            .then(if (brush != null) Modifier.background(brush) else Modifier.background(R.surface))
            .drawBehind {
                drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = if (brush != null) 0.18f else 0.04f),
                    0.45f to Color.Transparent))
            }
            .border(1.dp, if (brush != null) Color.White.copy(alpha = 0.22f) else R.edge, shape)
            .padding(padding),
        content = content,
    )
}

// ---------------------------------------------------------------------- status pill

/** Which gradient a status uses. */
enum class Mood(val colors: List<Color>) {
    THINKING(listOf(Color(0xFF00E5FF), Color(0xFF8A5CFF), Color(0xFFFF2E97), Color(0xFF2F6BFF))),
    WORKING(listOf(Color(0xFFFF8A1F), Color(0xFFFF2E97), Color(0xFFFFB13B), Color(0xFFFF3358))),
    LISTENING(listOf(Color(0xFFC6FF3D), Color(0xFF2BFFB0), Color(0xFF00E5FF), Color(0xFF9BE15D))),
    SPEAKING(listOf(Color(0xFF00E5FF), Color(0xFF2F6BFF), Color(0xFF7FE7FF), Color(0xFF8A5CFF))),
    SEARCHING(listOf(Color(0xFF8B8CA3), Color(0xFF2A2A38), Color(0xFFD8D9E6), Color(0xFF4F5068))),
}

/**
 * The glowing pill that says what Ronie is doing ("Thinking…", "Checking your calendar…"), from the
 * reference boards: coloured light drifting slowly behind frosted glass. Still when animations are off.
 */
@Composable
fun StatusPill(label: String, mood: Mood, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null,
               height: Dp = 56.dp, minWidth: Dp = 196.dp) {
    val still = reducedMotion()
    val clock = if (still) null else rememberInfiniteTransition(label = "pill").animateFloat(
        0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(9000, easing = LinearEasing)), label = "drift")
    val src = remember { MutableInteractionSource() }
    Box(
        modifier
            .scale(pressScale(src))
            .height(height)
            .widthIn(min = minWidth)
            .clip(R.pill)
            .drawBehind {
                val t = clock?.value ?: 0.6f   // read at draw time: redraw, no recomposition
                drawRect(Color(0xFF0E0E12))
                mood.colors.forEachIndexed { i, c ->
                    val a = t + i * 1.7f
                    val center = Offset(size.width * (0.5f + 0.42f * cos(a)), size.height * (0.5f + 0.6f * sin(a * 1.3f + i)))
                    val r = size.width * 0.42f
                    drawCircle(Brush.radialGradient(listOf(c.copy(alpha = 0.95f), c.copy(alpha = 0f)), center, r), r, center)
                }
                // Frosted glass on top: a soft white sheen, darker at the bottom.
                drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.28f), Color.White.copy(alpha = 0.05f),
                    Color.Black.copy(alpha = 0.18f))))
            }
            .border(1.dp, Color.White.copy(alpha = 0.28f), R.pill)
            .then(if (onClick != null) Modifier.clickable(src, null, role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = if (height < 50.dp) 22.dp else 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontSize = if (height < 50.dp) 16.sp else 18.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** The pill's drifting coloured light (same look as [StatusPill]), for shapes drawn by hand (the title morph). */
fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPillGlow(mood: Mood, t: Float) {
    drawRect(Color(0xFF0E0E12))
    mood.colors.forEachIndexed { i, c ->
        val a = t + i * 1.7f
        val center = Offset(size.width * (0.5f + 0.42f * cos(a)), size.height * (0.5f + 0.6f * sin(a * 1.3f + i)))
        val r = size.width * 0.42f + size.height * 0.4f
        drawCircle(Brush.radialGradient(listOf(c.copy(alpha = 0.95f), c.copy(alpha = 0f)), center, r), r, center)
    }
    drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.28f), Color.White.copy(alpha = 0.05f), Color.Black.copy(alpha = 0.18f))))
}

// ---------------------------------------------------------------------- buttons

/** Round icon button, at least 48 dp (Android touch-target minimum). [brush] fills it with a gradient. */
@Composable
fun RoundButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    background: Color = R.surface2,
    brush: Brush? = null,
    tint: Color = R.text,
    enabled: Boolean = true,
) {
    val src = remember { MutableInteractionSource() }
    Box(
        modifier
            .scale(pressScale(src))
            .size(size)
            .clip(R.pill)
            .then(if (brush != null) Modifier.background(brush) else Modifier.background(background))
            .border(1.dp, if (brush != null) Color.White.copy(alpha = 0.25f) else R.edge, R.pill)
            .clickable(src, null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (enabled) tint else tint.copy(alpha = 0.35f), modifier = Modifier.size(size * 0.46f))
    }
}

/** Pill button: white for the main action (like the references' "Next"), glass for the rest. */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    enabled: Boolean = true,
    brush: Brush? = null,
    minHeight: Dp = 52.dp,
    padding: PaddingValues = PaddingValues(horizontal = 22.dp, vertical = 14.dp),
    fontSize: androidx.compose.ui.unit.TextUnit = 16.sp,
) {
    val src = remember { MutableInteractionSource() }
    val haptic = LocalHapticFeedback.current
    Box(
        modifier
            .scale(pressScale(src))
            .defaultMinSize(minHeight = minHeight)
            .clip(R.pill)
            .then(when {
                brush != null -> Modifier.background(brush)
                primary -> Modifier.background(if (enabled) R.text else R.surface3)
                else -> Modifier.background(R.surface2)
            })
            .border(1.dp, if (primary && brush == null) Color.Transparent else Color.White.copy(alpha = 0.16f), R.pill)
            .clickable(src, null, enabled = enabled, role = Role.Button) {
                if (primary) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
            .padding(padding),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = fontSize, fontWeight = FontWeight.Medium, maxLines = 1,
            color = when { brush != null -> Color.White; primary && enabled -> R.bg; enabled -> R.text; else -> R.muted })
    }
}

/** Small glass chip (suggestions, quick actions). 48 dp tall so it's easy to hit. */
@Composable
fun Chip(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val src = remember { MutableInteractionSource() }
    Box(
        modifier
            .scale(pressScale(src))
            .defaultMinSize(minHeight = 48.dp)
            .clip(R.pill)
            .background(R.surface)
            .border(1.dp, R.edge, R.pill)
            .clickable(src, null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = 15.sp, color = R.text, maxLines = 1) }
}

/** A thin progress bar with a gradient fill. */
@Composable
fun GradientBar(fraction: Float, colors: List<Color>, modifier: Modifier = Modifier, height: Dp = 6.dp, track: Color = R.surface3) {
    Box(modifier.fillMaxWidth().height(height).clip(R.pill).background(track)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(height).clip(R.pill).background(Brush.horizontalGradient(colors)))
    }
}

// ---------------------------------------------------------------------- dot-matrix digits

/** 5×7 dot-matrix glyphs (digits, colon, a few letters), like the clocks on the reference boards. */
internal val GLYPHS: Map<Char, List<String>> = mapOf(
    '0' to listOf("01110", "10001", "10011", "10101", "11001", "10001", "01110"),
    '1' to listOf("00100", "01100", "00100", "00100", "00100", "00100", "01110"),
    '2' to listOf("01110", "10001", "00001", "00010", "00100", "01000", "11111"),
    '3' to listOf("11111", "00010", "00100", "00010", "00001", "10001", "01110"),
    '4' to listOf("00010", "00110", "01010", "10010", "11111", "00010", "00010"),
    '5' to listOf("11111", "10000", "11110", "00001", "00001", "10001", "01110"),
    '6' to listOf("00110", "01000", "10000", "11110", "10001", "10001", "01110"),
    '7' to listOf("11111", "00001", "00010", "00100", "01000", "01000", "01000"),
    '8' to listOf("01110", "10001", "10001", "01110", "10001", "10001", "01110"),
    '9' to listOf("01110", "10001", "10001", "01111", "00001", "00010", "01100"),
    ':' to listOf("0", "1", "1", "0", "1", "1", "0"),
    ' ' to listOf("00", "00", "00", "00", "00", "00", "00"),
    '-' to listOf("000", "000", "000", "111", "000", "000", "000"),
    'R' to listOf("11110", "10001", "10001", "11110", "10100", "10010", "10001"),
    'O' to listOf("01110", "10001", "10001", "10001", "10001", "10001", "01110"),
    'N' to listOf("10001", "11001", "10101", "10011", "10001", "10001", "10001"),
    'I' to listOf("11111", "00100", "00100", "00100", "00100", "00100", "11111"),
    'E' to listOf("11111", "10000", "10000", "11110", "10000", "10000", "11111"),
)

/**
 * Text drawn as a dot matrix: lit dots in [on], every other dot of the grid faintly in [off] (so the grid
 * shows, like an LED panel). [dot] is the dot pitch.
 */
@Composable
fun DotMatrix(text: String, modifier: Modifier = Modifier, dot: Dp = 7.dp, on: Color = Color.White, off: Color = Color.White.copy(alpha = 0.12f)) {
    val glyphs = text.map { GLYPHS[it] ?: GLYPHS[' ']!! }
    val cols = glyphs.sumOf { it[0].length } + (glyphs.size - 1).coerceAtLeast(0)
    val px = with(LocalDensity.current) { dot.toPx() }
    val w = with(LocalDensity.current) { (px * cols).toDp() }
    Canvas(modifier.size(w, dot * 7).semantics { contentDescription = text }) {
        val r = px * 0.36f
        var x0 = 0
        glyphs.forEachIndexed { gi, g ->
            val gw = g[0].length
            for (row in 0 until 7) for (col in 0 until gw) {
                val lit = g[row][col] == '1'
                drawRoundRect(if (lit) on else off,
                    topLeft = Offset((x0 + col) * px + (px / 2 - r), row * px + (px / 2 - r)),
                    size = androidx.compose.ui.geometry.Size(r * 2, r * 2), cornerRadius = CornerRadius(r * 0.45f))
            }
            x0 += gw
            if (gi < glyphs.lastIndex) {
                for (row in 0 until 7) drawRoundRect(off, topLeft = Offset(x0 * px + (px / 2 - r), row * px + (px / 2 - r)),
                    size = androidx.compose.ui.geometry.Size(r * 2, r * 2), cornerRadius = CornerRadius(r * 0.45f))
                x0 += 1
            }
        }
    }
}

/** A tiny section label above a number ("EARNINGS", "TIMER"). */
@Composable
fun EyebrowText(text: String, color: Color = R.muted, modifier: Modifier = Modifier) =
    Text(text.uppercase(), style = Eyebrow, color = color, modifier = modifier)

/** Centred box helper for icons inside tiles. */
@Composable
fun IconDisc(icon: ImageVector, brush: Brush, size: Dp = 40.dp, content: @Composable BoxScope.() -> Unit = {}) {
    Box(Modifier.size(size).clip(R.pill).background(brush), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(size * 0.5f))
        content()
    }
}

// ---------------------------------------------------------------------- icons (Material paths, Apache-2.0)

private fun icon(name: String, vararg paths: String): ImageVector = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
    paths.forEach { addPath(addPathNodes(it), fill = SolidColor(Color.Black)) }
}.build()

object Ic {
    val Menu = icon("menu", "M3,18h18v-2H3v2zM3,13h18v-2H3v2zM3,6v2h18V6H3z")
    val Plus = icon("plus", "M19,13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z")
    val Send = icon("send", "M4.01,6.03l7.51,3.22 -7.52,-1 0.01,-2.22m7.5,8.72L4,17.97v-2.22l7.51,-1M2.01,3L2,10l15,2 -15,2 0.01,7L23,12 2.01,3z")
    val ArrowUp = icon("arrow_up", "M4,12l1.41,1.41L11,7.83V20h2V7.83l5.58,5.59L20,12l-8,-8 -8,8z")
    val Mic = icon("mic", "M12,14c1.66,0 2.99,-1.34 2.99,-3L15,5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3zM17.3,11c0,3 -2.54,5.1 -5.3,5.1S6.7,14 6.7,11L5,11c0,3.41 2.72,6.23 6,6.72L11,21h2v-3.28c3.28,-0.48 6,-3.3 6,-6.72h-1.7z")
    val Stop = icon("stop", "M6,6h12v12H6z")
    val VolumeOff = icon("volume_off", "M16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v2.21l2.45,2.45c0.03,-0.2 0.05,-0.41 0.05,-0.63zM19,12c0,0.94 -0.2,1.82 -0.54,2.64l1.51,1.51C20.63,14.91 21,13.5 21,12c0,-4.28 -2.99,-7.86 -7,-8.77v2.06c2.89,0.86 5,3.54 5,6.71zM4.27,3L3,4.27 7.73,9H3v6h4l5,5v-6.73l4.25,4.25c-0.67,0.52 -1.42,0.93 -2.25,1.18v2.06c1.38,-0.31 2.63,-0.95 3.69,-1.81L19.73,21 21,19.73l-9,-9L4.27,3zM12,4L9.91,6.09 12,8.18V4z")
    val Check = icon("check", "M9,16.17L4.83,12l-1.42,1.41L9,19 21,7l-1.41,-1.41z")
    val Close = icon("close", "M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 19,17.59 13.41,12z")
    val Undo = icon("undo", "M12.5,8c-2.65,0 -5.05,0.99 -6.9,2.6L2,7v9h9l-3.62,-3.62c1.39,-1.16 3.16,-1.88 5.12,-1.88 3.54,0 6.55,2.31 7.6,5.5l2.37,-0.78C21.08,11.03 17.15,8 12.5,8z")
    val Timer = icon("timer", "M15,1H9v2h6V1zM11,14h2V8h-2v6zM19.03,7.39l1.42,-1.42c-0.43,-0.51 -0.9,-0.99 -1.41,-1.41l-1.42,1.42C16.07,4.74 14.12,4 12,4c-4.97,0 -9,4.03 -9,9s4.02,9 9,9 9,-4.03 9,-9c0,-2.12 -0.74,-4.07 -1.97,-5.61zM12,20c-3.87,0 -7,-3.13 -7,-7s3.13,-7 7,-7 7,3.13 7,7 -3.13,7 -7,7z")
    val Alarm = icon("alarm", "M22,5.72l-4.6,-3.86 -1.29,1.53 4.6,3.86L22,5.72zM7.88,3.39L6.6,1.86 2,5.71l1.29,1.53 4.59,-3.85zM12.5,8H11v6l4.75,2.85 0.75,-1.23 -4,-2.37V8zM12,4c-4.97,0 -9,4.03 -9,9s4.02,9 9,9c4.97,0 9,-4.03 9,-9s-4.03,-9 -9,-9zM12,20c-3.87,0 -7,-3.13 -7,-7s3.13,-7 7,-7 7,3.13 7,7 -3.13,7 -7,7z")
    val Bell = icon("bell", "M12,22c1.1,0 2,-0.9 2,-2h-4c0,1.1 0.89,2 2,2zM18,16v-5c0,-3.07 -1.64,-5.64 -4.5,-6.32V4c0,-0.83 -0.67,-1.5 -1.5,-1.5s-1.5,0.67 -1.5,1.5v0.68C7.63,5.36 6,7.92 6,11v5l-2,2v1h16v-1l-2,-2z")
    val Calendar = icon("calendar", "M19,4h-1V2h-2v2H8V2H6v2H5c-1.11,0 -1.99,0.9 -1.99,2L3,20c0,1.1 0.89,2 2,2h14c1.1,0 2,-0.9 2,-2V6c0,-1.1 -0.9,-2 -2,-2zM19,20H5V10h14v10zM9,14H7v-2h2v2zM13,14h-2v-2h2v2zM17,14h-2v-2h2v2zM9,18H7v-2h2v2zM13,18h-2v-2h2v2zM17,18h-2v-2h2v2z")
    val Task = icon("task", "M19,3h-4.18C14.4,1.84 13.3,1 12,1s-2.4,0.84 -2.82,2H5c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2V5c0,-1.1 -0.9,-2 -2,-2zM12,3c0.55,0 1,0.45 1,1s-0.45,1 -1,1 -1,-0.45 -1,-1 0.45,-1 1,-1zM10,17l-4,-4 1.41,-1.41L10,14.17l6.59,-6.59L18,9l-8,8z")
    val Brain = icon("memory", "M13,3C9.23,3 6.19,5.95 6,9.66l-1.92,2.53C3.84,12.5 4.08,13 4.5,13H6v3c0,1.1 0.9,2 2,2h1v3h7v-4.68c2.36,-1.12 4,-3.53 4,-6.32C20,6.13 16.87,3 13,3z")
    val Link = icon("link", "M3.9,12c0,-1.71 1.39,-3.1 3.1,-3.1h4V7H7c-2.76,0 -5,2.24 -5,5s2.24,5 5,5h4v-1.9H7c-1.71,0 -3.1,-1.39 -3.1,-3.1zM8,13h8v-2H8v2zM17,7h-4v1.9h4c1.71,0 3.1,1.39 3.1,3.1s-1.39,3.1 -3.1,3.1h-4V17h4c2.76,0 5,-2.24 5,-5s-2.24,-5 -5,-5z")
    val Trash = icon("trash", "M6,19c0,1.1 0.9,2 2,2h8c1.1,0 2,-0.9 2,-2V7H6v12zM19,4h-3.5l-1,-1h-5l-1,1H5v2h14V4z")
    val Back = icon("back", "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z")
    val Gear = icon("gear", "M19.14,12.94c0.04,-0.3 0.06,-0.61 0.06,-0.94c0,-0.32 -0.02,-0.64 -0.07,-0.94l2.03,-1.58c0.18,-0.14 0.23,-0.41 0.12,-0.61l-1.92,-3.32c-0.12,-0.22 -0.37,-0.29 -0.59,-0.22l-2.39,0.96c-0.5,-0.38 -1.03,-0.7 -1.62,-0.94L14.4,2.81c-0.04,-0.24 -0.24,-0.41 -0.48,-0.41h-3.84c-0.24,0 -0.43,0.17 -0.47,0.41L9.25,5.35C8.66,5.59 8.12,5.92 7.63,6.29L5.24,5.33c-0.22,-0.08 -0.47,0 -0.59,0.22L2.74,8.87C2.62,9.08 2.66,9.34 2.86,9.48l2.03,1.58C4.84,11.36 4.8,11.69 4.8,12s0.02,0.64 0.07,0.94l-2.03,1.58c-0.18,0.14 -0.23,0.41 -0.12,0.61l1.92,3.32c0.12,0.22 0.37,0.29 0.59,0.22l2.39,-0.96c0.5,0.38 1.03,0.7 1.62,0.94l0.36,2.54c0.05,0.24 0.24,0.41 0.48,0.41h3.84c0.24,0 0.44,-0.17 0.47,-0.41l0.36,-2.54c0.59,-0.24 1.13,-0.56 1.62,-0.94l2.39,0.96c0.22,0.08 0.47,0 0.59,-0.22l1.92,-3.32c0.12,-0.22 0.07,-0.47 -0.12,-0.61L19.14,12.94zM12,15.6c-1.98,0 -3.6,-1.62 -3.6,-3.6s1.62,-3.6 3.6,-3.6s3.6,1.62 3.6,3.6S13.98,15.6 12,15.6z")
    val Chat = icon("chat", "M20,2H4c-1.1,0 -1.99,0.9 -1.99,2L2,22l4,-4h14c1.1,0 2,-0.9 2,-2V4c0,-1.1 -0.9,-2 -2,-2zM18,14H6v-2h12v2zM18,11H6V9h12v2zM18,8H6V6h12v2z")
    val Warn = icon("warn", "M1,21h22L12,2 1,21zM13,18h-2v-2h2v2zM13,14h-2v-4h2v4z")
    val Brush = icon("brush", "M7,14c-1.66,0 -3,1.34 -3,3 0,1.31 -1.16,2 -2,2 0.92,1.22 2.49,2 4,2 2.21,0 4,-1.79 4,-4 0,-1.66 -1.34,-3 -3,-3zM20.71,4.63l-1.34,-1.34c-0.39,-0.39 -1.02,-0.39 -1.41,0L9,12.25 11.75,15l8.96,-8.96c0.39,-0.39 0.39,-1.02 0,-1.41z")
    val Share = icon("share", "M18,16.08c-0.76,0 -1.44,0.3 -1.96,0.77L8.91,12.7c0.05,-0.23 0.09,-0.46 0.09,-0.7s-0.04,-0.47 -0.09,-0.7l7.05,-4.11c0.54,0.5 1.25,0.81 2.04,0.81 1.66,0 3,-1.34 3,-3s-1.34,-3 -3,-3 -3,1.34 -3,3c0,0.24 0.04,0.47 0.09,0.7L8.04,9.81C7.5,9.31 6.79,9 6,9c-1.66,0 -3,1.34 -3,3s1.34,3 3,3c0.79,0 1.5,-0.31 2.04,-0.81l7.12,4.16c-0.05,0.21 -0.08,0.43 -0.08,0.65 0,1.61 1.31,2.92 2.92,2.92s2.92,-1.31 2.92,-2.92c0,-1.61 -1.31,-2.92 -2.92,-2.92z")
    val Save = icon("save", "M19,9h-4V3H9v6H5l7,7 7,-7zM5,18v2h14v-2H5z")
    val Refresh = icon("refresh", "M17.65,6.35C16.2,4.9 14.21,4 12,4c-4.42,0 -7.99,3.58 -7.99,8s3.57,8 7.99,8c3.73,0 6.84,-2.55 7.73,-6h-2.08c-0.82,2.33 -3.04,4 -5.65,4 -3.31,0 -6,-2.69 -6,-6s2.69,-6 6,-6c1.66,0 3.14,0.69 4.22,1.78L13,11h7V4l-2.35,2.35z")
    val Edit = icon("edit", "M3,17.25V21h3.75L17.81,9.94l-3.75,-3.75L3,17.25zM20.71,7.04c0.39,-0.39 0.39,-1.02 0,-1.41l-2.34,-2.34c-0.39,-0.39 -1.02,-0.39 -1.41,0l-1.83,1.83 3.75,3.75 1.83,-1.83z")
}

// ---------------------------------------------------------------------- switch

/** A labelled switch row (lime when on), 48 dp tall. */
@Composable
fun SwitchRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = androidx.compose.material3.MaterialTheme.typography.titleSmall, color = R.text, modifier = Modifier.weight(1f))
        androidx.compose.material3.Switch(
            checked = checked, onCheckedChange = onChange, enabled = enabled,
            colors = androidx.compose.material3.SwitchDefaults.colors(
                checkedThumbColor = R.bg, checkedTrackColor = R.lime, checkedBorderColor = R.lime,
                uncheckedThumbColor = R.muted, uncheckedTrackColor = R.surface3, uncheckedBorderColor = R.faint,
            ),
        )
    }
}

// ---------------------------------------------------------------------- shine

/**
 * Text with a silver shine sweeping across it every few seconds (drawn only on the letters).
 * Still when animations are off.
 */
@Composable
fun ShineText(text: String, style: androidx.compose.ui.text.TextStyle, color: Color, modifier: Modifier = Modifier) {
    val still = reducedMotion()
    val shine = if (still) null else rememberInfiniteTransition(label = "shine").animateFloat(
        -0.4f, 1.4f,
        infiniteRepeatable(androidx.compose.animation.core.keyframes {
            durationMillis = 4200
            -0.4f at 0
            1.4f at 1700 using androidx.compose.animation.core.FastOutSlowInEasing
            1.4f at 4200
        }), label = "x")
    Text(
        text, style = style, color = color,
        modifier = modifier
            .graphicsLayer(compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen)
            .drawWithContent {
                drawContent()
                val x = shine?.value ?: -1f
                if (x > -0.4f && x < 1.4f) {
                    val band = size.width * 0.28f
                    val cx = size.width * x
                    drawRect(
                        Brush.linearGradient(
                            listOf(Color.Transparent, Color(0xCCFFFFFF), Color(0xFFF4F6FA), Color(0xCCFFFFFF), Color.Transparent),
                            start = Offset(cx - band, 0f), end = Offset(cx + band, size.height * 0.6f),
                        ),
                        blendMode = androidx.compose.ui.graphics.BlendMode.SrcAtop,
                    )
                }
            },
    )
}

// ---------------------------------------------------------------------- LED build-up

/**
 * Dot-matrix text that "builds" itself: letter dots light up left to right up to [progress] (0..1), the dots
 * just ahead of the light flicker on and off like an LED panel powering up, the rest stay faint.
 */
@Composable
fun LedText(text: String, progress: Float, modifier: Modifier = Modifier, dot: Dp = 7.dp, on: Color = Color.White) {
    val glyphs = text.map { GLYPHS[it] ?: GLYPHS[' ']!! }
    val cols = glyphs.sumOf { it[0].length } + (glyphs.size - 1).coerceAtLeast(0)
    val still = reducedMotion()
    // Read at draw time only (no recomposition per frame).
    val clock = if (still) null else rememberInfiniteTransition(label = "led").animateFloat(
        0f, 1000f, infiniteRepeatable(tween(90_000, easing = LinearEasing)), label = "frame")
    Canvas(modifier.size(dot * cols, dot * 7).semantics { contentDescription = text }) {
        val frame = ((clock?.value ?: 0f) * 11).toInt()
        val px = dot.toPx()
        val r = px * 0.36f
        val lit = Array(7) { BooleanArray(cols) }
        var x0 = 0
        glyphs.forEach { g ->
            for (row in 0 until 7) for (col in g[0].indices) if (g[row][col] == '1') lit[row][x0 + col] = true
            x0 += g[0].length + 1
        }
        for (row in 0 until 7) for (col in 0 until cols) {
            val f = if (cols > 1) col.toFloat() / (cols - 1) else 1f
            val h = ((col * 73856093) xor (row * 19349663) xor (frame * 83492791)).let { (it ushr 3) and 1023 } / 1023f
            val alpha = when {
                lit[row][col] && f <= progress -> 1f
                lit[row][col] && f <= progress + 0.18f && h > 0.5f -> 0.55f
                else -> 0.06f
            }
            drawRoundRect((if (alpha > 0.06f) on else Color.White).copy(alpha = alpha),
                topLeft = Offset(col * px + (px / 2 - r), row * px + (px / 2 - r)),
                size = androidx.compose.ui.geometry.Size(r * 2, r * 2), cornerRadius = CornerRadius(r * 0.45f))
        }
    }
}


/**
 * Photos in the chat. Decoding a full 1536 px JPEG on the main thread for a 60 dp thumbnail made scrolling
 * stutter, so pictures are decoded in the background at roughly the size they're shown, and kept in a small cache.
 */
object Thumbs {
    private val cache = android.util.LruCache<String, androidx.compose.ui.graphics.ImageBitmap>(32)
    fun cached(path: String, maxPx: Int): androidx.compose.ui.graphics.ImageBitmap? = cache.get("$path@$maxPx")
    fun load(path: String, maxPx: Int): androidx.compose.ui.graphics.ImageBitmap? = runCatching {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
        val bm = android.graphics.BitmapFactory.decodeFile(path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
        bm?.asImageBitmap()?.also { cache.put("$path@$maxPx", it) }
    }.getOrNull()
}

/** The picture at [path], at most about [maxPx] on its long side; null until it's decoded (or if it can't be). */
@Composable
fun rememberThumb(path: String, maxPx: Int): androidx.compose.ui.graphics.ImageBitmap? {
    val state = androidx.compose.runtime.produceState(Thumbs.cached(path, maxPx), path, maxPx) {
        if (value == null) value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Thumbs.load(path, maxPx) }
    }
    return state.value
}
