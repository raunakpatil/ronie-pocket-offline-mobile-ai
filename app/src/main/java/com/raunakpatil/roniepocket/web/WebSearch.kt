package com.raunakpatil.roniepocket.web

import android.util.Log
import com.raunakpatil.roniepocket.memory.Embedder
import com.raunakpatil.roniepocket.memory.dot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder

/** One search result. */
data class Hit(val title: String, val url: String, val snippet: String) {
    val host: String get() = runCatching { URI(url).host.removePrefix("www.") }.getOrDefault(url)
}

/** A picture found for an explanation: the downloaded thumbnail, what it shows and the page it's from. */
data class WebImage(val path: String, val title: String, val pageUrl: String)

/** A numbered source Ronie can cite as [n]. */
data class Source(val n: Int, val title: String, val url: String, val host: String, val text: String)

/**
 * Web search without an API key (user decision, Phase 8): DuckDuckGo's lightweight results page for
 * general searches, Wikipedia's official API as a fallback. Then the top pages are read, cut into passages,
 * and the passages closest to the question (bge-small, same as memory) become numbered sources.
 *
 * DuckDuckGo has no official free API; reading its lite page is unofficial and could break or be rate-
 * limited, so requests are few and slow-paced, and Wikipedia is there when it fails.
 */
class WebSearch(private val log: (host: String, what: String, result: String) -> Unit) {
    companion object {
        private const val UA = "Mozilla/5.0 (Linux; Android 15) RoniePocket/0.1"
        private const val PAGES = 3
        private const val PASSAGES = 4

        /** Parses DuckDuckGo lite's results page (pure function: unit-tested on a saved page). */
        fun parseDdgLite(doc: Document): List<Hit> {
            val links = doc.select("a.result-link")
            val snippets = doc.select("td.result-snippet")
            return links.mapIndexedNotNull { i, a ->
                val url = a.attr("href")
                if (!url.startsWith("http") || "duckduckgo.com/y.js" in url) return@mapIndexedNotNull null   // ads
                Hit(a.text().trim(), url, snippets.getOrNull(i)?.text()?.trim() ?: "")
            }
        }

        /**
         * The readable text of a page: drops scripts, menus, headers, footers, forms and asides, then keeps
         * headings, paragraphs, list items and table cells from the article (or main, or body).
         */
        fun readable(doc: Document): String {
            doc.select("script, style, noscript, nav, header, footer, aside, form, iframe, svg, button, " +
                "[role=navigation], [aria-hidden=true], .navbox, .reflist, .references, .mw-editsection, .infobox-below").remove()
            val root = doc.selectFirst("article") ?: doc.selectFirst("main") ?: doc.selectFirst("#mw-content-text") ?: doc.body() ?: return ""
            val parts = root.select("h1, h2, h3, p, li, td, th, blockquote, figcaption").map { it.ownText().ifBlank { it.text() }.trim() }
                .filter { it.length >= 25 || (it.length > 2 && it.endsWith(":")) }
            return parts.distinct().joinToString("\n").take(24_000)
        }

        /** ~500-character passages on sentence boundaries. */
        fun passages(text: String, size: Int = 500): List<String> {
            val out = ArrayList<String>()
            val cur = StringBuilder()
            for (sentence in text.split(Regex("(?<=[.!?])\\s+|\\n+"))) {
                val s = sentence.trim()
                if (s.isEmpty()) continue
                if (cur.length + s.length > size && cur.isNotEmpty()) { out += cur.toString(); cur.setLength(0) }
                cur.append(s).append(' ')
            }
            if (cur.length > 40) out += cur.toString()
            return out.map { it.trim() }
        }
    }

