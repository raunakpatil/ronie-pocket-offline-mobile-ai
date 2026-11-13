package com.raunakpatil.roniepocket

import com.raunakpatil.roniepocket.engine.ChatMessage
import com.raunakpatil.roniepocket.tools.ToolCard
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Saved chats for the sidebar: one JSON file per chat (what's on screen + what Ronie's model saw) and a
 * small index. Plain files in app-private storage for now; Phase 6 moves them into the encrypted database.
 */
class ChatStore(dir: File) {
    data class Summary(val id: Long, val title: String, val updated: Long)
    data class Chat(val messages: List<UiMessage>, val history: List<ChatMessage>)

    private val root = File(dir, "chats").apply { mkdirs() }
    private val index = File(root, "index.json")

    @Synchronized fun list(): List<Summary> = runCatching {
        val a = JSONArray(index.readText())
        (0 until a.length()).map { a.getJSONObject(it) }.map { Summary(it.getLong("id"), it.getString("title"), it.getLong("updated")) }
    }.getOrDefault(emptyList()).sortedByDescending { it.updated }

    @Synchronized private fun saveIndex(list: List<Summary>) =
        index.writeText(JSONArray(list.map { JSONObject().put("id", it.id).put("title", it.title).put("updated", it.updated) }).toString())

    @Synchronized fun save(id: Long, messages: List<UiMessage>, history: List<ChatMessage>) {
        if (messages.isEmpty()) return
        // Keep the title Ronie gave the chat; until then, the first thing the user said.
        val title = list().firstOrNull { it.id == id }?.title
            ?: messages.firstOrNull { it.fromUser }?.text?.lineSequence()?.firstOrNull()?.take(48) ?: "Chat"
        val o = JSONObject()
            .put("messages", JSONArray(messages.map { m ->
                JSONObject().put("id", m.id).put("user", m.fromUser).put("text", m.text).put("checklist", m.checklist)
                    .put("images", JSONArray(m.images))
                    .put("tools", JSONArray(m.tools.map { c ->
                        JSONObject().put("id", c.id).put("tool", c.tool).put("text", c.text).put("state", c.state.name)
                            .put("sendsTo", c.sendsTo ?: "").put("endsAt", c.timerEndsAt ?: 0L).put("total", c.timerTotalMs)
                            .put("image", c.image?.absolutePath ?: "")
                            .put("pictures", JSONArray(c.pictures.map { p -> JSONObject().put("path", p.path).put("title", p.title).put("url", p.pageUrl) }))
                    }))
            }))
            .put("history", JSONArray(history.map {
                JSONObject().put("role", it.role.name).put("content", it.content).put("images", JSONArray(it.images))
            }))
        File(root, "$id.json").writeText(o.toString())
        saveIndex(list().filter { it.id != id } + Summary(id, title, System.currentTimeMillis()))
    }

    /** Ronie's summary title for a chat (updated as the conversation goes on). */
    @Synchronized fun rename(id: Long, title: String) {
        saveIndex(list().map { if (it.id == id) it.copy(title = title) else it })
    }

    @Synchronized fun load(id: Long): Chat? = runCatching {
        val o = JSONObject(File(root, "$id.json").readText())
        val ms = o.getJSONArray("messages")
        val messages = (0 until ms.length()).map { ms.getJSONObject(it) }.map { m ->
            val ts = m.optJSONArray("tools") ?: JSONArray()
            val ims = m.optJSONArray("images")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            UiMessage(m.getLong("id"), m.getBoolean("user"), m.getString("text"), images = ims, checklist = m.optBoolean("checklist"),
                tools = (0 until ts.length()).map { ts.getJSONObject(it) }.map { c ->
                    // Anything mid-flight when the chat was saved can't resume.
                    val st = runCatching { ToolCard.State.valueOf(c.getString("state")) }.getOrDefault(ToolCard.State.DONE)
                    ToolCard(c.getLong("id"), c.getString("tool"), c.getString("text"),
                        when (st) { ToolCard.State.ASK -> ToolCard.State.DECLINED; ToolCard.State.RUNNING -> ToolCard.State.FAILED; else -> st },
                        sendsTo = c.optString("sendsTo").ifEmpty { null },
                        timerEndsAt = c.optLong("endsAt").takeIf { it > System.currentTimeMillis() },
                        timerTotalMs = c.optLong("total"),
                        image = c.optString("image").takeIf { it.isNotBlank() }?.let { File(it) },
                        pictures = c.optJSONArray("pictures")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map { p ->
                            com.raunakpatil.roniepocket.web.WebImage(p.getString("path"), p.optString("title"), p.optString("url")) } } ?: emptyList())
                })
        }
        val hs = o.getJSONArray("history")
        val history = (0 until hs.length()).map { hs.getJSONObject(it) }.map {
            val ims = it.optJSONArray("images")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            ChatMessage(ChatMessage.Role.valueOf(it.getString("role")), it.getString("content"), images = ims)
        }
        Chat(messages, history)
    }.getOrNull()

    @Synchronized fun delete(id: Long) {
        val chatFile = File(root, "$id.json")
        if (chatFile.exists()) {
            val chat = load(id)
            val thisChatImages = (chat?.messages?.flatMap { it.images } ?: emptyList()) +
                (chat?.history?.flatMap { it.images } ?: emptyList()) +
                (chat?.messages?.flatMap { it.tools }?.mapNotNull { it.image?.absolutePath } ?: emptyList())

            chatFile.delete()
            saveIndex(list().filter { it.id != id })

            // Only delete an image file if no other chat references it
            if (thisChatImages.isNotEmpty()) {
                val remainingChats = list().mapNotNull { load(it.id) }
                val referencedElsewhere = remainingChats.flatMap { c ->
                    c.messages.flatMap { it.images } + c.history.flatMap { it.images } +
                        c.messages.flatMap { it.tools }.mapNotNull { it.image?.absolutePath }
                }.toSet()

                thisChatImages.toSet().forEach { path ->
                    if (path !in referencedElsewhere) {
                        runCatching { File(path).delete() }
                    }
                }
            }
        } else {
            saveIndex(list().filter { it.id != id })
        }
    }
}
