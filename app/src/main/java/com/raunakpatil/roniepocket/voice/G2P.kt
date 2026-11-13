package com.raunakpatil.roniepocket.voice

/**
 * Text -> phonemes (Kokoro/Paradee alphabet), following misaki's English G2P.
 * Pipeline: clean (markdown, emoji, links) -> tokenize -> read right-to-left with context
 * (so "the apple" says "ði", "to eat" says "tʊ") -> numbers / money / times -> dictionary
 * -> letter-to-sound rules for anything unknown.
 */
class G2P(private val lex: Lexicon) {

    private sealed interface Tok { val space: Boolean }
    private data class Word(val text: String, override val space: Boolean) : Tok
    private data class Num(val text: String, val currency: String?, val suffix: String, override val space: Boolean) : Tok
    private data class Time(val h: Int, val m: Int, override val space: Boolean) : Tok
    private data class Punct(val ph: String, override val space: Boolean) : Tok

    companion object {
        private const val PUNCTS = ";:,.!?—…\"“”()"
        private val NON_QUOTE = ";:,.!?—…".toSet()
        private val TOKEN = Regex(
            "(?<time>\\b\\d{1,2}:\\d{2}\\b)" +
                "|(?<num>[$£€₹]?\\d[\\d,]*(?:\\.\\d+)?(?:st|nd|rd|th|s|%)?)" +
                "|(?<word>[A-Za-z]+(?:['’][A-Za-z]+)*'?)" +
                "|(?<punct>[;:,.!?—…\"“”()–-])" +
                "|(?<sym>[%&+@])"
        )
        private val EMOJI = Regex("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}\\x{200D}\\x{1F1E6}-\\x{1F1FF}]")
        private val LINK = Regex("https?://\\S+|www\\.\\S+")
        private val MD = Regex("[*_`#>|~]+|^\\s*[-•]\\s+|^\\s*\\d+[.)]\\s+", RegexOption.MULTILINE)

        /** Strips what shouldn't be spoken: markdown, emoji, links. */
        fun clean(text: String): String = text
            .replace(LINK, " link ")
            .replace(EMOJI, "")
            .replace(MD, " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun phonemize(text: String): String {
        val toks = tokenize(clean(text))
        val out = arrayOfNulls<String>(toks.size)
        var ctx = Lexicon.Ctx()
        for (i in toks.indices.reversed()) {
            val t = toks[i]
            val prev = toks.getOrNull(i - 1)
            val ps: String = when (t) {
                is Punct -> t.ph
                is Time -> time(t.h, t.m)
                is Num -> number(t)
                is Word -> wordPhonemes(t.text, ctx, prevIsNumber = prev is Num || prev is Time)
            }
            out[i] = ps
            ctx = nextCtx(ctx, ps, t)
        }
        val sb = StringBuilder()
        toks.forEachIndexed { i, t ->
            sb.append(out[i])
            if (t.space && i < toks.size - 1) sb.append(' ')
        }
        return sb.toString().replace(Regex(" +"), " ").trim()
    }

    private fun nextCtx(ctx: Lexicon.Ctx, ps: String, t: Tok): Lexicon.Ctx {
        var vowel = ctx.futureVowel
        for (c in ps) {
            if (c in Lexicon.VOWELS || c in Lexicon.CONSONANTS || c in NON_QUOTE) {
                vowel = if (c in NON_QUOTE) null else c in Lexicon.VOWELS
                break
            }
        }
        val isTo = t is Word && (t.text == "to" || t.text == "To")
        return Lexicon.Ctx(vowel, isTo)
    }

    private fun tokenize(text: String): List<Tok> {
        val toks = mutableListOf<Tok>()
        for (m in TOKEN.findAll(text)) {
            val nextCh = text.getOrNull(m.range.last + 1)
            val space = nextCh != null && (nextCh.isWhitespace() || nextCh == '/')   // "upload/submission"
            val g = m.groups
            when {
                g["time"] != null -> {
                    val (h, mm) = m.value.split(':').map { it.toInt() }
                    toks += Time(h, mm, space)
                }
                g["num"] != null -> {
                    var v = m.value
                    val cur = v.firstOrNull()?.takeIf { it in "$£€₹" }?.toString()
                    if (cur != null) v = v.drop(1)
                    val suf = Regex("(st|nd|rd|th|s|%)$").find(v)?.value ?: ""
                    toks += Num(v.dropLast(suf.length), cur, suf, space)
                }
                g["word"] != null -> toks += Word(m.value, space)
                g["sym"] != null -> toks += Word(m.value, space)
                else -> {
                    val p = m.value
                    toks += Punct(if (p == "-" || p == "–") (if (space) "—" else " ") else p, space)
                }
            }
        }
        // "Rs 500" / "500 rupees" are handled as words; "₹500" as currency.
        return toks
    }

    private fun wordPhonemes(w: String, ctx: Lexicon.Ctx, prevIsNumber: Boolean): String {
        val lw = w.lowercase().replace(".", "")
        if (prevIsNumber && (lw == "am" || lw == "pm")) return spell(lw)
        lex.word(w, null, ctx)?.let { return it }
        // Joined words like "OneDrive" / "YouTube": split at lower->Upper and read the parts.
        val parts = CAMEL.split(w).filter { it.isNotEmpty() }
        if (parts.size > 1) return parts.joinToString(" ") { lex.word(it, null, ctx) ?: LetterToSound.convert(it) }
        return LetterToSound.convert(w)
    }

    private val CAMEL = Regex("(?<=[a-z])(?=[A-Z])")

    private fun spell(letters: String): String {
        val parts = letters.mapNotNull { c -> lex.gold(c.uppercase()) as? String }
        val ps = Lexicon.applyStress(parts.joinToString(""), 0.0) ?: return letters
        val i = ps.lastIndexOf(Lexicon.SECONDARY)
        return if (i >= 0) ps.substring(0, i) + Lexicon.PRIMARY + ps.substring(i + 1) else ps
    }

    private fun words(text: String): String =
        text.split(' ', '-').filter { it.isNotBlank() && it != "and" }
            .joinToString(" ") { (lex.lookup(it, null, if (it == "point") -2.0 else null, null)?.first ?: LetterToSound.convert(it)) }

    private fun time(h: Int, m: Int): String = when {
        m == 0 -> words(Num2Words.cardinal(h.toLong()))
        m < 10 -> words(Num2Words.cardinal(h.toLong())) + " " + (lex.gold("O") as String) + " " + words(Num2Words.cardinal(m.toLong()))
        else -> words(Num2Words.cardinal(h.toLong())) + " " + words(Num2Words.cardinal(m.toLong()))
    }

    private fun number(t: Num): String {
        val raw = t.text.replace(",", "")
        val ps = when {
            t.suffix in setOf("st", "nd", "rd", "th") && raw.all { it.isDigit() } -> words(Num2Words.ordinal(raw.toLong()))
            t.currency != null -> money(raw, t.currency)
            '.' in raw -> {
                val (a, b) = raw.split('.', limit = 2)
                (if (a.isEmpty()) "" else words(Num2Words.cardinal(a.toLong())) + " ") +
                    (lex.lookup("point", null, -2.0, null)?.first ?: "pˈYnt") + " " +
                    b.map { words(Num2Words.cardinal(it.digitToInt().toLong())) }.joinToString(" ")
            }
            raw.length == 4 && t.suffix != "%" && raw.toInt() in 1100..2099 -> words(Num2Words.year(raw.toInt()))
            raw.length > 15 -> raw.map { words(Num2Words.cardinal(it.digitToInt().toLong())) }.joinToString(" ")
            else -> words(Num2Words.cardinal(raw.toLong()))
        }
        return when (t.suffix) {
            "%" -> ps + " " + (lex.lookup("percent", null, null, null)?.first ?: "")
            "s" -> lex.suffixS(ps) ?: ps
            else -> ps
        }
    }

    private fun money(raw: String, cur: String): String {
        val (unit, sub) = when (cur) {
            "$" -> "dollar" to "cent"; "£" -> "pound" to "pence"; "€" -> "euro" to "cent"; else -> "rupee" to "paisa"
        }
        val parts = raw.split('.')
        val whole = parts[0].ifEmpty { "0" }.toLong()
        val frac = parts.getOrNull(1)?.padEnd(2, '0')?.take(2)?.toLong() ?: 0L
        fun unitPs(n: Long, u: String): String {
            val base = lex.lookup(u, null, null, null)?.first ?: LetterToSound.convert(u)
            return if (n == 1L || u == "pence" || u == "paisa") base else (lex.suffixS(base) ?: base)
        }
        val out = mutableListOf<String>()
        if (whole > 0 || frac == 0L) out += words(Num2Words.cardinal(whole)) + " " + unitPs(whole, unit)
        if (frac > 0) {
            if (out.isNotEmpty()) out += (lex.lookup("and", null, null, null)?.first ?: "ænd")
            out += words(Num2Words.cardinal(frac)) + " " + unitPs(frac, sub)
        }
        return out.joinToString(" ")
    }
}

/** English number words (the subset of num2words misaki uses). */
object Num2Words {
    private val ones = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen")
    private val tens = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
    private val scales = listOf(1_000_000_000_000L to "trillion", 1_000_000_000L to "billion", 1_000_000L to "million", 1_000L to "thousand")

