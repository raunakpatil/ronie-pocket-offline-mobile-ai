package com.raunakpatil.roniepocket.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Callbacks the drawer needs from the chat (kept small so the drawer doesn't depend on the ViewModel). */
interface ToolActions {
    fun setOffline(on: Boolean)
    fun addConnector(name: String, url: String, token: String)
    fun testConnector(id: String)
    fun setConnectorEnabled(id: String, on: Boolean)
    fun setConnectorTool(id: String, tool: String, on: Boolean)
    fun removeConnector(id: String)
    fun forgetFact(id: Long)
    fun clearActivity()
}

@Composable
fun ToolsSection(toolsOn: Boolean, offline: Boolean, connectors: List<Connector>, a: ToolActions) {
    if (!toolsOn) {
        Hint("Tools work with the Full brain for now; this brain gets them in a later update.")
        return
    }
    Hint("Built in, on this phone: to-do list, reminders, timers and alarms (Clock app), your calendar (read only), " +
        "and remembering things you tell Ronie. Anything that deletes, sends or posts asks you first.")
    Spacer(Modifier.height(10.dp))
    Toggle("Offline mode", offline) { a.setOffline(it) }
    Hint("Blocks every connector, so nothing leaves the phone.")

    Spacer(Modifier.height(12.dp))
    Text("Connectors (MCP)", style = MaterialTheme.typography.titleSmall, color = com.raunakpatil.roniepocket.ui.R.text)
    if (connectors.isEmpty()) Hint("Connect Ronie to other apps through their MCP server: Notion, GitHub, your own server…")
    connectors.forEach { c -> ConnectorRow(c, offline, a) }
    var adding by remember { mutableStateOf(false) }
    com.raunakpatil.roniepocket.ui.Chip("+ Add connector", { adding = true })
    if (adding) AddConnectorDialog(onDismiss = { adding = false }) { n, u, t -> adding = false; a.addConnector(n, u, t) }
}

@Composable
private fun ConnectorRow(c: Connector, offline: Boolean, a: ToolActions) {
    var open by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(c.name, style = MaterialTheme.typography.bodyLarge, color = com.raunakpatil.roniepocket.ui.R.text)
                Text("Sends data to ${c.host}" + if (offline) " · blocked (offline mode)" else "",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (c.status.isNotEmpty()) Text(c.status, style = MaterialTheme.typography.labelSmall,
                    color = if (c.status.startsWith("Couldn't")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = c.enabled, onCheckedChange = { a.setConnectorEnabled(c.id, it) }, colors = switchColors())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { a.testConnector(c.id) }) { Text("Test") }
            if (c.tools.isNotEmpty()) TextButton(onClick = { open = !open }) { Text(if (open) "Hide tools" else "Tools (${c.tools.size})") }
            TextButton(onClick = { confirmRemove = true }) { Text("Remove") }
        }
        if (open) c.tools.forEach { t ->
            val name = t.optString("name")
            val ro = t.optJSONObject("annotations")?.optBoolean("readOnlyHint", false) == true
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.bodySmall)
                    Text(if (ro) "reads only" else "can change things · asks you first", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = name !in c.disabledTools, onCheckedChange = { a.setConnectorTool(c.id, name, it) }, colors = switchColors())
            }
        }
    }
    if (confirmRemove) AlertDialog(
        onDismissRequest = { confirmRemove = false },
        title = { Text("Remove ${c.name}?") },
        text = { Text("Ronie forgets this connector and its saved token.") },
        confirmButton = { TextButton(onClick = { confirmRemove = false; a.removeConnector(c.id) }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Keep") } },
    )
}

@Composable
private fun AddConnectorDialog(onDismiss: () -> Unit, onAdd: (String, String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("https://") }
    var token by remember { mutableStateOf("") }
    // https only, except a server on the phone itself (localhost, e.g. a PC server through adb reverse).
    val ok = name.isNotBlank() && Regex("^(https://\\S+|http://(localhost|127\\.0\\.0\\.1)(:\\d+)?(/\\S*)?)$").matches(url.trim())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add connector") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name, e.g. Notion") }, singleLine = true)
                OutlinedTextField(url, { url = it }, label = { Text("MCP server URL (https)") }, placeholder = { Text("https://…/mcp") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                OutlinedTextField(token, { token = it }, label = { Text("Access token (optional)") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation())
                Hint("The token is encrypted with a key kept in Android's Keystore. Sign-in with OAuth comes later.")
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(name, url, token) }, enabled = ok) { Text("Connect") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun MemorySection(facts: List<com.raunakpatil.roniepocket.memory.MemoryStore.Fact>, a: ToolActions) {
    if (facts.isEmpty()) { Hint("Tell Ronie something to remember (\"my sister is called Priya\") and it shows up here."); return }
    facts.reversed().forEach { f ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(f.text, style = MaterialTheme.typography.bodyMedium, color = com.raunakpatil.roniepocket.ui.R.text, modifier = Modifier.weight(1f))
            TextButton(onClick = { a.forgetFact(f.id) }) { Text("Forget") }
        }
    }
}

@Composable
fun ActivityButton(entries: List<ActivityLog.Entry>, a: ToolActions) {
    var open by remember { mutableStateOf(false) }
    com.raunakpatil.roniepocket.ui.Chip("Activity log (${entries.size})", { open = true })
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text("What left this phone") },
        text = {
            if (entries.isEmpty()) Text("Nothing yet. Every request to a connector (later also search and mail) is listed here.")
            else LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(entries) { e ->
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Text("${SimpleDateFormat("d MMM HH:mm:ss", Locale.ENGLISH).format(Date(e.time))} · ${e.host}",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Text(e.what, style = MaterialTheme.typography.bodySmall)
                        Text(e.result, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } },
        dismissButton = { if (entries.isNotEmpty()) TextButton(onClick = { a.clearActivity() }) { Text("Clear") } },
    )
}

@Composable private fun Hint(t: String) =
    Text(t, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = com.raunakpatil.roniepocket.ui.R.text, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, colors = switchColors())
    }
}

@Composable private fun switchColors() = androidx.compose.material3.SwitchDefaults.colors(
    checkedThumbColor = com.raunakpatil.roniepocket.ui.R.bg, checkedTrackColor = com.raunakpatil.roniepocket.ui.R.lime, checkedBorderColor = com.raunakpatil.roniepocket.ui.R.lime,
    uncheckedThumbColor = com.raunakpatil.roniepocket.ui.R.muted, uncheckedTrackColor = com.raunakpatil.roniepocket.ui.R.surface3, uncheckedBorderColor = com.raunakpatil.roniepocket.ui.R.faint,
)
