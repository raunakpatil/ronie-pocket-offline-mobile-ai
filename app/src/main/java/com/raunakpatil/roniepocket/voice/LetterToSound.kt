package com.raunakpatil.roniepocket.voice

/**
 * Last-resort pronunciation for words the dictionary doesn't know (names, made-up words), using simple
 * English spelling rules and putting the main stress on the first vowel. Rough, but far better than
 * skipping the word. Known names Ronie should say well go in assets/voice/ronie_words.tsv instead.
 */
object LetterToSound {
    // Longest patterns first. Values are Kokoro/Paradee phonemes (US).
    private val rules: List<Pair<String, String>> = listOf(
        "tion" to "ʃən", "sion" to "ʒən", "ture" to "ʧɚ", "ough" to "O", "augh" to "ɔ",
        "igh" to "I", "eigh" to "A", "tch" to "ʧ", "dge" to "ʤ", "sch" to "sk", "que" to "k",
        "ai" to "A", "ay" to "A", "ee" to "i", "ea" to "i", "ie" to "i", "ei" to "A", "ey" to "A",
        "oo" to "u", "ou" to "W", "ow" to "O", "oa" to "O", "oe" to "O", "au" to "ɔ", "aw" to "ɔ",
        "oi" to "Y", "oy" to "Y", "ue" to "u", "ui" to "u", "ew" to "u",
        "ch" to "ʧ", "sh" to "ʃ", "th" to "θ", "ph" to "f", "ng" to "ŋ", "ck" to "k", "qu" to "kw",
        "wh" to "w", "kn" to "n", "wr" to "ɹ", "gh" to "ɡ", "bh" to "b", "dh" to "d", "kh" to "k",
        "a" to "æ", "e" to "ɛ", "i" to "ɪ", "o" to "ɑ", "u" to "ʌ",
        "b" to "b", "d" to "d", "f" to "f", "h" to "h", "j" to "ʤ", "k" to "k", "l" to "l", "m" to "m",
        "n" to "n", "p" to "p", "r" to "ɹ", "s" to "s", "t" to "t", "v" to "v", "w" to "w", "x" to "ks",
        "z" to "z",
    )

    fun convert(word: String): String {
        val w = word.lowercase().filter { it in 'a'..'z' }
        if (w.isEmpty()) return ""
        val out = StringBuilder()
        var i = 0
        while (i < w.length) {
            val c = w[i]
            val next = w.getOrNull(i + 1)
            // Context-dependent letters
            when {
                c == 'c' && w.startsWith("ch", i).not() && w.startsWith("ck", i).not() ->
                    { out.append(if (next != null && next in "eiy") "s" else "k"); i++; continue }
                c == 'g' && w.startsWith("gh", i).not() && w.startsWith("ng", i - 1).not() ->
                    { out.append(if (next != null && next in "ei" && i > 0) "ʤ" else "ɡ"); i++; continue }
                c == 'y' -> { out.append(if (i == 0) "j" else if (i == w.length - 1) "i" else "ɪ"); i++; continue }
                c == 'e' && i == w.length - 1 && w.length > 3 && out.any { it in Lexicon.VOWELS } -> { i++; continue } // silent e
                c == 'a' && i == w.length - 1 -> { out.append("ə"); i++; continue }
                c == 'o' && i == w.length - 1 -> { out.append("O"); i++; continue }
                c == 'i' && i == w.length - 1 -> { out.append("i"); i++; continue }
                c == 'u' && i == w.length - 1 -> { out.append("u"); i++; continue }
            }
            val r = rules.firstOrNull { w.startsWith(it.first, i) }
            if (r == null) { i++; continue }
            // Doubled consonants sound once.
            if (r.first.length == 1 && out.isNotEmpty() && out.endsWith(r.second) && r.second.single() in Lexicon.CONSONANTS) {
                i++; continue
            }
            out.append(r.second)
            i += r.first.length
        }
        val ps = out.toString()
        val v = ps.indexOfFirst { it in Lexicon.VOWELS }
        return if (v < 0) ps else ps.substring(0, v) + Lexicon.PRIMARY + ps.substring(v)
    }
}