    fun cardinal(n: Long): String {
        if (n < 0) return "minus " + cardinal(-n)
        if (n < 20) return ones[n.toInt()]
        if (n < 100) return tens[(n / 10).toInt()] + if (n % 10 != 0L) "-" + ones[(n % 10).toInt()] else ""
        if (n < 1000) return ones[(n / 100).toInt()] + " hundred" + if (n % 100 != 0L) " and " + cardinal(n % 100) else ""
        for ((v, name) in scales) if (n >= v) {
            val rest = n % v
            return cardinal(n / v) + " " + name + when {
                rest == 0L -> ""; rest < 100 -> " and " + cardinal(rest); else -> " " + cardinal(rest)
            }
        }
        return n.toString()
    }

    fun ordinal(n: Long): String {
        val c = cardinal(n)
        val last = c.split(' ', '-').last()
        val ord = when (last) {
            "one" -> "first"; "two" -> "second"; "three" -> "third"; "five" -> "fifth"; "eight" -> "eighth"
            "nine" -> "ninth"; "twelve" -> "twelfth"
            else -> if (last.endsWith("y")) last.dropLast(1) + "ieth" else last + "th"
        }
        return c.dropLast(last.length) + ord
    }

    fun year(y: Int): String = when {
        y in 2000..2009 -> cardinal(y.toLong())
        y % 100 == 0 -> cardinal((y / 100).toLong()) + " hundred"
        y % 100 < 10 -> cardinal((y / 100).toLong()) + " oh " + cardinal((y % 100).toLong())
        else -> cardinal((y / 100).toLong()) + " " + cardinal((y % 100).toLong())
    }
}
