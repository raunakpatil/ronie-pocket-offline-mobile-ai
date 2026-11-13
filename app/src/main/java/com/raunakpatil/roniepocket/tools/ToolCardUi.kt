package com.raunakpatil.roniepocket.tools

import android.content.Context
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.raunakpatil.roniepocket.ui.DotMatrix
import com.raunakpatil.roniepocket.ui.EyebrowText
import com.raunakpatil.roniepocket.ui.GradientBar
import com.raunakpatil.roniepocket.ui.Ic
import com.raunakpatil.roniepocket.ui.IconDisc
import com.raunakpatil.roniepocket.ui.PillButton
import com.raunakpatil.roniepocket.ui.R
import com.raunakpatil.roniepocket.ui.RoundButton
import com.raunakpatil.roniepocket.ui.Tile
import java.io.File

/** One tool call shown in Ronie's reply. */
data class ToolCard(
    val id: Long,
    val tool: String,
    /** What it will do / did, in plain words. */
    val text: String,
    val state: State,
    /** "Sends data to api.notion.com" for connector tools. */
    val sendsTo: String? = null,
    val canUndo: Boolean = false,
    /** Timer cards: when the timer ends (epoch ms) and its full length, for the live countdown. */
    val timerEndsAt: Long? = null,
    val timerTotalMs: Long = 0L,
    /** Web search: numbered sources, shown as chips that open the page. */
    val sources: List<com.raunakpatil.roniepocket.web.Source> = emptyList(),
    /** find_images: pictures from the web, shown in a row under the card. */
    val pictures: List<com.raunakpatil.roniepocket.web.WebImage> = emptyList(),
    /** Drawn image file for generate_image. */
    val image: File? = null,
    /** Progress fraction 0.0 .. 1.0 for image generation. */
    val progress: Float? = null,
    /** Real-time intermediate preview bitmap taking shape while generating. */
    val previewBitmap: android.graphics.Bitmap? = null,
    val currentStep: Int? = null,
    val totalSteps: Int? = null,
    val prompt: String? = null,
    val stageText: String? = null,
) {
    enum class State { RUNNING, ASK, DONE, FAILED, DECLINED, UNDONE }
}

/** Plain-words description of a call, for the card ("Delete task 'laundry'"). */
fun describeCall(spec: ToolSpec, args: Map<String, Any?>): String {
    fun a(k: String) = args[k]?.toString().orEmpty()
    return when (spec.name) {
        "create_task" -> "Add to your list: ${a("title")}" + (args["due"]?.let { " · due ${Builtins.pretty(it.toString())}" } ?: "")
        "list_tasks" -> "Check your to-do list"
        "complete_task" -> "Tick off: ${a("title")}"
        "delete_task" -> "Delete the task \"${a("title")}\" for good"
        "set_reminder" -> "Remind you ${Builtins.pretty(a("time"))}: ${a("text")}"
        "set_timer" -> "Start a ${Builtins.duration((((args["minutes"] as? Number)?.toDouble() ?: 0.0) * 60).toInt())} timer"
        "stop_timer" -> "Stop " + (args["label"]?.let { "the $it timer" } ?: "your timer")
        "set_alarm" -> "Set an alarm for ${a("time")}"
        "get_calendar" -> "Read your calendar"
        "remember" -> "Remember: ${a("fact")}"
        "web_search" -> "Search the web: ${a("query")}"
        "find_images" -> "Find pictures: ${a("query")}"
        "generate_image" -> "Draw: ${a("prompt")}"
        else -> spec.remoteName + if (args.isEmpty()) "" else " · " + args.entries.joinToString(", ") { (k, v) -> "$k: ${v.toString().take(80)}" }
    }
}

/** How each tool looks: a label, an icon and a gradient for its disc. */
private data class Look(val label: String, val icon: ImageVector, val colors: List<Color>)

