package com.raunakpatil.roniepocket.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/** Pronunciation checks against misaki's dictionaries (runs on the PC: scripts\test.cmd). */
class G2PTest {
    companion object {
        private lateinit var g2p: G2P
        private lateinit var vocab: Set<Char>
        private val dir = File("src/main/assets/voice")

        @BeforeClass @JvmStatic fun setUp() {
            val lex = Lexicon.load(File(dir, "us_gold.tsv").inputStream(), File(dir, "us_silver.tsv").inputStream(),
                File(dir, "ronie_words.tsv").inputStream())
            g2p = G2P(lex)
            val cfg = File(dir, "paradee_config.json").readText()
            vocab = Regex("\"(.)\"\\s*:\\s*\\d+").findAll(cfg.substringAfter("\"vocab\"")).map { it.groupValues[1][0] }.toSet()
        }
    }

    private fun ps(t: String) = g2p.phonemize(t).also { println("$t  ->  $it") }

    @Test fun dictionaryWords() {
        assertEquals("həlˈO wˈɜɹld.", ps("Hello world."))
        assertTrue(ps("Ronie is here").startsWith("ɹˈOni"))
    }

    @Test fun contextTheAndTo() {
        assertTrue(ps("the apple").startsWith("ði "))
        assertTrue(ps("the car").startsWith("ðə "))
    }

    @Test fun stemming() {
        assertTrue(ps("walked").endsWith("t"))
        assertTrue(ps("boxes").endsWith("ᵻz"))
    }

    @Test fun numbersMoneyTimes() {
        val n = ps("It has 12 staff and opened on 3 May 2024.")
        assertTrue(n, "twˈɛlv" in n && "θɹˈi" in n)
        val r = ps("The ticket cost ₹2451.")
        assertTrue(r, r.contains("ɹupˈiz") && r.contains("θˈWzᵊnd"))
        assertTrue(ps("$3.50").contains("dˈɑləɹz") || ps("$3.50").contains("dˈɑlɚz"))
        assertTrue(ps("Meet at 7:30 pm").contains("sˈɛvən"))
        assertTrue(ps("50% off").contains("pəɹsˈɛnt") || ps("50% off").contains("pɚsˈɛnt"))
    }

    @Test fun cleansMarkdownAndEmoji() {
        val p = ps("**Staff:** 12 👍 see https://example.com")
        assertTrue(p, "*" !in p && "👍" !in p && "lˈɪŋk" in p)
    }

    @Test fun joinedWordsAndSlashes() {
        val p = ps("Check OneDrive for the upload/submission.")
        assertTrue(p, "wˈʌn" in p && "dɹˈIv" in p)
        assertTrue(p, "ˈʌplˌOd sə" in p || "ˈʌplˌOd s" in p)
    }

    @Test fun unknownWordsStillSpeak() {
        val p = ps("Kshitij likes Bhopal")
        assertTrue(p.isNotBlank() && "❓" !in p)
    }

    @Test fun everyCharacterIsInParadeesVocab() {
        val text = "Fuck. That's the worst. Check if it charges at all, then grab your files from the cloud backup! " +
            "It opened on May 3, 2024; 12 staff, 7 am — honestly? Ronie's got this… (probably)."
        val p = ps(text)
        val bad = p.filter { it != ' ' && it !in vocab }.toSet()
        assertTrue("not in vocab: $bad", bad.isEmpty())
    }
}
