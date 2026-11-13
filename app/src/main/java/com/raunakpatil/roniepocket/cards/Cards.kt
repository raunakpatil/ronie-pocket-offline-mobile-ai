package com.raunakpatil.roniepocket.cards

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Intent
import com.raunakpatil.roniepocket.ui.Chip
import com.raunakpatil.roniepocket.ui.EyebrowText
import com.raunakpatil.roniepocket.ui.GradientBar
import com.raunakpatil.roniepocket.ui.Ic
import com.raunakpatil.roniepocket.ui.R

/**
 * Renders an assistant reply as Ronie Cards: plain paragraphs stay text, lists become a checklist card
 * (tickable when the user asked for a list/plan) or a bullet card, numbered lists become a steps card,
 * tables become a compare card. Re-parsed on every streamed chunk, so cards grow as Ronie types.
 *
 * Every card gets its own personality ("living art", from the user's reference boards): a look (gradient
 * tile, glow corner, dot grid, gradient outline, accent bar, header band) and a palette, picked from the
 * message and the card's position, so no two cards in a chat look alike. Bullet dots each get a colour.
 */
@Composable
fun RonieReply(text: String, checklistMode: Boolean, modifier: Modifier = Modifier, seed: Long = 0L) {
    val blocks = remember(text) { Markdown.parse(text) }
    Column(modifier.animateContentSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        var cardNo = 0
        blocks.forEachIndexed { idx, b ->
            when (b) {
                is Block.Paragraph -> Text(inline(b.text), style = MaterialTheme.typography.bodyLarge, color = R.text)
                is Block.Heading -> Text(inline(b.text), style = if (b.level <= 2) MaterialTheme.typography.titleMedium
                    else MaterialTheme.typography.titleSmall, color = R.text)
                is Block.Bullets -> { val sk = skinFor(seed, cardNo++)
                    if (checklistMode) ChecklistCard(b.items, key = "$seed-$idx", sk) else BulletCard(b.items, sk) }
                is Block.Steps -> StepsCard(b.items, skinFor(seed, cardNo++))
                is Block.Table -> CompareCard(b, skinFor(seed, cardNo++))
                is Block.Code -> CodeCard(b.text)
                is Block.Expense -> ExpenseCard(b.json, skinFor(seed, cardNo++))
            }
        }
    }
}

/** Heuristic: did the user ask for something they'd want to tick off? */
fun wantsChecklist(userMessage: String): Boolean {
    val m = userMessage.lowercase()
    return listOf("list", "to-do", "todo", "checklist", "pack", "shopping", "groceries", "grocery", "plan", "tasks",
        "errands", "bring", "buy").any { it in m }
}

// ---------------------------------------------------------------------- skins

private enum class Look { GRADIENT, GLOW, DOTGRID, OUTLINE, BAR, BAND }

/** [onColor]: text sits on a vivid gradient (white text, light dots). */
private data class Skin(val look: Look, val palette: List<Color>, val angle: Float) {
    /** The colour the card's dots / numbers / ticks shade through. */
    val base: Color get() = if (look == Look.GRADIENT) Color.White else palette.first()
    val onColor get() = look == Look.GRADIENT
    val textColor get() = if (onColor) Color.White else R.text
    val subColor get() = if (onColor) Color.White.copy(alpha = 0.8f) else R.muted
}

// Neon Tokyo-night palettes (cyan, magenta, violet, neon orange, acid lime).
private val PALETTES = listOf(
    R.sunset, R.aurora, R.ocean, R.meadow, R.ember,
    listOf(R.pink, R.violet), listOf(R.cyan, R.pink), listOf(R.violet, R.blue),
)
// Readable with white text on top (for full-gradient tiles).
private val VIVID = listOf(listOf(R.violet, R.pink), listOf(R.blue, R.violet), listOf(R.pink, R.red), listOf(R.blue, R.cyan.copy(alpha = 0.9f)))

/**
 * One colour family per card, shading from dark to light down the list (user: "first point dark green,
 * then a little lighter…"), each dot with its own little gradient.
 */
private fun ramp(base: Color, i: Int, n: Int): Color {
    val t = if (n > 1) i.toFloat() / (n - 1) else 0f
    val dark = androidx.compose.ui.graphics.lerp(base, Color.Black, 0.45f)
    val light = androidx.compose.ui.graphics.lerp(base, Color.White, 0.30f)
    return androidx.compose.ui.graphics.lerp(dark, light, t)
}

private fun rampBrush(base: Color, i: Int, n: Int): Brush {
    val c = ramp(base, i, n)
    return Brush.linearGradient(listOf(androidx.compose.ui.graphics.lerp(c, Color.White, 0.25f), c))
}

