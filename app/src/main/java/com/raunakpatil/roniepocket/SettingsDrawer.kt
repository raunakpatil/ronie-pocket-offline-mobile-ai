package com.raunakpatil.roniepocket

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.raunakpatil.roniepocket.models.DeviceProfile
import com.raunakpatil.roniepocket.models.ImageGenTier
import com.raunakpatil.roniepocket.models.TierPolicy
import com.raunakpatil.roniepocket.ui.EyebrowText
import com.raunakpatil.roniepocket.ui.R
import com.raunakpatil.roniepocket.ui.SwitchRow
import com.raunakpatil.roniepocket.ui.Tile

/**
 * Settings, as its own page (opened from the ⚙ at the bottom of the sidebar, like ChatGPT / Claude).
 * Sections: You, Ronie (tone, X-ray), Voice, Tools, Memory, Models, Privacy, About — each on its own tile.
 */
@Composable
fun SettingsPage(s: ChatState, vm: ChatViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val devProfile = remember { DeviceProfile.read(ctx) }

    Column(Modifier.fillMaxSize().background(R.bg).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            com.raunakpatil.roniepocket.ui.RoundButton(com.raunakpatil.roniepocket.ui.Ic.Back, "Back", onBack)
            Spacer(Modifier.width(14.dp))
            Text("Settings", fontSize = 28.sp, fontWeight = FontWeight.Light, color = R.text)
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Section("You") {
                var name by remember(s.userName) { mutableStateOf(s.userName ?: "") }
                Text("Your name", style = MaterialTheme.typography.titleSmall, color = R.text)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.TextField(
                        value = name, onValueChange = { name = it.take(40) }, singleLine = true,
                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(24.dp),
                        placeholder = { Text("What Ronie calls you", color = R.muted) },
                        colors = androidx.compose.material3.TextFieldDefaults.colors(
                            focusedContainerColor = R.surface2, unfocusedContainerColor = R.surface2,
                            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            cursorColor = R.orange, focusedTextColor = R.text, unfocusedTextColor = R.text),
                    )
                    if (name.trim() != (s.userName ?: "")) {
                        Spacer(Modifier.width(8.dp))
                        com.raunakpatil.roniepocket.ui.PillButton("Save", { vm.setName(name) })
                    }
                }
            }

            Section("Ronie") {
                val tone = s.tone
                if (tone != null) {
                    var v by remember(tone) { mutableFloatStateOf(tone) }
                    Row {
                        Text("Tone", style = MaterialTheme.typography.titleSmall, color = R.text, modifier = Modifier.weight(1f))
                        Text(if (v == 0f) "Normal" else "%+.0f%%".format(v * 100), style = MaterialTheme.typography.titleSmall, color = R.orange)
                    }
                    Slider(value = v, onValueChange = { v = it }, onValueChangeFinished = { vm.setTone(v) },
                        valueRange = -1f..1f, steps = 7, colors = sliderColors())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Small("Squeaky clean"); Small("Sweary")
                    }
                    Hint("Changes how Ronie's brain is wired while he writes, from the next reply.")
                } else Hint("Tone isn't available for this brain yet.")
                if (s.xray != null) {
                    Spacer(Modifier.height(10.dp))
                    SwitchRow("X-ray (Labs)", s.xray == true, enabled = s.ready && !s.generating) { vm.toggleXray() }
                    Hint("Colours each sentence: green = matches what you gave Ronie · yellow = unsure · red = looks made up. ${s.xrayNote}")
                }
            }

            Section("Voice") {
                SwitchRow("Reply aloud when I use the mic", s.speakOnVoice) { vm.setSpeakOnVoice(it) }
                Hint("Talk to Ronie with 🎤 and he answers out loud; type and he answers in text.")
                Spacer(Modifier.height(6.dp))
                SwitchRow("Always reply aloud", s.speak) { vm.setSpeak(it) }
                Spacer(Modifier.height(6.dp))
                var speed by remember(s.voiceSpeed) { mutableFloatStateOf(s.voiceSpeed) }
                Row {
                    Text("Speed", style = MaterialTheme.typography.titleSmall, color = R.text, modifier = Modifier.weight(1f))
                    Text("%.1f×".format(speed), style = MaterialTheme.typography.titleSmall, color = R.orange)
                }
                Slider(value = speed, onValueChange = { speed = it }, onValueChangeFinished = { vm.setVoiceSpeed(speed) },
                    valueRange = 0.8f..1.3f, steps = 4, colors = sliderColors())
                Hint(s.voiceStatus)
            }

            Section("Tools & connectors") {
                com.raunakpatil.roniepocket.tools.ToolsSection(s.toolsOn, s.offline, s.connectors, vm)
            }

            if (s.toolsOn) Section("What Ronie remembers") {
                com.raunakpatil.roniepocket.tools.MemorySection(s.facts, vm)
            }

            Section("Models") { ModelsSection(s, vm) }

            Section("Privacy") {
                Hint("Everything you say is processed on this phone. The internet is used to download Ronie's brain and drawing weights, and, " +
                    "only if you add them, by connectors, each labelled with where it sends data.")
                Spacer(Modifier.height(10.dp))
                com.raunakpatil.roniepocket.tools.ActivityButton(s.activity, vm)
            }

            Section("About") {
                Hint("Ronie Pocket · ${BuildConfig.VERSION_NAME} · Gemma 4 / Qwen3.5 via llama.cpp · voice: Paradee (Apache-2.0), " +
                    "pronunciations from misaki (Apache-2.0).")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Tile(Modifier.fillMaxWidth()) {
        EyebrowText(title, color = R.orange)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun sliderColors() = SliderDefaults.colors(
    thumbColor = R.text, activeTrackColor = R.orange, inactiveTrackColor = R.surface3,
    activeTickColor = R.bg.copy(alpha = 0.4f), inactiveTickColor = R.faint,
)

@Composable private fun Small(t: String) = Text(t, style = MaterialTheme.typography.labelSmall, color = R.muted)

@Composable private fun Hint(t: String) = Text(t, style = MaterialTheme.typography.bodySmall, color = R.muted)
