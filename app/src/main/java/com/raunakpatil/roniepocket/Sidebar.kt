package com.raunakpatil.roniepocket

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.raunakpatil.roniepocket.ui.DotMatrix
import com.raunakpatil.roniepocket.ui.EyebrowText
import com.raunakpatil.roniepocket.ui.Ic
import com.raunakpatil.roniepocket.ui.R
import com.raunakpatil.roniepocket.ui.RoundButton
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The sidebar (swipe right or ☰), like ChatGPT / Claude: new chat at the top, saved chats in the middle,
 * and the user with a settings button at the bottom. Long-press a chat to delete it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Sidebar(s: ChatState, onNewChat: () -> Unit, onOpen: (Long) -> Unit, onDelete: (Long) -> Unit, onSettings: () -> Unit) {
    var confirmDelete by remember { mutableStateOf<ChatStore.Summary?>(null) }
    ModalDrawerSheet(
        drawerContainerColor = R.bg,
        drawerContentColor = R.text,
        drawerShape = RoundedCornerShape(topEnd = 32.dp, bottomEnd = 32.dp),
    ) {
        Column(Modifier.fillMaxHeight().padding(vertical = 16.dp)) {
            // Top: Ronie + new chat.
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                DotMatrix("RONIE", dot = 2.6.dp, on = R.text, off = Color.White.copy(alpha = 0.06f))
                Spacer(Modifier.weight(1f))
                RoundButton(Ic.Plus, "New chat", onNewChat, background = R.surface2)
            }
            Spacer(Modifier.height(20.dp))
            EyebrowText("Chats", modifier = Modifier.padding(horizontal = 24.dp))
            Spacer(Modifier.height(6.dp))

            // Middle: saved chats, newest first.
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)) {
                if (s.chats.isEmpty()) item {
                    Text("Your chats will show up here.", color = R.muted, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp))
                }
                items(s.chats, key = { it.id }) { c ->
                    val selected = c.id == s.chatId
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(16.dp))
                            .background(if (selected) R.surface2 else Color.Transparent)
                            .combinedClickable(onClick = { onOpen(c.id) }, onLongClick = { confirmDelete = c })
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(c.title, color = if (selected) R.text else R.text.copy(alpha = 0.85f), fontSize = 15.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        Text(whenLabel(c.updated), color = R.muted, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            // Bottom: you + settings.
            HorizontalDivider(color = R.edge, modifier = Modifier.padding(horizontal = 16.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                val name = s.userName?.takeIf { it.isNotBlank() }
                Box(Modifier.size(40.dp).clip(R.pill).background(Brush.linearGradient(R.sunset)), contentAlignment = Alignment.Center) {
                    Text((name?.first() ?: 'Y').uppercaseChar().toString(), color = R.bg, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(name ?: "You", color = R.text, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    Text("Private · Uncensored", color = R.muted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
                RoundButton(Ic.Gear, "Settings", onSettings, background = R.surface2)
            }
        }
    }
    confirmDelete?.let { c ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete this chat?") },
            text = { Text("\"${c.title}\" will be removed from this phone.") },
            confirmButton = { TextButton(onClick = { onDelete(c.id); confirmDelete = null }) { Text("Delete", color = R.red) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Keep") } },
        )
    }
}

/** "10:42", "Yesterday", "Mon", "3 Nov". */
private fun whenLabel(ms: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = ms }
    val days = ((now.timeInMillis - ms) / 86_400_000L).toInt()
    return when {
        now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR) && now.get(Calendar.YEAR) == then.get(Calendar.YEAR) ->
            SimpleDateFormat("HH:mm", Locale.ENGLISH).format(Date(ms))
        days <= 1 -> "Yesterday"
        days < 7 -> SimpleDateFormat("EEE", Locale.ENGLISH).format(Date(ms))
        else -> SimpleDateFormat("d MMM", Locale.ENGLISH).format(Date(ms))
    }
}