private fun look(tool: String): Look = when (tool) {
    "create_task", "list_tasks", "complete_task", "delete_task" -> Look("To-do list", Ic.Task, R.meadow)
    "set_reminder" -> Look("Reminder", Ic.Bell, R.aurora)
    "set_timer", "stop_timer" -> Look("Timer", Ic.Timer, R.sunset)
    "set_alarm" -> Look("Alarm", Ic.Alarm, R.sunset)
    "get_calendar" -> Look("Calendar", Ic.Calendar, R.ocean)
    "remember" -> Look("Memory", Ic.Brain, R.aurora)
    "web_search" -> Look("Web", Ic.Link, R.ocean)
    "find_images" -> Look("Pictures", Ic.Link, R.aurora)
    "generate_image" -> Look("Image Generator", Ic.Brush, R.aurora)
    else -> Look("Connector", Ic.Link, listOf(Color(0xFF6B6B74), Color(0xFF3A3A40)))
}

@Composable
fun ToolCardView(
    c: ToolCard,
    onConfirm: (Boolean) -> Unit,
    onUndo: () -> Unit,
    onImageClick: (String) -> Unit = {},
    onEditPrompt: (String) -> Unit = {},
    onRerun: (String) -> Unit = {},
) {
    when {
        c.state == ToolCard.State.ASK -> AskTile(c, onConfirm)
        c.state == ToolCard.State.DONE && c.timerEndsAt != null -> TimerTile(c)
        c.tool == "generate_image" || c.image != null -> ImageGenTile(c, onImageClick, onEditPrompt, onRerun)
        else -> ActionRow(c, onUndo, onImageClick)
    }
}

/** A finished (or running / failed / undone) action: icon disc, what happened, Undo. */
@Composable
private fun ActionRow(c: ToolCard, onUndo: () -> Unit, onImageClick: (String) -> Unit = {}) {
    val l = look(c.tool)
    val failed = c.state == ToolCard.State.FAILED
    val off = c.state == ToolCard.State.DECLINED || c.state == ToolCard.State.UNDONE
    Tile(Modifier.fillMaxWidth().animateContentSize(), shape = R.cardShape, padding = PaddingValues(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.alpha(if (off || c.state == ToolCard.State.RUNNING) 0.45f else 1f)) {
                IconDisc(if (failed) Ic.Warn else l.icon, R.diagonal(if (failed) R.ember else l.colors))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                EyebrowText(when (c.state) {
                    ToolCard.State.RUNNING -> "${l.label} · working"
                    ToolCard.State.FAILED -> "${l.label} · didn't work"
                    ToolCard.State.DECLINED -> "${l.label} · cancelled"
                    ToolCard.State.UNDONE -> "${l.label} · undone"
                    else -> l.label
                }, color = if (failed) R.red else R.muted)
                Spacer(Modifier.height(2.dp))
                Text(c.text, color = if (off) R.muted else R.text, fontSize = 15.sp, lineHeight = 20.sp,
                    textDecoration = if (off) TextDecoration.LineThrough else null)
                c.sendsTo?.let { Text("Sends data to $it" + if (c.sources.isNotEmpty()) " + the sites below" else "",
                    style = MaterialTheme.typography.labelSmall, color = R.muted) }
            }
            if (c.state == ToolCard.State.DONE && c.canUndo) RoundButton(Ic.Undo, "Undo", onUndo, size = 48.dp, background = R.surface3)
        }
        if (c.sources.isNotEmpty()) SourceChips(c.sources)
        if (c.pictures.isNotEmpty()) PictureRow(c.pictures, onImageClick)
    }
}