private fun mix(seed: Long, n: Int): Int {
    var x = seed * 6364136223846793005L + n * 1442695040888963407L
    x = x xor (x ushr 29); x *= -0x4b47d5b1; x = x xor (x ushr 32)
    return (x and 0x7fffffff).toInt()
}

private fun skinFor(seed: Long, n: Int): Skin {
    val h = mix(seed, n)
    val look = Look.entries[h % Look.entries.size]
    val palette = if (look == Look.GRADIENT) VIVID[(h / 7) % VIVID.size] else PALETTES[(h / 7) % PALETTES.size]
    return Skin(look, palette, ((h / 61) % 360).toFloat())
}


/** The card body for a skin, with a small label on top ("LIST · 5"). */
@Composable
private fun SkinCard(skin: Skin, label: String, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    val p = skin.palette
    val rad = Math.toRadians(skin.angle.toDouble())
    var m = Modifier.fillMaxWidth().clip(shape)
    m = when (skin.look) {
        Look.GRADIENT -> m.background(Brush.linearGradient(p, start = Offset.Zero,
            end = Offset((1000 * kotlin.math.cos(rad)).toFloat().let { if (it == 0f) 1f else kotlin.math.abs(it) }, 1000f)))
            .drawBehind {   // glassy sheen on top
                drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.22f), 0.5f to Color.Transparent))
            }
            .border(1.dp, Color.White.copy(alpha = 0.25f), shape)
        Look.GLOW -> m.background(R.surface).drawBehind {
            // A coloured light pooling in one corner, like the reference tiles.
            val corner = if (skin.angle < 180) Offset(size.width, 0f) else Offset(0f, size.height)
            drawCircle(Brush.radialGradient(listOf(p.first().copy(alpha = 0.45f), Color.Transparent), corner, size.width * 0.75f),
                size.width * 0.75f, corner)
            drawCircle(Brush.radialGradient(listOf(p.last().copy(alpha = 0.25f), Color.Transparent),
                Offset(size.width - corner.x, size.height - corner.y), size.width * 0.5f), size.width * 0.5f,
                Offset(size.width - corner.x, size.height - corner.y))
        }.border(1.dp, R.edge, shape)
        Look.DOTGRID -> m.background(R.surface).drawBehind {
            // A faint LED dot grid tinted with the palette (habits / dot-matrix boards).
            val step = 14.dp.toPx(); val r = 1.4.dp.toPx()
            var y = step / 2
            var row = 0
            while (y < size.height) {
                var x = step / 2
                while (x < size.width) {
                    val c = p[(row + (x / step).toInt()) % p.size]
                    drawCircle(c.copy(alpha = 0.16f), r, Offset(x, y))
                    x += step
                }
                y += step; row++
            }
        }.border(1.dp, R.edge, shape)
        Look.OUTLINE -> m.background(R.bg).border(1.5.dp, Brush.linearGradient(p), shape)
        Look.BAR -> m.background(R.surface).drawBehind {
            drawRect(Brush.verticalGradient(p), size = androidx.compose.ui.geometry.Size(5.dp.toPx(), size.height))
        }.border(1.dp, R.edge, shape)
        Look.BAND -> m.background(R.surface).drawBehind {
            drawRect(Brush.horizontalGradient(p), size = androidx.compose.ui.geometry.Size(size.width, 6.dp.toPx()))
            drawRect(Brush.verticalGradient(listOf(p.first().copy(alpha = 0.12f), Color.Transparent), startY = 0f, endY = size.height * 0.6f))
        }.border(1.dp, R.edge, shape)
    }
    Column(m.padding(start = if (skin.look == Look.BAR) 20.dp else 18.dp, end = 18.dp, top = if (skin.look == Look.BAND) 20.dp else 16.dp, bottom = 16.dp)) {
        EyebrowText(label, color = if (skin.onColor) Color.White.copy(alpha = 0.85f) else p.first())
        Spacer(Modifier.height(10.dp))
        content()
    }
}

// ---------------------------------------------------------------------- cards

