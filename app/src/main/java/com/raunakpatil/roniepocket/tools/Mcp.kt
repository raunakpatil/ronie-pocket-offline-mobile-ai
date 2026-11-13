package com.raunakpatil.roniepocket.tools

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Minimal MCP client over Streamable HTTP (spec 2025-06-18): initialize → notifications/initialized →
 * tools/list → tools/call. Responses may come back as plain JSON or as a short SSE stream; both are read.
 * Auth v0: an optional bearer token (OAuth sign-in comes later). Every request is written to the [ActivityLog].
 */
class McpClient(private val url: String, private val token: String?, private val log: ActivityLog) {
    private var session: String? = null
    private var nextId = 1
    private var initialized = false
    val host: String = runCatching { URL(url).host }.getOrDefault(url)

    class McpError(msg: String) : Exception(msg)

    suspend fun listTools(): List<JSONObject> {
        ensureInit()
        val out = mutableListOf<JSONObject>()
        var cursor: String? = null
        do {
            val params = JSONObject().apply { cursor?.let { put("cursor", it) } }
            val r = request("tools/list", params, "list tools")
            val tools = r.optJSONArray("tools") ?: JSONArray()
            for (i in 0 until tools.length()) out += tools.getJSONObject(i)
            cursor = r.optString("nextCursor").ifEmpty { null }
        } while (cursor != null && out.size < 500)
        return out
    }

    /** Calls a tool; returns its text content (and structured content when given). Tool errors come back as text. */
    suspend fun call(name: String, args: Map<String, Any?>): Pair<Boolean, String> {
        ensureInit()
        val params = JSONObject().put("name", name).put("arguments", Json.fromKotlin(args))
        val r = request("tools/call", params, "call $name ${JSONObject(args.mapValues { it.value?.toString() }).toString().take(160)}")
        val isError = r.optBoolean("isError", false)
        val texts = mutableListOf<String>()
        r.optJSONArray("content")?.let { c ->
            for (i in 0 until c.length()) {
                val part = c.getJSONObject(i)
                when (part.optString("type")) {
                    "text" -> texts += part.optString("text")
                    "resource" -> part.optJSONObject("resource")?.optString("text")?.takeIf { it.isNotEmpty() }?.let { texts += it }
                    else -> texts += "[${part.optString("type")} content]"
                }
            }
        }
        if (texts.isEmpty()) r.optJSONObject("structuredContent")?.let { texts += it.toString() }
        return !isError to texts.joinToString("\n").take(6000)
    }

    private suspend fun ensureInit() {
        if (initialized) return
        val p = JSONObject().put("protocolVersion", "2025-06-18").put("capabilities", JSONObject())
            .put("clientInfo", JSONObject().put("name", "Ronie Pocket").put("version", "0.1"))
        request("initialize", p, "connect")
        notify("notifications/initialized")
        initialized = true
    }

    private suspend fun notify(method: String) = withContext(Dispatchers.IO) {
        runCatching { post(JSONObject().put("jsonrpc", "2.0").put("method", method)).disconnect() }
    }

