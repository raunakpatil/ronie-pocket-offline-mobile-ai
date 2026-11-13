package com.raunakpatil.roniepocket

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.raunakpatil.roniepocket.models.DeviceProfile
import com.raunakpatil.roniepocket.models.Fit
import com.raunakpatil.roniepocket.models.ImageGenTier
import com.raunakpatil.roniepocket.models.ModelStore
import com.raunakpatil.roniepocket.models.Tier
import com.raunakpatil.roniepocket.models.TierPolicy
import com.raunakpatil.roniepocket.ui.Chip
import com.raunakpatil.roniepocket.ui.EyebrowText
import com.raunakpatil.roniepocket.ui.PillButton
import com.raunakpatil.roniepocket.ui.R

/**
 * Settings → Models. Shows the chat brain and the drawing model in use, and lets the user swap either one
 * after setup: pick another, Ronie downloads it, and the old files are deleted (the chat brain's always,
 * since only one fits the "one brain per phone" rule; the drawing model's if the user wants the space back).
 */
@Composable
fun ModelsSection(s: ChatState, vm: ChatViewModel) {
    val ctx = LocalContext.current
    val device = remember { DeviceProfile.read(ctx) }
    val choice = remember(device) { TierPolicy.choose(device) }
    var confirmBrain by remember { mutableStateOf<Tier?>(null) }
    var confirmDraw by remember { mutableStateOf<ImageGenTier?>(null) }

    // ------------------------------------------------------------ chat brain
    EyebrowText("Chat brain", color = R.orange)
    Spacer(Modifier.height(4.dp))
    Text(s.brainLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = R.text)
    Small(s.brainDetail)
    if (s.lastStats.isNotEmpty()) Small("Last reply: ${s.lastStats}")
    Spacer(Modifier.height(10.dp))
    Small("Switch brain (your chats are kept; the old brain is deleted once the new one is ready):")
    Spacer(Modifier.height(6.dp))
    val options = choice.options.ifEmpty { Tier.entries.map { com.raunakpatil.roniepocket.models.TierOption(it, Fit.OK, it.tagline) } }
    options.forEach { o ->
        val current = o.tier == s.brainTier
        OptionRow(
            title = "${o.tier.label} · ${o.tier.model.sizeLabel}",
            badge = when { current -> "In use"; o.fit == Fit.RECOMMENDED -> "Recommended for this phone"; o.fit == Fit.SLOW -> "May be slow here"
                o.fit == Fit.NO_SPACE -> "Not enough storage"; else -> null },
            lines = listOf(o.tier.tagline, o.note).distinct().filter { it.isNotBlank() },
            highlighted = current,
        ) {
            if (!current) PillButton("Switch", { confirmBrain = o.tier }, primary = false, enabled = o.selectable && s.ready && !s.generating,
                minHeight = 36.dp, padding = PaddingValues(horizontal = 14.dp, vertical = 4.dp), fontSize = 13.sp)
        }
    }

    // ------------------------------------------------------------ drawing model
    Spacer(Modifier.height(18.dp))
    EyebrowText("Drawing model", color = R.orange)
    Spacer(Modifier.height(4.dp))
    s.drawingDownload?.let { Chip(it, {}); Spacer(Modifier.height(6.dp)) }
    listOf(ImageGenTier.SD_TURBO, ImageGenTier.SANA_SPRINT).forEach { t ->
        // Re-read when a download finishes or a model is removed.
        val installed = remember(t, s.drawingInstalled, s.drawTier, s.drawingDownload) { ModelStore.isDrawingInstalled(ctx, t) }
        val active = s.drawTier == t
        OptionRow(
            title = t.title,
            badge = when { active && installed -> "In use"; installed -> "On this phone"; !t.isAvailableNow -> "Not downloadable yet"; else -> null },
            lines = listOf("${t.resolution} · ${t.stepsLabel} · ${t.downloadSizeLabel}", "Licence: ${t.license}") +
                if (!t.isAvailableNow && !installed) listOf("The phone-ready files for this model aren't hosted anywhere yet.") else emptyList(),
            highlighted = active && installed,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val small = PaddingValues(horizontal = 14.dp, vertical = 4.dp)
                when {
                    s.drawingDownload != null -> {}
                    installed && !active -> PillButton("Use", { confirmDraw = t }, primary = false, minHeight = 36.dp, padding = small, fontSize = 13.sp)
                    !installed && t.isAvailableNow -> PillButton("Download", { confirmDraw = t }, minHeight = 36.dp, padding = small, fontSize = 13.sp)
                }
                if (installed && s.drawingDownload == null) PillButton("Delete", { vm.removeDrawing(t) }, primary = false, minHeight = 36.dp, padding = small, fontSize = 13.sp)
            }
        }
    }
    Small("Drawing runs fully on this phone. One model is active at a time.")

    // ------------------------------------------------------------ confirmations
    confirmBrain?.let { t ->
        AlertDialog(
            onDismissRequest = { confirmBrain = null },
            containerColor = R.surface, shape = RoundedCornerShape(24.dp),
            title = { Text("Switch to the ${t.label} brain?", color = R.text) },
            text = { Text("Ronie downloads ${t.model.sizeLabel} over Wi-Fi, then deletes the current brain. Your chats and what Ronie remembers stay. " +
                "He can't chat until the new brain has arrived.", color = R.muted) },
            confirmButton = { PillButton("Switch", { confirmBrain = null; vm.switchBrain(t) }) },
            dismissButton = { PillButton("Cancel", { confirmBrain = null }, primary = false) },
        )
    }
    confirmDraw?.let { t ->
        val other = listOf(ImageGenTier.SD_TURBO, ImageGenTier.SANA_SPRINT).firstOrNull { it != t && ModelStore.isDrawingInstalled(ctx, it) }
        val need = !ModelStore.isDrawingInstalled(ctx, t)
        AlertDialog(
            onDismissRequest = { confirmDraw = null },
            containerColor = R.surface, shape = RoundedCornerShape(24.dp),
            title = { Text("Draw with ${t.title}?", color = R.text) },
            text = { Text((if (need) "Ronie downloads ${t.downloadSizeLabel} over Wi-Fi. " else "") +
                (if (other != null) "You can keep ${other.title} on the phone as well, or delete it to free ${other.downloadSizeLabel}." else ""), color = R.muted) },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (other != null) PillButton("Switch and delete ${other.title}", { confirmDraw = null; vm.switchDrawing(t, deleteOld = true) })
                    PillButton(if (other != null) "Switch, keep both" else if (need) "Download" else "Use it", { confirmDraw = null; vm.switchDrawing(t, deleteOld = false) },
                        primary = other == null)
                }
            },
            dismissButton = { PillButton("Cancel", { confirmDraw = null }, primary = false) },
        )
    }
}

@Composable
private fun Small(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = R.muted)

@Composable
private fun OptionRow(title: String, badge: String?, lines: List<String>, highlighted: Boolean, trailing: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(12.dp))
            .background(if (highlighted) R.surface3 else R.surface2).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = R.text)
                badge?.let { Spacer(Modifier.width(8.dp)); Text(it, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = if (highlighted) R.cyan else R.orange) }
            }
            lines.forEach { Text(it, fontSize = 11.sp, color = R.muted) }
        }
        Spacer(Modifier.width(8.dp))
        trailing()
    }
}
