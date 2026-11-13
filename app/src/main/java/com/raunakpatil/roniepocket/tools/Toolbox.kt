package com.raunakpatil.roniepocket.tools

import android.content.Context

/** How a tool call is allowed to run. Decided in code from the tool's annotations, never by the model. */
enum class Gate {
    /** Read-only: just run it. */
    RUN,
    /** Local and reversible, asked for directly by the user: run it and offer Undo. */
    RUN_WITH_UNDO,
    /** Everything else (deletes, anything a remote server does that isn't read-only, anything after a tool result): ask first. */
    CONFIRM;

    companion object {
        /**
         * [afterToolResult]: the model has already seen a tool result in this turn, so this call might have been
         * suggested by tool output (prompt injection). Then only read-only tools run without asking.
         */
        fun of(spec: ToolSpec, afterToolResult: Boolean): Gate = when {
            spec.readOnly -> RUN
            spec.local && !spec.destructive && !afterToolResult -> RUN_WITH_UNDO
            else -> CONFIRM
        }
    }
}

/** All tools Ronie can see: built-ins (always) plus enabled connectors (unless offline mode is on). */
class Toolbox(private val ctx: Context) {
    val log = ActivityLog(ctx)
    val builtins = Builtins(ctx) { host, what, result -> log.add(host, what, result) }
    val connectors = ConnectorStore(ctx)
    private val prefs = ctx.getSharedPreferences("ronie_tools", 0)
    private val clients = HashMap<String, McpClient>()

    var offline: Boolean
        get() = prefs.getBoolean("offline", false)
        set(v) { prefs.edit().putBoolean("offline", v).apply() }

    /** Built-in declarations: always the same, so they sit in the cached part of the prompt. */
    val builtinBlock: String by lazy { builtins.specs.joinToString("") { it.declaration() } }

    fun remoteSpecs(): List<ToolSpec> = if (offline) emptyList() else connectors.all().filter { it.enabled }.flatMap { it.specs() }

    /** Connector tools worth showing the model for this message (all of them when there are only a few). */
    fun route(message: String, recent: String = ""): List<ToolSpec> = Router.pick(remoteSpecs(), message, recent,
        connectors.all().associate { it.id to it.name })

    fun find(name: String): ToolSpec? = builtins.specs.firstOrNull { it.name == name } ?: remoteSpecs().firstOrNull { it.name == name }

    /** Name → required args, for the lenient parser (only tools the model was shown). */
    fun known(shown: List<ToolSpec>): Map<String, List<String>> = (builtins.specs + shown).associate { it.name to it.required }

    suspend fun run(
        spec: ToolSpec,
        args: Map<String, Any?>,
        onProgress: ((step: Int, total: Int, preview: android.graphics.Bitmap?) -> Unit)? = null,
        onStageProgress: ((progress: Float, stageText: String, preview: android.graphics.Bitmap?) -> Unit)? = null
    ): Outcome {
        if (spec.name == "web_search" && offline) return Outcome.Failed("Offline mode is on, so web search is blocked.")
        if (spec.name == "find_images" && offline) return Outcome.Failed("Offline mode is on, so no pictures from the web.")
        val cid = spec.connectorId ?: return builtins.run(spec.name, args, onProgress, onStageProgress)
        if (offline) return Outcome.Failed("Offline mode is on, so connectors are blocked.")
        val c = connectors.all().firstOrNull { it.id == cid } ?: return Outcome.Failed("That connector was removed.")
        val client = clients.getOrPut(cid) { McpClient(c.url, connectors.token(cid), log) }
        return try {
            val (ok, text) = client.call(spec.remoteName, args)
            if (ok) Outcome.Done(text, "${c.name}: ${spec.remoteName}") else Outcome.Failed(text.ifBlank { "The tool reported an error." })
        } catch (e: McpClient.McpError) {
            clients.remove(cid)   // reconnect next time
            Outcome.Failed("${c.name} didn't answer: ${e.message}")
        }
    }

    /** Connects, lists tools and stores them. Returns a status line for the UI. */
    suspend fun test(c: Connector): Connector {
        clients.remove(c.id)
        val client = McpClient(c.url, connectors.token(c.id), log)
        val updated = try {
            val tools = client.listTools()
            c.copy(tools = tools, status = "Connected · ${tools.size} tool${if (tools.size == 1) "" else "s"}")
        } catch (e: Exception) {
            c.copy(status = "Couldn't connect: ${e.message}")
        }
        connectors.upsert(updated)
        if (updated.tools.isNotEmpty()) clients[c.id] = client
        return updated
    }
}

/**
 * Picks the connector tools to show the model for a message. Built-ins are always shown; with more than
 * [MAX] connector tools, the ones whose name/description share the most words with the message win, and
 * naming the connector ("in Notion…") pulls its tools in. (Phase 6 swaps the word overlap for embeddings.)
 */
object Router {
    const val MAX = 5
    private val STOP = setOf("the", "and", "for", "you", "your", "with", "this", "that", "from", "what", "can", "please", "about",
        "are", "was", "have", "has", "use", "get", "into", "out", "all", "any", "how", "who", "when", "where", "which", "list", "tool")

    private fun words(s: String) = s.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 2 && it !in STOP }.toSet()

    fun pick(tools: List<ToolSpec>, message: String, recent: String, connectorNames: Map<String, String>): List<ToolSpec> {
        if (tools.size <= MAX) return tools
        val m = words(message); val r = words(recent)
        return tools.map { t ->
            val tw = words(t.name.replace('_', ' ') + " " + t.description)
            val named = connectorNames[t.connectorId]?.let { n -> words(n).any { it in m } } == true
            t to (tw.count { it in m } * 2 + tw.count { it in r } + if (named) 3 else 0)
        }.filter { it.second > 0 }.sortedByDescending { it.second }.take(MAX).map { it.first }
    }
}