    private suspend fun request(method: String, params: JSONObject, what: String): JSONObject = withContext(Dispatchers.IO) {
        val id = nextId++
        val body = JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params)
        val t0 = System.currentTimeMillis()
        try {
            val c = post(body)
            val code = c.responseCode
            if (code !in 200..299) {
                val err = runCatching { c.errorStream?.bufferedReader()?.readText() }.getOrNull()?.take(200) ?: ""
                throw McpError(if (code == 401 || code == 403) "the server refused the token (HTTP $code)" else "HTTP $code $err")
            }
            c.getHeaderField("Mcp-Session-Id")?.let { session = it }
            val type = c.contentType ?: ""
            val text = c.inputStream.bufferedReader().use { r ->
                if (type.startsWith("text/event-stream")) readSse(r, id) else r.readText()
            }
            val msg = JSONObject(text)
            msg.optJSONObject("error")?.let { throw McpError(it.optString("message", "error ${it.optInt("code")}")) }
            log.add(host, what, "ok · ${System.currentTimeMillis() - t0} ms")
            msg.optJSONObject("result") ?: JSONObject()
        } catch (e: Exception) {
            log.add(host, what, "failed: ${e.message}")
            throw if (e is McpError) e else McpError(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Reads SSE events until the JSON-RPC response with [id] arrives. */
    private fun readSse(r: java.io.BufferedReader, id: Int): String {
        val data = StringBuilder()
        while (true) {
            val line = r.readLine() ?: break
            if (line.startsWith("data:")) data.append(line.removePrefix("data:").trim())
            else if (line.isEmpty() && data.isNotEmpty()) {
                val msg = runCatching { JSONObject(data.toString()) }.getOrNull()
                data.setLength(0)
                if (msg != null && msg.opt("id")?.toString() == id.toString()) return msg.toString()
            }
        }
        if (data.isNotEmpty()) return data.toString()
        throw McpError("the server closed the stream without an answer")
    }

    private fun post(body: JSONObject): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 10_000; c.readTimeout = 30_000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Accept", "application/json, text/event-stream")
        c.setRequestProperty("MCP-Protocol-Version", "2025-06-18")
        session?.let { c.setRequestProperty("Mcp-Session-Id", it) }
        token?.takeIf { it.isNotBlank() }?.let { c.setRequestProperty("Authorization", "Bearer $it") }
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        return c
    }
}

/** A remote MCP server the user added. */
data class Connector(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    /** Tools as returned by tools/list (raw JSON), refreshed by "Test". */
    val tools: List<JSONObject> = emptyList(),
    /** Tool names the user switched off. */
    val disabledTools: Set<String> = emptySet(),
    val status: String = "",
) {
    val host: String get() = runCatching { URL(url).host }.getOrDefault(url)
    /** Model-facing name prefix, e.g. "notion_". */
    val prefix: String get() = name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(16) + "_"

    fun specs(): List<ToolSpec> = tools.filter { it.optString("name") !in disabledTools }.map {
        ToolSpec.fromMcp(it, rename = (prefix + it.getString("name")).replace(Regex("[^A-Za-z0-9_]"), "_"), connectorId = id, host = host)
    }
}

/** Connectors on disk; tokens are encrypted with a key that never leaves the Android Keystore. */
class ConnectorStore(private val ctx: Context) {
    private val file = File(ctx.filesDir, "tools/connectors.json")

    @Synchronized fun all(): List<Connector> = runCatching {
        val a = JSONArray(file.readText())
        (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            val tools = o.optJSONArray("tools") ?: JSONArray()
            val off = o.optJSONArray("disabledTools") ?: JSONArray()
            Connector(o.getString("id"), o.getString("name"), o.getString("url"), o.optBoolean("enabled", true),
                (0 until tools.length()).map { tools.getJSONObject(it) }, (0 until off.length()).map { off.getString(it) }.toSet(),
                o.optString("status"))
        }
    }.getOrDefault(emptyList())

    @Synchronized fun save(list: List<Connector>) {
        file.parentFile?.mkdirs()
        file.writeText(JSONArray(list.map { c ->
            JSONObject().put("id", c.id).put("name", c.name).put("url", c.url).put("enabled", c.enabled)
                .put("tools", JSONArray(c.tools)).put("disabledTools", JSONArray(c.disabledTools.toList())).put("status", c.status)
        }).toString())
    }

    @Synchronized fun upsert(c: Connector) = save(all().filter { it.id != c.id } + c)
    @Synchronized fun remove(id: String) { save(all().filter { it.id != id }); tokenFile(id).delete() }

    fun setToken(id: String, token: String?) {
        val f = tokenFile(id)
        if (token.isNullOrBlank()) { f.delete(); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val enc = cipher.doFinal(token.toByteArray())
        f.parentFile?.mkdirs()
        f.writeText(Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(enc, Base64.NO_WRAP))
    }

    fun token(id: String): String? = runCatching {
        val (iv, enc) = tokenFile(id).readText().split(":").map { Base64.decode(it, Base64.NO_WRAP) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
        String(cipher.doFinal(enc))
    }.getOrNull()

    private fun tokenFile(id: String) = File(ctx.filesDir, "tools/secrets/$id.tok")

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return gen.generateKey()
    }

    private companion object { const val ALIAS = "ronie_connector_tokens" }
}

/** Every outbound request Ronie makes (connectors now; search and mail later): when, where, what. */
class ActivityLog(ctx: Context) {
    data class Entry(val time: Long, val host: String, val what: String, val result: String)
    private val file = File(ctx.filesDir, "tools/activity.json")

    @Synchronized fun all(): List<Entry> = runCatching {
        val a = JSONArray(file.readText())
        (0 until a.length()).map { a.getJSONObject(it) }.map { Entry(it.getLong("t"), it.getString("host"), it.getString("what"), it.optString("result")) }
    }.getOrDefault(emptyList())

    @Synchronized fun add(host: String, what: String, result: String) {
        val list = (all() + Entry(System.currentTimeMillis(), host, what.take(240), result.take(200))).takeLast(200)
        file.parentFile?.mkdirs()
        file.writeText(JSONArray(list.map { JSONObject().put("t", it.time).put("host", it.host).put("what", it.what).put("result", it.result) }).toString())
    }

    @Synchronized fun clear() { file.delete() }
}
