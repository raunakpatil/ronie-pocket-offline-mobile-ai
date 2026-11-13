package com.raunakpatil.roniepocket.tools

import org.json.JSONArray
import org.json.JSONObject

/**
 * One tool Ronie can call, MCP-shaped. Built-ins come from assets/tools/builtin.json; connector tools come
 * from a remote MCP server's tools/list.
 *
 * [readOnly] / [destructive] are MCP's readOnlyHint / destructiveHint. [local] = stays on the phone and can be
 * undone (built-ins only). The confirm gate ([Gate]) decides from these, in code, never from the prompt.
 */
data class ToolSpec(
    val name: String,
    val description: String,
    val schema: Map<String, Any?>,
    val readOnly: Boolean,
    val destructive: Boolean,
    val local: Boolean,
    /** null for built-ins; the connector id for remote tools. */
    val connectorId: String? = null,
    /** Host data is sent to (remote tools), for the "Sends data to …" label and the activity log. */
    val host: String? = null,
    /** The tool's own name on its server (remote tools are renamed `<connector>_<tool>` for the model). */
    val remoteName: String = name,
) {
    val required: List<String> get() = (schema["required"] as? List<*>)?.map { it.toString() } ?: emptyList()
    fun declaration() = ToolSyntax.declaration(name, description, schema)

    companion object {
        /** From an MCP tool object: {name, description, inputSchema, annotations}. */
        fun fromMcp(o: JSONObject, rename: String = o.getString("name"), connectorId: String? = null, host: String? = null): ToolSpec {
            val ann = o.optJSONObject("annotations") ?: JSONObject()
            val readOnly = ann.optBoolean("readOnlyHint", false)
            return ToolSpec(
                name = rename,
                description = o.optString("description").lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(500) ?: "",
                schema = compactSchema(Json.toKotlin(o.optJSONObject("inputSchema") ?: JSONObject()) as Map<String, Any?>),
                readOnly = readOnly,
                // MCP's default for destructiveHint is true when the tool isn't read-only.
                destructive = !readOnly && ann.optBoolean("destructiveHint", true),
                local = connectorId == null && ann.optBoolean("local", false),
                connectorId = connectorId, host = host ?: ann.optString("sendsTo").ifEmpty { null }, remoteName = o.getString("name"),
            )
        }

        /** Keeps what the model needs (type, properties with type/description/enum, required); one-line descriptions. */
        @Suppress("UNCHECKED_CAST")
        fun compactSchema(s: Map<String, Any?>): Map<String, Any?> {
            val props = (s["properties"] as? Map<String, Any?>)?.mapValues { (_, v) ->
                val p = v as? Map<String, Any?> ?: emptyMap()
                buildMap {
                    put("type", (p["type"] as? String) ?: (p["type"] as? List<*>)?.firstOrNull { it != "null" }?.toString() ?: "string")
                    (p["description"] as? String)?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()?.take(160)?.let { put("description", it) }
                    (p["enum"] as? List<*>)?.let { put("enum", it.map { e -> e.toString() }) }
                }
            }
            return buildMap {
                put("type", "object")
                if (!props.isNullOrEmpty()) put("properties", props)
                (s["required"] as? List<*>)?.takeIf { it.isNotEmpty() }?.let { put("required", it.map { r -> r.toString() }) }
            }
        }
    }
}

/** org.json ⇄ plain Kotlin values. */
object Json {
    fun toKotlin(v: Any?): Any? = when (v) {
        null, JSONObject.NULL -> null
        is JSONObject -> v.keys().asSequence().associateWith { toKotlin(v.opt(it)) }
        is JSONArray -> (0 until v.length()).map { toKotlin(v.opt(it)) }
        is Int -> v.toLong()
        else -> v
    }

    fun fromKotlin(v: Any?): Any? = when (v) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject().apply { v.forEach { (k, x) -> put(k.toString(), fromKotlin(x)) } }
        is List<*> -> JSONArray().apply { v.forEach { put(fromKotlin(it)) } }
        else -> v
    }
}

/**
 * Checks a call's arguments against the tool's schema and fixes what's safe to fix ("10" → 10 for a
 * number, 10.0 → 10 for an integer, "true" → true). Errors go back to the model for one repair retry.
 */
object ArgCheck {
    data class Result(val args: Map<String, Any?>, val errors: List<String>)

    @Suppress("UNCHECKED_CAST")
    fun check(spec: ToolSpec, args: Map<String, Any?>): Result {
        val props = spec.schema["properties"] as? Map<String, Any?> ?: emptyMap()
        val errors = mutableListOf<String>()
        val out = LinkedHashMap<String, Any?>()
        for ((k, v) in args) {
            val p = props[k] as? Map<String, Any?>
            if (p == null) { if (props.isNotEmpty()) continue else { out[k] = v; continue } }   // unknown args are dropped
            if (v == null) continue
            val type = (p["type"] as? String ?: "string").lowercase()
            val fixed: Any? = when (type) {
                "string" -> when (v) { is String -> v; is Number, is Boolean -> v.toString(); else -> null }
                "number" -> when (v) { is Number -> v.toDouble(); is String -> v.trim().toDoubleOrNull(); else -> null }
                "integer" -> when (v) {
                    is Long, is Int -> (v as Number).toLong()
                    is Double -> if (v == Math.floor(v)) v.toLong() else null
                    is String -> v.trim().toDoubleOrNull()?.takeIf { it == Math.floor(it) }?.toLong()
                    else -> null
                }
                "boolean" -> when (v) { is Boolean -> v; "true" -> true; "false" -> false; else -> null }
                "array" -> v as? List<*> ?: listOf(v)
                "object" -> v as? Map<*, *>
                else -> v
            }
            if (fixed == null) { errors += "$k must be a $type"; continue }
            val enum = p["enum"] as? List<*>
            if (enum != null && fixed.toString() !in enum.map { it.toString() }) { errors += "$k must be one of ${enum.joinToString()}"; continue }
            out[k] = fixed
        }
        for (r in spec.required) if (out[r] == null || (out[r] as? String)?.isBlank() == true) errors += "missing $r"
        return Result(out, errors)
    }
}