/** Pictures from the web for an explanation: a row of thumbnails with what each shows; tap to see it large. */
@Composable
private fun PictureRow(pictures: List<com.raunakpatil.roniepocket.web.WebImage>, onImageClick: (String) -> Unit) {
    Row(Modifier.padding(top = 10.dp).fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        pictures.forEach { p ->
            Column(Modifier.width(150.dp)) {
                val bm = com.raunakpatil.roniepocket.ui.rememberThumb(p.path, 480)
                Box(Modifier.size(150.dp, 112.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF141419)).clickable { onImageClick(p.path) }) {
                    if (bm != null) androidx.compose.foundation.Image(bm, p.title, Modifier.matchParentSize(),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                }
                Text(p.title, style = MaterialTheme.typography.labelSmall, color = R.muted, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

/** Numbered sources ("1 · wikipedia.org"): tap to open the page in the browser. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SourceChips(sources: List<com.raunakpatil.roniepocket.web.Source>) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.foundation.layout.FlowRow(
        Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        sources.forEach { src ->
            com.raunakpatil.roniepocket.ui.Chip("${src.n} · ${src.host}", {
                runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(src.url))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            })
        }
    }
}

/** Ronie wants to do something that needs a yes: what exactly, where data goes, and two clear buttons. */
@Composable
private fun AskTile(c: ToolCard, onConfirm: (Boolean) -> Unit) {
    val l = look(c.tool)
    Tile(Modifier.fillMaxWidth().border(1.dp, Brush.horizontalGradient(R.ember.map { it.copy(alpha = 0.55f) }), R.tileShape)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconDisc(l.icon, R.diagonal(R.ember), size = 36.dp)
            Spacer(Modifier.width(10.dp))
            EyebrowText("Ronie wants to", color = R.orange)
        }
        Spacer(Modifier.height(10.dp))
        Text(c.text, color = R.text, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium)
        c.sendsTo?.let {
            Spacer(Modifier.height(4.dp))
            Text("Sends data to $it", style = MaterialTheme.typography.labelMedium, color = R.muted)
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("Do it", { onConfirm(true) }, Modifier.weight(1f))
            PillButton("Cancel", { onConfirm(false) }, Modifier.weight(1f), primary = false)
        }
    }
}

/**
 * A timer Ronie started: a sunset tile with dot-matrix digits ticking down every second and a bar that
 * empties. Counts from the end time, so it stays right after scrolling or the screen turning off. The Clock
 * app does the ringing.
 */
@Composable
private fun TimerTile(c: ToolCard) {
    val endsAt = c.timerEndsAt ?: return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(endsAt) {
        while (System.currentTimeMillis() < endsAt) {
            now = System.currentTimeMillis()
            // Wake just after the next whole second, so the digits change exactly on the second.
            kotlinx.coroutines.delay(((endsAt - now) % 1000L).let { if (it == 0L) 1000L else it } + 5L)
        }
        now = System.currentTimeMillis()
    }
    val leftMs = (endsAt - now).coerceAtLeast(0L)
    val done = leftMs == 0L
    val secs = (leftMs + 999L) / 1000L          // round up: 10, 9 … 1, then 0:00
    val digits = if (secs >= 3600) "%d:%02d:%02d".format(secs / 3600, (secs % 3600) / 60, secs % 60)
        else "%d:%02d".format(secs / 60, secs % 60)
    Tile(Modifier.fillMaxWidth(), brush = R.diagonal(if (done) R.ember else R.sunset)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            EyebrowText(if (done) "Time's up" else "Timer", color = Color.White.copy(alpha = 0.85f), modifier = Modifier.weight(1f))
            // Top right: the length, plus the label if there is one ("10 min · pasta").
            Text(c.text.replace(" timer", ""),
                color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
        Spacer(Modifier.height(14.dp))
        DotMatrix(digits, Modifier.align(Alignment.CenterHorizontally), dot = if (digits.length > 5) 7.dp else 9.dp,
            on = Color.White, off = Color.White.copy(alpha = 0.16f))
        Spacer(Modifier.height(16.dp))
        GradientBar(if (c.timerTotalMs > 0) leftMs.toFloat() / c.timerTotalMs else 0f,
            listOf(Color.White, Color.White.copy(alpha = 0.8f)), track = Color.White.copy(alpha = 0.22f))
        Spacer(Modifier.height(8.dp))
        Text(if (done) "Ringing · say \"stop the timer\" or tap Stop" else "Rings on this phone", color = Color.White.copy(alpha = 0.8f),
            style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * Living-art tile for on-device image generation:
 * - Running: dot-matrix progress ("DRAWING...")
 * - Failed: failure explanation
 * - Done: rounded living-art framed image with tap-to-fullscreen and Save, Share, Again, Edit buttons
 */
@Composable
private fun ImageGenTile(
    c: ToolCard,
    onImageClick: (String) -> Unit,
    onEditPrompt: (String) -> Unit,
    onRerun: (String) -> Unit
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val l = look(c.tool)

    Tile(
        Modifier.fillMaxWidth().animateContentSize(),
        shape = R.cardShape,
        padding = PaddingValues(12.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconDisc(l.icon, R.diagonal(l.colors))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = when (c.state) {
                            ToolCard.State.RUNNING -> "Generating Image Locally..."
                            ToolCard.State.FAILED -> "Image generation failed"
                            ToolCard.State.DONE -> c.prompt?.takeIf { it.isNotBlank() } ?: c.text.takeIf { it.isNotBlank() && it != "Generating Image Locally..." } ?: "Generated Artwork"
                            else -> c.prompt?.takeIf { it.isNotBlank() } ?: "Generated Artwork"
                        },
                        color = if (c.state == ToolCard.State.FAILED) R.red else R.text,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }

            when (c.state) {
                ToolCard.State.RUNNING -> {
                    Spacer(Modifier.height(14.dp))

                    // 1. Live preview image taking shape
                    val previewBmp = c.previewBitmap
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 220.dp, max = 320.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFF141419))
                            .border(1.dp, Brush.horizontalGradient(R.aurora.map { it.copy(alpha = 0.5f) }), RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (previewBmp != null) {
                            androidx.compose.foundation.Image(
                                bitmap = previewBmp.asImageBitmap(),
                                contentDescription = "Generating image preview",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 220.dp, max = 320.dp),
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop
                            )
                        } else {
                            // Initial state before step 1 completes
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier.padding(24.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(R.green)
                                )
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    c.stageText ?: "Shaping image latents…",
                                    color = R.muted,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }

                        // Bottom badge inside preview
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(8.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.Black.copy(alpha = 0.65f))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(R.cyan)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    if (previewBmp != null) "PREVIEW · STEP ${c.currentStep ?: 1}/${c.totalSteps ?: 4}" else "GENERATING",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    letterSpacing = 0.5.sp
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // 2. Smooth animated progress bar
                    val targetProgress = (c.progress ?: 0.08f).coerceIn(0.05f, 1f)
                    val animatedProgress by animateFloatAsState(
                        targetValue = targetProgress,
                        animationSpec = spring(stiffness = Spring.StiffnessLow),
                        label = "ImageGenProgress"
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color(0xFF202028))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(animatedProgress)
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(Brush.horizontalGradient(R.aurora))
                        )
                    }

                    Spacer(Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            c.stageText ?: "Step ${(c.currentStep ?: 1).coerceAtLeast(1)} of ${c.totalSteps ?: 4}",
                            color = R.muted,
                            fontSize = 12.sp
                        )
                        Text(
                            "${(animatedProgress * 100).toInt()}%",
                            color = R.cyan,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                ToolCard.State.DONE -> {
                    val file = c.image
                    val bitmap = file?.let { com.raunakpatil.roniepocket.ui.rememberThumb(it.absolutePath, 1024) }
                    if (bitmap != null) {
                        Spacer(Modifier.height(12.dp))

                        // The reveal plays once, right after drawing. Scrolling back to the card or reopening the
                        // chat shows the finished picture straight away (it used to flip again every time).
                        var revealed by androidx.compose.runtime.saveable.rememberSaveable(c.id) { androidx.compose.runtime.mutableStateOf(c.previewBitmap == null) }
                        val startRevealed = remember { revealed }
                        val flipRotation = remember { Animatable(if (startRevealed) 180f else 0f) }
                        val shineProgress = remember { Animatable(if (startRevealed) 2f else -1f) }
                        val buttonsAlpha = remember { Animatable(if (startRevealed) 1f else 0f) }

                        LaunchedEffect(Unit) {
                            if (startRevealed) return@LaunchedEffect
                            revealed = true
                            // 3D card flip animation
                            flipRotation.animateTo(
                                targetValue = 180f,
                                animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing)
                            )
                            // Specular shine sweep across newly revealed image
                            shineProgress.animateTo(
                                targetValue = 2f,
                                animationSpec = tween(durationMillis = 750, easing = LinearOutSlowInEasing)
                            )
                            // Action buttons slide/fade in
                            buttonsAlpha.animateTo(
                                targetValue = 1f,
                                animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow)
                            )
                        }

                        val isFlipped = flipRotation.value >= 90f

                        // 3D Flipping Card Container
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .graphicsLayer {
                                    rotationY = flipRotation.value
                                    cameraDistance = 16f * density
                                }
                        ) {
                            if (!isFlipped) {
                                // Back face: preview state during first half of flip
                                val previewBmp = c.previewBitmap
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 220.dp, max = 340.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(Color(0xFF141419))
                                        .border(1.dp, Brush.horizontalGradient(R.aurora.map { it.copy(alpha = 0.5f) }), RoundedCornerShape(14.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (previewBmp != null) {
                                        androidx.compose.foundation.Image(
                                            bitmap = previewBmp.asImageBitmap(),
                                            contentDescription = "Preview",
                                            modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 340.dp),
                                            contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                        )
                                    }
                                }
                            } else {
                                // Front face: finished image (flipped 180° back so it renders upright)
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .graphicsLayer { rotationY = 180f }
                                ) {
                                    androidx.compose.foundation.Image(
                                        bitmap = bitmap,
                                        contentDescription = c.text,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 340.dp)
                                            .clip(RoundedCornerShape(14.dp))
                                            .border(1.dp, Brush.horizontalGradient(R.aurora.map { it.copy(alpha = 0.5f) }), RoundedCornerShape(14.dp))
                                            .clickable { onImageClick(file!!.absolutePath) },
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                    )

                                    // Radiant specular shine sweep overlay
                                    if (shineProgress.value in -0.5f..1.8f) {
                                        Canvas(
                                            modifier = Modifier
                                                .matchParentSize()
                                                .clip(RoundedCornerShape(14.dp))
                                        ) {
                                            val s = shineProgress.value
                                            val w = size.width
                                            val h = size.height
                                            val startX = w * s - w * 0.35f
                                            val endX = w * s + w * 0.35f
                                            drawRect(
                                                brush = Brush.linearGradient(
                                                    colors = listOf(
                                                        Color.Transparent,
                                                        Color.White.copy(alpha = 0.45f),
                                                        Color.White.copy(alpha = 0.85f),
                                                        Color.White.copy(alpha = 0.45f),
                                                        Color.Transparent
                                                    ),
                                                    start = Offset(startX, 0f),
                                                    end = Offset(endX, h)
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        // Action Buttons with staggered fade-in
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .alpha(buttonsAlpha.value),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val btnPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                            val btnMinHeight = 36.dp
                            val btnFontSize = 13.sp

                            PillButton("Save", {
                                val saved = saveToGallery(ctx, file!!)
                                val msg = if (saved) "Saved to Pictures/Ronie" else "Couldn't save image"
                                android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
                            }, Modifier.weight(1f), primary = false, minHeight = btnMinHeight, padding = btnPadding, fontSize = btnFontSize)

                            PillButton("Share", {
                                shareImage(ctx, file!!)
                            }, Modifier.weight(1f), primary = false, minHeight = btnMinHeight, padding = btnPadding, fontSize = btnFontSize)

                            PillButton("Again", {
                                onRerun(c.prompt ?: c.text.removePrefix("Drew: ").removePrefix("Draw: "))
                            }, Modifier.weight(1f), primary = false, minHeight = btnMinHeight, padding = btnPadding, fontSize = btnFontSize)

                            PillButton("Edit", {
                                onEditPrompt("Change this picture: ")
                            }, Modifier.weight(1f), primary = true, minHeight = btnMinHeight, padding = btnPadding, fontSize = btnFontSize)
                        }
                    }
                }
                else -> { /* FAILED handled by header */ }
            }
        }
    }
}

private fun saveToGallery(context: Context, imageFile: File): Boolean = runCatching {
    val resolver = context.contentResolver
    val contentValues = android.content.ContentValues().apply {
        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "Ronie_${System.currentTimeMillis()}.png")
        put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/png")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Ronie")
            put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
        }
    }
    val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues) ?: return false
    resolver.openOutputStream(uri)?.use { out ->
        imageFile.inputStream().use { it.copyTo(out) }
    }
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
        contentValues.clear()
        contentValues.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, contentValues, null, null)
    }
    true
}.getOrDefault(false)

private fun shareImage(context: Context, imageFile: File) {
    runCatching {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.files",
            imageFile
        )
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(android.content.Intent.createChooser(intent, "Share image").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

@Composable private fun Box(modifier: Modifier, content: @Composable () -> Unit) =
    androidx.compose.foundation.layout.Box(modifier) { content() }
