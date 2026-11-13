package com.raunakpatil.roniepocket.imagegen

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class GemmaTokenizerTest {

    @Test
    fun testEncodeMatchesSentencePiece() {
        val spmFile = File("src/main/assets/imagegen/gemma_spm.bin")
        if (!spmFile.exists()) {
            println("Skipping test: ${spmFile.absolutePath} not found in test working directory")
            return
        }

        val tokenizer = GemmaTokenizer.load(spmFile)

        // Case 1: "Draw a cat astronaut in watercolour"
        val enc1 = tokenizer.encode("Draw a cat astronaut in watercolour")
        val expectedIds1 = longArrayOf(2L, 5205L, 476L, 4401L, 103846L, 575L, 148148L)
        for (i in expectedIds1.indices) {
            assertEquals("ID at $i", expectedIds1[i], enc1.inputIds[i])
            assertEquals("Mask at $i", 1L, enc1.attentionMask[i])
        }
        for (i in expectedIds1.size until 300) {
            assertEquals("Padding ID at $i", 0L, enc1.inputIds[i])
            assertEquals("Padding mask at $i", 0L, enc1.attentionMask[i])
        }

        // Case 2: "a photorealistic cat astronaut in nebula space"
        val enc2 = tokenizer.encode("a photorealistic cat astronaut in nebula space")
        val expectedIds2 = longArrayOf(2L, 235250L, 2686L, 54385L, 4401L, 103846L, 575L, 125461L, 3641L)
        for (i in expectedIds2.indices) {
            assertEquals("ID at $i", expectedIds2[i], enc2.inputIds[i])
            assertEquals("Mask at $i", 1L, enc2.attentionMask[i])
        }
    }
}
