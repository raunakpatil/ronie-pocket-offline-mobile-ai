package com.raunakpatil.roniepocket.voice

import java.io.InputStream

/**
 * English (US) pronunciation dictionary, ported from misaki's `Lexicon` (hexgrad/misaki, Apache-2.0).
 * Produces phonemes in Kokoro/Paradee's alphabet. Differences from misaki: no spaCy part-of-speech
 * tagger on the phone, so words with several pronunciations use their default unless a simple rule
 * (e.g. "used to") applies.
 */
class Lexicon private constructor(
    private val golds: Map<String, Any>,     // String or Map<String, String?> (tag -> phonemes)
    private val silvers: Map<String, Any>,
) {
    companion object {
        const val PRIMARY = 'ˈ'
        const val SECONDARY = 'ˌ'
        private const val STRESSES = "ˌˈ"
        val VOWELS = "AIOQWYaiuæɑɒɔəɛɜɪʊʌᵻ".toSet()
        val CONSONANTS = "bdfhjklmnpstvwzðŋɡɹɾʃʒʤʧθ".toSet()
        private val DIPHTHONGS = "AIOQWYʤʧ".toSet()
        private val US_TAUS = "AIOWYiuæɑəɛɪɹʊʌ".toSet()
        val SYMBOLS = mapOf("%" to "percent", "&" to "and", "+" to "plus", "@" to "at")

        fun load(gold: InputStream, silver: InputStream, extra: InputStream? = null): Lexicon {
            val g = HashMap<String, Any>(120_000)
            read(gold, g)
            extra?.let { read(it, g) }   // Ronie's own words (names, places) override
            val s = HashMap<String, Any>(120_000)
            read(silver, s)
            return Lexicon(g, s)
        }

        private fun read(input: InputStream, into: MutableMap<String, Any>) {
            input.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                val tab = line.indexOf('\t')
                if (tab <= 0) return@forEachLine
                val word = line.substring(0, tab)
                val v = line.substring(tab + 1)
                into[word] = if ('=' in v) v.split('|').associate {
                    val eq = it.indexOf('=')
                    it.substring(0, eq) to it.substring(eq + 1).ifEmpty { null }
                } else v
            }
        }

        fun capitalize(w: String) = if (w.isEmpty()) w else w[0].uppercase() + w.substring(1).lowercase()

        /** misaki's apply_stress. */
        fun applyStress(ps: String?, stress: Double?): String? {
            if (ps == null || stress == null) return ps
            val hasP = PRIMARY in ps
            val hasAny = ps.any { it in STRESSES }
            return when {
                stress < -1 -> ps.replace(PRIMARY.toString(), "").replace(SECONDARY.toString(), "")
                stress == -1.0 || ((stress == 0.0 || stress == -0.5) && hasP) ->
                    ps.replace(SECONDARY.toString(), "").replace(PRIMARY, SECONDARY)
                (stress == 0.0 || stress == 0.5 || stress == 1.0) && !hasAny ->
                    if (ps.none { it in VOWELS }) ps else restress(SECONDARY + ps)
                stress >= 1 && !hasP && SECONDARY in ps -> ps.replace(SECONDARY, PRIMARY)
                stress > 1 && !hasAny -> if (ps.none { it in VOWELS }) ps else restress(PRIMARY + ps)
                else -> ps
            }
        }

        /** Moves each stress mark to just before the next vowel. */
        private fun restress(ps: String): String {
            val items = ps.mapIndexed { i, c -> i.toDouble() to c }.toMutableList()
            for (i in ps.indices) {
                if (ps[i] in STRESSES) {
                    val j = (i until ps.length).firstOrNull { ps[it] in VOWELS } ?: continue
                    items[i] = (j - 0.5) to ps[i]
                }
            }
            return items.sortedBy { it.first }.joinToString("") { it.second.toString() }
        }

        fun stressWeight(ps: String?) = ps?.sumOf { if (it in DIPHTHONGS) 2 else 1 } ?: 0

        private fun parentTag(tag: String?): String? = when {
            tag == null -> null
            tag.startsWith("VB") -> "VERB"
            tag.startsWith("NN") -> "NOUN"
            tag.startsWith("ADV") || tag.startsWith("RB") -> "ADV"
            tag.startsWith("ADJ") || tag.startsWith("JJ") -> "ADJ"
            else -> tag
        }

        private fun isLexiconChar(c: Char) = c == '\'' || c == '-' || c in 'A'..'Z' || c in 'a'..'z'
    }

    /** Context from the words that come after this one (we read right to left, like misaki). */
    data class Ctx(val futureVowel: Boolean? = null, val futureTo: Boolean = false)

    /** misaki's grow_dictionary, done at lookup time instead of doubling the maps. */
    private fun grown(d: Map<String, Any>, w: String): Any? {
        d[w]?.let { return it }
        if (w.length < 2) return null
        val lower = w.lowercase()
        if (w == capitalize(lower) && w != lower) d[lower]?.let { return it }
        if (w == lower && w != capitalize(w)) d[capitalize(w)]?.let { return it }
        return null
    }

    fun gold(w: String): Any? = grown(golds, w)
    private fun silver(w: String): Any? = grown(silvers, w)

    private fun nnp(word: String): Pair<String, Int>? {
        val parts = word.filter { it.isLetter() }.map { c -> (gold(c.uppercase()) as? String) ?: return null }
        val ps = applyStress(parts.joinToString(""), 0.0) ?: return null
        val i = ps.lastIndexOf(SECONDARY)
        return (if (i >= 0) ps.substring(0, i) + PRIMARY + ps.substring(i + 1) else ps) to 3
    }

    private fun specialCase(word: String, tag: String?, stress: Double?, ctx: Ctx): Pair<String, Int>? = when {
        word in SYMBOLS -> lookup(SYMBOLS.getValue(word), null, null, ctx)
        '.' in word.trim('.') && word.replace(".", "").all { it.isLetter() } &&
            word.split('.').maxOf { it.length } < 3 -> nnp(word)
        word == "a" || (word == "A" && tag == "DT") -> "ɐ" to 4
        word == "am" || word == "Am" || word == "AM" ->
            if (ctx.futureVowel == null || word != "am" || (stress != null && stress > 0)) (gold("am") as String) to 4
            else "ɐm" to 4
        word == "an" || word == "An" || word == "AN" -> "ɐn" to 4
        word == "I" -> "${SECONDARY}I" to 4
        word == "to" || word == "To" -> when (ctx.futureVowel) {
            null -> (gold("to") as String) to 4; false -> "tə" to 4; true -> "tʊ" to 4
        }
        word == "the" || word == "The" -> (if (ctx.futureVowel == true) "ði" else "ðə") to 4
        word == "used" || word == "Used" || word == "USED" -> {
            val m = gold("used") as Map<*, *>
            ((if (ctx.futureTo) m["VBD"] else m["DEFAULT"]) as String) to 4
        }
        else -> null
    }

    fun isKnown(word: String): Boolean = when {
        gold(word) != null || word in SYMBOLS || silver(word) != null -> true
        !word.all { it.isLetter() } || !word.all(::isLexiconChar) -> false
        word.length == 1 -> true
        word == word.uppercase() && gold(word.lowercase()) != null -> true
        else -> word.substring(1) == word.substring(1).uppercase()
    }

    fun lookup(word0: String, tag0: String?, stress: Double?, ctx: Ctx?): Pair<String, Int>? {
        var word = word0
        var tag = tag0
        var isNNP: Boolean? = null
        if (word == word.uppercase() && gold(word) == null) {
            word = word.lowercase()
            isNNP = tag == "NNP"
        }
        var ps: Any? = gold(word)
        var rating = 4
        if (ps == null && isNNP != true) { ps = silver(word); rating = 3 }
        if (ps is Map<*, *>) {
            if (ctx != null && ctx.futureVowel == null && "None" in ps) tag = "None"
            else if (tag !in ps) tag = parentTag(tag)
            ps = if (tag != null && ps.containsKey(tag)) ps[tag] else ps["DEFAULT"]
        }
        var s = ps as String?
        if (s == null || (isNNP == true && PRIMARY !in s)) {
            nnp(word)?.let { return it }
        }
        s ?: return null
        return applyStress(s, stress)!! to rating
    }

    private fun sfx(stem: String?): String? = when {
        stem.isNullOrEmpty() -> null
        stem.last() in "ptkfθ" -> stem + "s"
        stem.last() in "szʃʒʧʤ" -> stem + "ᵻz"
        else -> stem + "z"
    }

    private fun stemS(word: String, tag: String?, stress: Double?, ctx: Ctx?): Pair<String, Int>? {
        val stem = when {
            word.length > 2 && word.endsWith("s") && !word.endsWith("ss") && isKnown(word.dropLast(1)) -> word.dropLast(1)
            (word.endsWith("'s") || (word.length > 4 && word.endsWith("es"))) && isKnown(word.dropLast(2)) -> word.dropLast(2)
            word.length > 4 && word.endsWith("ies") && isKnown(word.dropLast(3) + "y") -> word.dropLast(3) + "y"
            else -> return null
        }
        val (ps, r) = lookup(stem, tag, stress, ctx) ?: return null
        return (sfx(ps) ?: return null) to r
    }

    fun suffixS(ps: String?) = sfx(ps)

    fun suffixEd(stem: String?): String? = when {
        stem.isNullOrEmpty() -> null
        stem.last() in "pkfθʃsʧ" -> stem + "t"
        stem.last() == 'd' -> stem + "ᵻd"
        stem.last() != 't' -> stem + "d"
        stem.length < 2 -> stem + "ɪd"
        stem[stem.length - 2] in US_TAUS -> stem.dropLast(1) + "ɾᵻd"
        else -> stem + "ᵻd"
    }

    private fun stemEd(word: String, tag: String?, stress: Double?, ctx: Ctx?): Pair<String, Int>? {
        val stem = when {
            word.endsWith("d") && !word.endsWith("dd") && isKnown(word.dropLast(1)) -> word.dropLast(1)
            word.endsWith("ed") && !word.endsWith("eed") && isKnown(word.dropLast(2)) -> word.dropLast(2)
            else -> return null
        }
        val (ps, r) = lookup(stem, tag, stress, ctx) ?: return null
        return (suffixEd(ps) ?: return null) to r
    }

    fun suffixIng(stem: String?): String? = when {
        stem.isNullOrEmpty() -> null
        stem.length > 1 && stem.last() == 't' && stem[stem.length - 2] in US_TAUS -> stem.dropLast(1) + "ɾɪŋ"
        else -> stem + "ɪŋ"
    }

    private val doubled = Regex("([bcdgklmnprstvxz])\\1ing$|cking$")

    private fun stemIng(word: String, tag: String?, stress: Double?, ctx: Ctx?): Pair<String, Int>? {
        val stem = when {
            word.endsWith("ing") && isKnown(word.dropLast(3)) -> word.dropLast(3)
            word.endsWith("ing") && isKnown(word.dropLast(3) + "e") -> word.dropLast(3) + "e"
            doubled.containsMatchIn(word) && isKnown(word.dropLast(4)) -> word.dropLast(4)
            else -> return null
        }
        val (ps, r) = lookup(stem, tag, stress, ctx) ?: return null
        return (suffixIng(ps) ?: return null) to r
    }

    fun getWord(word: String, tag: String?, stress: Double?, ctx: Ctx): Pair<String, Int>? {
        specialCase(word, tag, stress, ctx)?.let { return it }
        if (isKnown(word)) return lookup(word, tag, stress, ctx)
        if (word.endsWith("s'") && isKnown(word.dropLast(2) + "'s")) return lookup(word.dropLast(2) + "'s", tag, stress, ctx)
        if (word.endsWith("'") && isKnown(word.dropLast(1))) return lookup(word.dropLast(1), tag, stress, ctx)
        stemS(word, tag, stress, ctx)?.let { return it }
        stemEd(word, tag, stress, ctx)?.let { return it }
        stemIng(word, tag, stress ?: 0.5, ctx)?.let { return it }
        return null
    }

    /** Whole-word entry point (misaki Lexicon.__call__ minus numbers, which G2P handles). */
    fun word(text: String, tag: String?, ctx: Ctx): String? {
        val w = java.text.Normalizer.normalize(text.replace('‘', '\'').replace('’', '\''), java.text.Normalizer.Form.NFKC)
        val stress = if (w == w.lowercase()) null else if (w == w.uppercase()) 2.0 else 0.5
        getWord(w, tag, stress, ctx)?.let { return it.first }
        if (!w.all(::isLexiconChar)) return null
        if (w != w.lowercase() && (w == w.uppercase() || w.substring(1) == w.substring(1).lowercase())) {
            getWord(w.lowercase(), tag, stress, ctx)?.let { return it.first }
        }
        return null
    }
}