@Composable
private fun ChecklistCard(items: List<String>, key: String, skin: Skin) {
    // Ticks are local for now; Phase 6 stores them in the task DB.
    val done = remember(key) { mutableStateMapOf<Int, Boolean>() }
    val total = items.size
    val ticked = done.values.count { it }
    SkinCard(skin, "Checklist · $ticked of $total") {
        GradientBar(if (total > 0) ticked.toFloat() / total else 0f,
            if (skin.onColor) listOf(Color.White, Color.White) else skin.palette, height = 4.dp,
            track = if (skin.onColor) Color.White.copy(alpha = 0.25f) else R.surface3, modifier = Modifier.padding(bottom = 4.dp))
        items.forEachIndexed { i, item ->
            val checked = done[i] == true
            Row(Modifier.fillMaxWidth().clickable { done[i] = !checked }, verticalAlignment = Alignment.CenterVertically) {
                RoundCheck(checked, if (skin.onColor) ramp(skin.palette.first(), i, items.size) else ramp(skin.base, i, items.size), skin.onColor)
                Text(inline(item), fontSize = 16.sp, lineHeight = 22.sp,
                    textDecoration = if (checked) TextDecoration.LineThrough else null,
                    color = if (checked) skin.subColor else skin.textColor)
            }
        }
    }
}

/** A round tick in its own colour: a ring, or a filled disc with a check (48 dp touch area). */
@Composable
private fun RoundCheck(checked: Boolean, color: Color, onColor: Boolean) {
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(24.dp).clip(CircleShape)
                .then(if (checked) Modifier.background(if (onColor) Color.White else color)
                    else Modifier.border(1.8.dp, if (onColor) Color.White.copy(alpha = 0.8f) else color, CircleShape)),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Icon(Ic.Check, null, tint = if (onColor) color else R.bg, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun BulletCard(items: List<String>, skin: Skin) = SkinCard(skin, "List · ${items.size}") {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.forEachIndexed { i, item ->
            Row(verticalAlignment = Alignment.Top) {
                // One colour family, dark → light down the list, each dot a small gradient.
                Box(Modifier.padding(top = 7.dp, end = 12.dp).size(10.dp).clip(CircleShape)
                    .background(if (skin.onColor) Brush.linearGradient(listOf(Color.White, Color.White.copy(alpha = 0.4f + 0.6f * (i + 1f) / items.size)))
                        else rampBrush(skin.base, i, items.size)))
                Text(inline(item), fontSize = 16.sp, lineHeight = 22.sp, color = skin.textColor)
            }
        }
    }
}

@Composable
private fun StepsCard(items: List<String>, skin: Skin) = SkinCard(skin, "Steps · ${items.size}") {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items.forEachIndexed { i, item ->
            Row(verticalAlignment = Alignment.Top) {
                val c = ramp(skin.palette.first(), i, items.size)
                Box(Modifier.size(28.dp).clip(CircleShape)
                    .background(if (skin.onColor) Brush.linearGradient(listOf(Color.White, Color.White))
                        else rampBrush(skin.base, i, items.size)),
                    contentAlignment = Alignment.Center) {
                    Text("${i + 1}", style = MaterialTheme.typography.labelLarge, color = if (skin.onColor) c else if (i.toFloat() / items.size < 0.5f) Color.White else R.bg,
                        fontWeight = FontWeight.Bold)
                }
                Text(inline(item), fontSize = 16.sp, lineHeight = 22.sp, color = skin.textColor,
                    modifier = Modifier.padding(start = 12.dp, top = 3.dp))
            }
        }
    }
}

/**
 * Mobile-first comparison: each table row becomes a small section ("Caffeine" → "Tea: … / Coffee: …"),
 * so nothing is cut off or misaligned on a narrow screen. Column headers name the things being compared.
 */
@Composable
private fun CompareCard(t: Block.Table, skin: Skin) = SkinCard(skin, "Compare · " + t.header.drop(1).joinToString(" vs ") { it.trim() }.take(40)) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        t.rows.forEachIndexed { r, row ->
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(inline(row.getOrElse(0) { "" }), style = MaterialTheme.typography.labelLarge,
                    color = if (skin.onColor) Color.White else androidx.compose.ui.graphics.lerp(ramp(skin.base, r, t.rows.size), Color.White, 0.2f))
                for (c in 1 until maxOf(row.size, t.header.size)) {
                    val value = row.getOrElse(c) { "" }
                    if (value.isBlank()) continue
                    Text(buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(inline(t.header.getOrElse(c) { "" })) }
                        append(": ")
                        append(inline(value))
                    }, style = MaterialTheme.typography.bodyMedium, color = skin.textColor)
                }
            }
            if (r < t.rows.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp)
                .background(if (skin.onColor) Color.White.copy(alpha = 0.2f) else R.edge))
        }
    }
}

@Composable
private fun CodeCard(code: String) = Box(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(R.surface2).border(1.dp, R.edge, RoundedCornerShape(16.dp)),
) {
    Text(code, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = R.text,
        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(12.dp))
}

