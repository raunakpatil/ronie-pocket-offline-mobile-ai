package com.raunakpatil.roniepocket.memory

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONObject
import java.io.InputStream
import java.nio.LongBuffer
import java.text.Normalizer

/**
 * BERT WordPiece tokenizer, matching Hugging Face's for bge-small-en-v1.5 (lowercase, accents stripped,
 * punctuation split, CJK characters spaced, greedy longest-match sub-words, [CLS] … [SEP]).
 * Checked token-for-token against the official tokenizer (MemoryTokenizerTest).
 */
class WordPiece(tokenizerJson: String) {
    private val vocab: Map<String, Int>
    private val unk: Int
    private val cls: Int
    private val sep: Int

    init {
        val v = JSONObject(tokenizerJson).getJSONObject("model").getJSONObject("vocab")
        vocab = HashMap<String, Int>(v.length() * 2).apply { v.keys().forEach { put(it, v.getInt(it)) } }
        unk = vocab.getValue("[UNK]"); cls = vocab.getValue("[CLS]"); sep = vocab.getValue("[SEP]")
    }

    fun encode(text: String, maxLen: Int = 512): IntArray {
        val ids = ArrayList<Int>()
        ids += cls
        for (word in preTokenize(normalize(text))) {
            wordPiece(word, ids)
            if (ids.size >= maxLen - 1) break
        }
        while (ids.size > maxLen - 1) ids.removeAt(ids.lastIndex)
        ids += sep
        return ids.toIntArray()
    }

    private fun normalize(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i); i += Character.charCount(cp)
            // clean_text: drop NUL / U+FFFD / control chars, turn whitespace into a plain space
            if (cp == 0 || cp == 0xFFFD || (isControl(cp))) continue
            if (isWhitespace(cp)) { sb.append(' '); continue }
            // handle_chinese_chars: surround CJK ideographs with spaces
            if (isCjk(cp)) { sb.append(' ').appendCodePoint(cp).append(' '); continue }
            sb.appendCodePoint(cp)
        }
        // lowercase + strip accents (strip_accents defaults to lowercase for BERT)
        val lower = sb.toString().lowercase()
        val nfd = Normalizer.normalize(lower, Normalizer.Form.NFD)
        val out = StringBuilder(nfd.length)
        var j = 0
        while (j < nfd.length) {
            val cp = nfd.codePointAt(j); j += Character.charCount(cp)
            if (Character.getType(cp) == Character.NON_SPACING_MARK.toInt()) continue
            out.appendCodePoint(cp)
        }
        return out.toString()
    }

    /** Split on whitespace; every punctuation character becomes its own token. */
    private fun preTokenize(s: String): List<String> {
        val words = ArrayList<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i); i += Character.charCount(cp)
            when {
                isWhitespace(cp) -> { if (cur.isNotEmpty()) { words += cur.toString(); cur.setLength(0) } }
                isPunct(cp) -> {
                    if (cur.isNotEmpty()) { words += cur.toString(); cur.setLength(0) }
                    words += String(Character.toChars(cp))
                }
                else -> cur.appendCodePoint(cp)
            }
        }
        if (cur.isNotEmpty()) words += cur.toString()
        return words
    }

    private fun wordPiece(word: String, out: MutableList<Int>) {
        if (word.codePointCount(0, word.length) > 100) { out += unk; return }
        val pieces = ArrayList<Int>()
        var start = 0
        while (start < word.length) {
            var end = word.length
            var found = -1
            while (start < end) {
                val sub = (if (start > 0) "##" else "") + word.substring(start, end)
                val id = vocab[sub]
                if (id != null) { found = id; break }
                end = word.offsetByCodePoints(end, -1)
            }
            if (found < 0) { out += unk; return }
            pieces += found
            start = end
        }
        out += pieces
    }

    private fun isWhitespace(cp: Int) = cp == ' '.code || cp == '\t'.code || cp == '\n'.code || cp == '\r'.code ||
        Character.getType(cp) == Character.SPACE_SEPARATOR.toInt()

    private fun isControl(cp: Int): Boolean {
        if (cp == '\t'.code || cp == '\n'.code || cp == '\r'.code) return false
        val t = Character.getType(cp)
        return t == Character.CONTROL.toInt() || t == Character.FORMAT.toInt() ||
            t == Character.PRIVATE_USE.toInt() || t == Character.UNASSIGNED.toInt() || t == Character.SURROGATE.toInt()
    }

    private fun isPunct(cp: Int): Boolean {
        if (cp in 33..47 || cp in 58..64 || cp in 91..96 || cp in 123..126) return true
        return when (Character.getType(cp)) {
            Character.CONNECTOR_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(), Character.START_PUNCTUATION.toInt(),
            Character.END_PUNCTUATION.toInt(), Character.INITIAL_QUOTE_PUNCTUATION.toInt(), Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt() -> true
            else -> false
        }
    }

    private fun isCjk(cp: Int) = cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF || cp in 0x20000..0x2A6DF || cp in 0x2A700..0x2B73F ||
        cp in 0x2B740..0x2B81F || cp in 0x2B820..0x2CEAF || cp in 0xF900..0xFAFF || cp in 0x2F800..0x2FA1F
}

/**
 * Turns text into a 384-number vector with bge-small-en-v1.5 (int8 ONNX, in the app, ~10 ms on the phone).
 * Questions get bge's retrieval prefix; stored facts don't. Vectors are unit length, so a dot product is the
 * cosine similarity. Chosen in the Phase 6 bake-off (eval/memory/embed_bakeoff.py).
 */
class Embedder(model: ByteArray, tokenizerJson: String) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(model, OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) })
    private val tok = WordPiece(tokenizerJson)
    private val hasTypeIds = "token_type_ids" in session.inputNames

    companion object {
        const val MODEL_ID = "bge-small-en-v1.5-int8"
        const val DIM = 384
        const val QUERY_PREFIX = "Represent this sentence for searching relevant passages: "

        fun load(open: (String) -> InputStream): Embedder =
            Embedder(open("memory/bge-small-int8.onnx").use { it.readBytes() },
                open("memory/bge-tokenizer.json").use { it.bufferedReader().readText() })
    }

    /** A stored fact / message. */
    fun embedDoc(text: String) = embed(text)

    /** Something to search with (gets the retrieval prefix). */
    fun embedQuery(text: String) = embed(QUERY_PREFIX + text)

    @Synchronized
    fun embed(text: String): FloatArray {
        val ids = tok.encode(text).map { it.toLong() }.toLongArray()
        val shape = longArrayOf(1, ids.size.toLong())
        val inputs = HashMap<String, OnnxTensor>()
        inputs["input_ids"] = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape)
        inputs["attention_mask"] = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(ids.size) { 1 }), shape)
        if (hasTypeIds) inputs["token_type_ids"] = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(ids.size)), shape)
        try {
            session.run(inputs).use { res ->
                @Suppress("UNCHECKED_CAST")
                val hidden = (res[0].value as Array<Array<FloatArray>>)[0]
                val v = hidden[0].copyOf()                    // [CLS] pooling
                var n = 0.0
                for (x in v) n += x * x
                val inv = (1.0 / (Math.sqrt(n) + 1e-9)).toFloat()
                for (k in v.indices) v[k] *= inv
                return v
            }
        } finally { inputs.values.forEach { it.close() } }
    }

    override fun close() { session.close() }
}

/** Cosine similarity of two unit vectors. */
fun dot(a: FloatArray, b: FloatArray): Float {
    var s = 0f
    for (i in a.indices) s += a[i] * b[i]
    return s
}