    suspend fun search(query: String): List<Hit> = withContext(Dispatchers.IO) {
        val ddg = runCatching {
            val doc = Jsoup.connect("https://lite.duckduckgo.com/lite/").userAgent(UA).timeout(8000)
                .data("q", query).post()
            parseDdgLite(doc)
        }.onFailure { log("duckduckgo.com", "search \"$query\"", "failed: ${it.message}") }.getOrNull()
        if (!ddg.isNullOrEmpty()) { log("duckduckgo.com", "search \"$query\"", "${ddg.size} results"); return@withContext ddg }
        // Fallback: Wikipedia's official search API.
        runCatching {
            val q = URLEncoder.encode(query, "UTF-8")
            val body = Jsoup.connect("https://en.wikipedia.org/w/api.php?action=query&list=search&srlimit=5&format=json&srsearch=$q")
                .userAgent("RoniePocket/0.1 (on-device assistant)").ignoreContentType(true).timeout(8000).execute().body()
            val arr = JSONObject(body).getJSONObject("query").getJSONArray("search")
            (0 until arr.length()).map { arr.getJSONObject(it) }.map { o ->
                val title = o.getString("title")
                Hit(title, "https://en.wikipedia.org/wiki/" + URLEncoder.encode(title.replace(' ', '_'), "UTF-8"),
                    Jsoup.parse(o.optString("snippet")).text())
            }.also { log("en.wikipedia.org", "search \"$query\"", "${it.size} results") }
        }.onFailure { log("en.wikipedia.org", "search \"$query\"", "failed: ${it.message}") }.getOrDefault(emptyList())
    }

    private suspend fun read(url: String): String? = withContext(Dispatchers.IO) {
        if (!url.startsWith("https://")) return@withContext null          // HTTPS only (network security config)
        withTimeoutOrNull(7000) {
            runCatching {
                val doc = Jsoup.connect(url).userAgent(UA).timeout(6000).maxBodySize(1_500_000).followRedirects(true).get()
                readable(doc)
            }.onFailure { Log.w("RonieWeb", "couldn't read $url: ${it.message}") }.getOrNull()
        }.also { t -> log(runCatching { URI(url).host }.getOrDefault(url), "read page", if (t.isNullOrEmpty()) "failed" else "${t.length} chars") }
    }

    /**
     * Search, read the top pages in parallel, and turn each of the top sites into one numbered source: the
     * search engine's own snippet (usually the most informative line) plus the page's single best passage
     * for [query] (bge-small). Short on purpose: long sources made Ronie slow to answer (phone test: ~18 s of
     * reading), and boilerplate passages misled him.
     */
    suspend fun ground(query: String, embedder: Embedder?): List<Source> = coroutineScope {
        val hits = search(query)
        if (hits.isEmpty()) return@coroutineScope emptyList()
        val top = hits.distinctBy { it.host }.take(PAGES)
        val pages = top.map { h -> async { h to (read(h.url) ?: "") } }.awaitAll()
        val q = embedder?.let { e -> withContext(Dispatchers.Default) { e.embedQuery(query) } }
        withContext(Dispatchers.Default) {
            pages.mapIndexed { i, (h, text) ->
                // The page's two passages closest to the question (one was often too thin to answer from).
                val best = if (q == null || embedder == null) emptyList() else passages(text, 300)
                    .filter { !looksLikeBoilerplate(it) }.take(40)
                    .map { it to dot(q, embedder.embedDoc(it)) }.sortedByDescending { it.second }
                    .filter { it.second >= 0.45f }.take(2).map { it.first }
                val body = (listOfNotNull(h.snippet.takeIf { it.isNotBlank() }) + best).distinct().joinToString(" … ")
                Source(i + 1, h.title, h.url, h.host, body)
            }.filter { it.text.isNotBlank() }
        }
    }

