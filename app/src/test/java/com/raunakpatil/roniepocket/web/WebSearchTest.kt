package com.raunakpatil.roniepocket.web

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WebSearchTest {
    @Test fun parsesDuckDuckGoLite() {
        val hits = WebSearch.parseDdgLite(Jsoup.parse(File("src/test/resources/web/ddg_lite_f1.html").readText()))
        assertTrue("got ${hits.size}", hits.size >= 5)
        assertEquals("https://en.wikipedia.org/wiki/2025_Formula_One_World_Championship", hits[0].url)
        assertEquals("en.wikipedia.org", hits[0].host)
        assertTrue(hits[0].title.contains("Formula One"))
        assertTrue(hits.all { it.snippet.isNotBlank() && it.url.startsWith("http") })
    }

    @Test fun readsTheArticleNotTheChrome() {
        val html = """<html><body><nav>Home | About | Login to see more stuff here please</nav>
            <header>Big site header with lots of words in it</header>
            <article><h1>How to make chai</h1><p>Boil water with crushed cardamom and ginger for two minutes.</p>
            <ul><li>Add black tea leaves and simmer for another minute.</li></ul><script>var x = 1;</script></article>
            <footer>Copyright footer that should never be read by Ronie</footer></body></html>"""
        val text = WebSearch.readable(Jsoup.parse(html))
        assertTrue(text, "cardamom" in text && "black tea" in text)
        assertFalse(text, "Login" in text || "Copyright" in text || "var x" in text)
    }

    @Test fun cutsIntoPassages() {
        val text = (1..40).joinToString(" ") { "This is sentence number $it about something." }
        val ps = WebSearch.passages(text, size = 200)
        assertTrue(ps.size in 6..12)
        assertTrue(ps.all { it.length <= 260 })
    }

    @Test fun scrubsPersonalDetails() {
        val q = Scrubber.scrub("Raunak's flight from Pune, email raunak@example.com call +91 98765 43210 at 12 Baner Road", listOf("Raunak"))
        assertFalse(q, "Raunak" in q || "@" in q || "98765" in q || "Baner Road" in q)
        assertTrue(q, "flight from Pune" in q)
        assertEquals("who won the 2025 F1 championship", Scrubber.scrub("who won the 2025 F1 championship"))
    }
}
