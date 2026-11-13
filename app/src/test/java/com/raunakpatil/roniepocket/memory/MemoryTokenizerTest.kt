package com.raunakpatil.roniepocket.memory

import org.json.JSONArray
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.io.File

/** The Kotlin WordPiece tokenizer must match Hugging Face's exactly (golden from eval, 150 texts incl. edge cases). */
class MemoryTokenizerTest {
    @Test fun matchesHuggingFace() {
        val tok = WordPiece(File("src/main/assets/memory/bge-tokenizer.json").readText())
        val golden = JSONArray(File("src/test/resources/memory/bge_tokenizer_golden.json").readText())
        for (i in 0 until golden.length()) {
            val o = golden.getJSONObject(i)
            val want = o.getJSONArray("ids").let { a -> IntArray(a.length()) { a.getInt(it) } }
            assertArrayEquals("text: ${o.getString("text")}", want, tok.encode(o.getString("text")))
        }
    }
}