    /**
     * Pictures of [query] from Wikipedia (its official API, no key): the lead image of the best-matching
     * articles, downloaded as small thumbnails into [dir]. Used to show what something looks like while
     * Ronie explains it. Wikipedia only, so the pictures are of the thing itself and freely licensed.
     */
    suspend fun images(query: String, dir: java.io.File, max: Int = 3): List<WebImage> = withContext(Dispatchers.IO) {
        runCatching {
            val q = URLEncoder.encode(query, "UTF-8")
            val body = Jsoup.connect("https://en.wikipedia.org/w/api.php?action=query&format=json&generator=search&gsrsearch=$q&gsrlimit=6" +
                "&prop=pageimages%7Cinfo&piprop=thumbnail&pithumbsize=640&pilicense=any&inprop=url")
                .userAgent("RoniePocket/0.1 (on-device assistant)").ignoreContentType(true).timeout(8000).execute().body()
            val pages = JSONObject(body).optJSONObject("query")?.optJSONObject("pages") ?: return@runCatching emptyList<WebImage>()
            val found = pages.keys().asSequence().map { pages.getJSONObject(it) }
                .filter { it.optJSONObject("thumbnail")?.optString("source")?.startsWith("https://") == true }
                .sortedBy { it.optInt("index", 99) }.take(max).toList()
            dir.mkdirs()
            found.mapNotNull { p ->
                val src = p.getJSONObject("thumbnail").getString("source")
                val name = java.security.MessageDigest.getInstance("SHA-1").digest(src.toByteArray()).joinToString("") { "%02x".format(it) }.take(20)
                val f = java.io.File(dir, "$name.${if (src.substringAfterLast('.').lowercase() == "png") "png" else "jpg"}")
                runCatching {
                    if (!f.exists()) {
                        val bytes = Jsoup.connect(src).userAgent("RoniePocket/0.1 (on-device assistant)").ignoreContentType(true)
                            .timeout(8000).maxBodySize(2_000_000).execute().bodyAsBytes()
                        f.writeBytes(bytes)
                    }
                    WebImage(f.absolutePath, p.optString("title"), p.optString("fullurl"))
                }.getOrNull()
            }.also { log("en.wikipedia.org", "pictures of \"$query\"", "${it.size} pictures") }
        }.onFailure { log("en.wikipedia.org", "pictures of \"$query\"", "failed: ${it.message}") }.getOrDefault(emptyList())
    }

    /** Legends, cookie banners, "sign up" blurbs: text that is on the page but isn't about anything. */
    private fun looksLikeBoilerplate(p: String): Boolean {
        val l = p.lowercase()
        return listOf("cookie", "subscribe", "sign up", "sign in", "log in", "all rights reserved", "privacy policy",
            "terms of use", "advertisement", "javascript", "positions in", "click here", "newsletter").any { it in l } ||
            p.count { it.isDigit() } > p.length / 3
    }
}

/**
 * Takes personal details out of a search query before it leaves the phone: the user's name, email
 * addresses, phone numbers, long ID-like numbers and street addresses. The exact outgoing query is what
 * the Activity log shows.
 */
object Scrubber {
    private val EMAIL = Regex("[\\w.+-]+@[\\w-]+\\.[\\w.]+")
    private val PHONE = Regex("(?<!\\w)(\\+?\\d[\\d\\s-]{8,}\\d)(?!\\w)")
    private val LONGNUM = Regex("\\b\\d{9,}\\b")
    private val STREET = Regex("\\b\\d{1,5}\\s+([A-Z][a-z]+\\s){1,3}(Road|Rd|Street|St|Lane|Ln|Avenue|Ave|Marg|Nagar)\\b")

    fun scrub(query: String, names: List<String> = emptyList()): String {
        var q = query
        q = EMAIL.replace(q, "")
        q = PHONE.replace(q, "")
        q = LONGNUM.replace(q, "")
        q = STREET.replace(q, "")
        for (n in names.filter { it.length >= 2 }) q = Regex("\\b${Regex.escape(n)}('s)?\\b", RegexOption.IGNORE_CASE).replace(q, "")
        return q.replace(Regex("\\s{2,}"), " ").trim()
    }
}
