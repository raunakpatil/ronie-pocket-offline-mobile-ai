package com.raunakpatil.roniepocket.tools

/**
 * Gemma 4's native tool syntax (Kotlin port of eval/tools_v2/gemma_tools.py, which is checked
 * byte-for-byte against the GGUF chat template). Values are plain Kotlin: String, Number, Boolean,
 * null, Map<String, Any?>, List<Any?>.
 *
 *   declaration  <|tool>declaration:name{description:<|"|>…<|"|>,parameters:{…}}<tool|>
 *   call         <|tool_call>call:name{key:<|"|>value<|"|>}<tool_call|>
 *   response     <|tool_response>response:name{value:<|"|>…<|"|>}<tool_response|>
 */
object ToolSyntax {
    const val Q = "<|\"|>"

    // ------------------------------------------------------------------ rendering

    /** format_argument(escape_keys = false). */
    fun arg(v: Any?): String = when (v) {
        null -> "null"
        is Boolean -> if (v) "true" else "false"
        is String -> Q + v + Q
        is Map<*, *> -> v.keys.map { it.toString() }.sorted().joinToString(",", "{", "}") { k -> "$k:${arg(v[k])}" }
        is List<*> -> v.joinToString(",", "[", "]") { arg(it) }
        is Double -> if (v == Math.floor(v) && !v.isInfinite() && kotlin.math.abs(v) < 1e15) "${v.toLong()}.0" else v.toString()
        is Float -> arg(v.toDouble())
        else -> v.toString()
    }

    @Suppress("UNCHECKED_CAST")
    private fun props(p: Map<String, Any?>): String = p.keys.sorted().joinToString(",") { key ->
        val d = p[key] as? Map<String, Any?> ?: emptyMap()
        val parts = mutableListOf<String>()
        (d["description"] as? String)?.takeIf { it.isNotEmpty() }?.let { parts += "description:$Q$it$Q" }
        val type = (d["type"] as? String ?: "string").uppercase()
        val enum = d["enum"] as? List<*>
        if (type == "STRING" && !enum.isNullOrEmpty()) parts += "enum:" + enum.joinToString(",", "[", "]") { Q + it + Q }
        parts += "type:$Q$type$Q"
        "$key:{" + parts.joinToString(",") + "}"
    }

    /** One `<|tool>…<tool|>` block for a tool with a JSON-schema-like [schema] (as Kotlin maps). */
    @Suppress("UNCHECKED_CAST")
    fun declaration(name: String, description: String, schema: Map<String, Any?>?): String = buildString {
        append("<|tool>declaration:").append(name).append("{description:").append(Q).append(description).append(Q)
        if (!schema.isNullOrEmpty()) {
            append(",parameters:{")
            (schema["properties"] as? Map<String, Any?>)?.takeIf { it.isNotEmpty() }?.let { append("properties:{").append(props(it)).append("},") }
            (schema["required"] as? List<*>)?.takeIf { it.isNotEmpty() }?.let { r -> append("required:[").append(r.joinToString(",") { Q + it + Q }).append("],") }
            (schema["type"] as? String)?.let { append("type:").append(Q).append(it.uppercase()).append(Q).append("}") }
        }
        append("}<tool|>")
    }

    fun call(name: String, args: Map<String, Any?>): String =
        "<|tool_call>call:$name{" + args.keys.sorted().joinToString(",") { "$it:${arg(args[it])}" } + "}<tool_call|>"

    /** A tool result fed back to the model: a map renders as fields, anything else as `value:`. */
    fun response(name: String, value: Any?): String {
        val inner = if (value is Map<*, *>) value.keys.map { it.toString() }.sorted().joinToString(",") { "$it:${arg(value[it])}" }
        else "value:" + arg(value)
        return "<|tool_response>response:$name{$inner}<tool_response|>"
    }

    // ------------------------------------------------------------------ parsing

    class ParseError(msg: String) : Exception(msg)

    /** A parsed call; [args] is null when the arguments couldn't be read ([error] says why). */
    data class Call(val name: String, val args: Map<String, Any?>?, val error: String? = null, val repaired: Boolean = false)

    private val CALL = Regex("""<\|tool_call>call:([A-Za-z0-9_\-.]+)\{(.*?)\}<tool_call\|>""", RegexOption.DOT_MATCHES_ALL)
    private val BARE = Regex("""^\s*(?:call:)?([A-Za-z0-9_]+)\s*(\{.*\}|\(.*\))?\s*(?:<tool_call\|>)?\s*(?:<turn\|>|<eos>|<\|tool_response>)?\s*$""",
        RegexOption.DOT_MATCHES_ALL)
    private val KWARG = Regex("""(^|,)\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*""")