/** Inline markdown: **bold**, *italic* / _italic_, `code`. Unclosed markers (mid-stream) are left as-is. */
fun inline(src: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < src.length) {
        when {
            src.startsWith("**", i) && src.indexOf("**", i + 2) > i + 2 -> {
                val end = src.indexOf("**", i + 2)
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(inline(src.substring(i + 2, end))) }
                i = end + 2
            }
            (src[i] == '*' || src[i] == '_') && i + 1 < src.length && src[i + 1] != ' ' &&
                src.indexOf(src[i], i + 1).let { it > i + 1 && (src[i] == '*' || it + 1 >= src.length || !src[it + 1].isLetterOrDigit()) } -> {
                val end = src.indexOf(src[i], i + 1)
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(src.substring(i + 1, end)) }
                i = end + 1
            }
            src[i] == '`' && src.indexOf('`', i + 1) > i + 1 -> {
                val end = src.indexOf('`', i + 1)
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(src.substring(i + 1, end)) }
                i = end + 1
            }
            else -> { append(src[i]); i++ }
        }
    }
}

@Composable
private fun ExpenseCard(jsonStr: String, skin: Skin) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val parsed = remember(jsonStr) {
        runCatching {
            val o = org.json.JSONObject(jsonStr)
            val merchant = o.optString("merchant", "Expense")
            val date = o.optString("date", "")
            val total = o.optDouble("total", 0.0)
            val currency = o.optString("currency", "INR")
            val tax = if (o.has("tax") && !o.isNull("tax")) o.optDouble("tax") else null
            val itemsArr = o.optJSONArray("items")
            val items = if (itemsArr != null) {
                (0 until itemsArr.length()).map { i ->
                    val itm = itemsArr.getJSONObject(i)
                    Triple(itm.optString("name", "Item"), itm.optInt("qty", 1), itm.optDouble("price", 0.0))
                }
            } else emptyList()
            ExpenseCardData(merchant, date, total, currency, items, tax)
        }.getOrNull()
    }

    if (parsed == null) {
        CodeCard("```expense\n$jsonStr\n```")
        return
    }

    val sym = when (parsed.currency.uppercase()) {
        "INR", "RS", "RUPEES" -> "₹"
        "USD", "$" -> "$"
        "EUR", "€" -> "€"
        "GBP", "£" -> "£"
        else -> parsed.currency
    }

    SkinCard(skin, "EXPENSE · ${parsed.merchant.uppercase()}") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                Column {
                    Text(parsed.merchant, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = skin.textColor)
                    if (parsed.date.isNotBlank()) {
                        Text(parsed.date, style = MaterialTheme.typography.bodySmall, color = skin.subColor)
                    }
                }
                Text("$sym ${"%.2f".format(parsed.total)}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, color = skin.textColor)
            }

            if (parsed.items.isNotEmpty()) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(if (skin.onColor) Color.White.copy(alpha = 0.2f) else R.edge))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    parsed.items.forEach { (name, qty, price) ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(if (qty > 1) "$name × $qty" else name, style = MaterialTheme.typography.bodyMedium, color = skin.textColor)
                            Text("$sym ${"%.2f".format(price)}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = skin.textColor)
                        }
                    }
                    if (parsed.tax != null && parsed.tax > 0.0) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Tax", style = MaterialTheme.typography.bodySmall, color = skin.subColor)
                            Text("$sym ${"%.2f".format(parsed.tax)}", style = MaterialTheme.typography.bodySmall, color = skin.subColor)
                        }
                    }
                }
            }

            Box(Modifier.fillMaxWidth().height(1.dp).background(if (skin.onColor) Color.White.copy(alpha = 0.2f) else R.edge))

            val summaryText = buildString {
                append("${parsed.merchant} · $sym ${"%.2f".format(parsed.total)}")
                if (parsed.date.isNotBlank()) append(" (${parsed.date})")
                if (parsed.items.isNotEmpty()) {
                    append("\n" + parsed.items.joinToString("\n") { (name, qty, price) ->
                        "- $name" + (if (qty > 1) " × $qty" else "") + ": $sym ${"%.2f".format(price)}"
                    })
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Copy", {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(summaryText))
                    android.widget.Toast.makeText(ctx, "Expense copied", android.widget.Toast.LENGTH_SHORT).show()
                })
                Chip("Share", {
                    val sendIntent = Intent().apply {
                        action = Intent.ACTION_SEND
                        putExtra(Intent.EXTRA_TEXT, summaryText)
                        type = "text/plain"
                    }
                    ctx.startActivity(Intent.createChooser(sendIntent, "Share Expense"))
                })
            }
        }
    }
}

private data class ExpenseCardData(
    val merchant: String,
    val date: String,
    val total: Double,
    val currency: String,
    val items: List<Triple<String, Int, Double>>,
    val tax: Double?,
)