    /**
     * Every call in [text]. Small models sometimes drop the `<|tool_call>call:` marker and write just
     * `list_tasks{…}` or `set_alarm(time="06:30")`; when the whole reply is such a call to a tool named in
     * [known] (name → required argument names), it is accepted and marked [Call.repaired].
     */
    fun parseCalls(text: String, known: Map<String, List<String>> = emptyMap()): List<Call> {
        val out = CALL.findAll(text).map { m ->
            try { Call(m.groupValues[1], parseArgs(m.groupValues[2])) } catch (e: ParseError) { Call(m.groupValues[1], null, e.message) }
        }.toList()
        if (out.isNotEmpty() || known.isEmpty()) return out
        val m = BARE.matchEntire(text) ?: return emptyList()
        val name = m.groupValues[1]
        val required = known[name] ?: return emptyList()
        val raw = m.groupValues[2].ifEmpty { "{}" }
        val body = KWARG.replace(raw.substring(1, raw.length - 1)) { it.groupValues[1] + it.groupValues[2] + ":" }
        return listOf(try {
            Call(name, parseArgs(body), repaired = true)
        } catch (e: ParseError) {
            // remember(Your birthday is March 3rd.) → the text is the tool's single required argument.
            if (required.size == 1 && body.isNotBlank()) Call(name, mapOf(required[0] to body.trim().trim('"').replace(Q, "")), repaired = true)
            else Call(name, null, e.message, repaired = true)
        })
    }

    /** True while [text] might still turn into a bare call (hide it from the chat and voice until we know). */
    fun maybeBareCall(text: String, names: Collection<String>): Boolean {
        val t = text.trimStart()
        if (t.isEmpty()) return true
        val raw = t.takeWhile { it.isLetterOrDigit() || it == '_' || it == ':' }
        val head = raw.removePrefix("call:")
        if (raw.length == t.length) return names.any { it.startsWith(head) || "call:$it".startsWith(t) }
        val next = t[raw.length]
        return head in names && (next == '{' || next == '(' || next.isWhitespace() || next == '<')
    }

    /** What the user sees: no tool calls, thinking blocks or control tokens. */
    fun visible(text: String): String = text
        .replace(CALL, "")
        .replace(Regex("""<\|tool_call>.*$""", RegexOption.DOT_MATCHES_ALL), "")      // a call still being written
        .replace(Regex("""<\|channel>.*?<channel\|>""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""<\|?[a-z_"]+\|?>"""), "")

    /** Parses Gemma's argument syntax: bare keys, <|"|>strings<|"|>, numbers, true/false/null, {}, []. */
    fun parseArgs(s: String): Map<String, Any?> = ArgParser(s).run { obj(null) }

    private class ArgParser(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun at(t: String) = s.startsWith(t, i)

        fun value(): Any? {
            ws()
            when {
                at(Q) -> {
                    val j = s.indexOf(Q, i + Q.length); if (j < 0) throw ParseError("unterminated string")
                    return s.substring(i + Q.length, j).also { i = j + Q.length }
                }
                at("\"") -> {
                    val sb = StringBuilder(); i++
                    while (i < s.length && s[i] != '"') {
                        if (s[i] == '\\' && i + 1 < s.length) { i++; sb.append(when (s[i]) { 'n' -> '\n'; 't' -> '\t'; else -> s[i] }) } else sb.append(s[i])
                        i++
                    }
                    if (i >= s.length) throw ParseError("unterminated string")
                    i++; return sb.toString()
                }
                at("{") -> { i++; return obj("}") }
                at("[") -> {
                    i++; val out = mutableListOf<Any?>(); ws()
                    if (at("]")) { i++; return out }
                    while (true) {
                        out += value(); ws()
                        if (at(",")) i++ else if (at("]")) { i++; return out } else throw ParseError("expected , or ] at $i")
                    }
                }
            }
            val m = Regex("""-?\d+(\.\d+)?([eE][-+]?\d+)?|true|false|null""").matchAt(s, i) ?: throw ParseError("bad value at $i: ${s.drop(i).take(20)}")
            i = m.range.last + 1
            return when (val t = m.value) {
                "true" -> true; "false" -> false; "null" -> null
                else -> if (t.any { it == '.' || it == 'e' || it == 'E' }) t.toDouble() else t.toLongOrNull() ?: t.toDouble()
            }
        }

        fun obj(end: String?): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>(); ws()
            if (end != null && at(end)) { i++; return out }
            if (end == null && i >= s.length) return out
            while (i < s.length) {
                ws()
                val m = Regex("""<\|"\|>([^<]*)<\|"\|>|"([^"]*)"|([A-Za-z_][A-Za-z0-9_\-]*)""").matchAt(s, i)
                    ?: throw ParseError("bad key at $i: ${s.drop(i).take(20)}")
                val key = m.groupValues.drop(1).first { it.isNotEmpty() }
                i = m.range.last + 1; ws()
                if (!at(":")) throw ParseError("expected : after $key")
                i++
                out[key] = value(); ws()
                if (at(",")) { i++; continue }
                if (end != null && at(end)) { i++; return out }
                if (end == null && i >= s.length) return out
                throw ParseError("expected , at $i: ${s.drop(i).take(20)}")
            }
            if (end != null) throw ParseError("unterminated object")
            return out
        }
    }
}
