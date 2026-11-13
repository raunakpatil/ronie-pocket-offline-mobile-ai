package com.raunakpatil.roniepocket.imagegen

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.PriorityQueue

/**
 * High-performance, zero-dependency SentencePiece BPE tokenizer for Gemma (256,000 vocab).
 * Loads directly from the precompiled binary file `gemma_spm.bin`.
 *
 * Implements exact SentencePiece BPE algorithm matching HuggingFace GemmaTokenizerFast.
 */
class GemmaTokenizer private constructor(
    private val pieceToId: Map<String, Int>,
    private val scores: FloatArray
) {
    data class EncodedPrompt(
        val inputIds: LongArray,
        val attentionMask: LongArray
    )

    private data class Bigram(
        val left: Int,
        val right: Int,
        val score: Float,
        val length: Int
    )

    /**
     * Tokenizes [prompt] with maximum length 300, prepending <bos> (token 2) and right-padding with 0.
     * Matches diffusers SanaPipeline prompt encoding conditioning requirements.
     */
    fun encode(prompt: String, maxLength: Int = 300): EncodedPrompt {
        val inputIds = LongArray(maxLength)
        val attentionMask = LongArray(maxLength)

        val cleaned = prompt.lowercase().trim()
        if (cleaned.isEmpty()) {
            inputIds[0] = BOS_TOKEN_ID
            attentionMask[0] = 1L
            return EncodedPrompt(inputIds, attentionMask)
        }

        // Replace spaces with SPM space symbol U+2581 (no leading dummy prefix for Gemma)
        val spmText = cleaned.replace(" ", "\u2581")

        // Split text into unicode code points
        val symbols = ArrayList<String>(spmText.length)
        var offset = 0
        while (offset < spmText.length) {
            val cp = spmText.codePointAt(offset)
            val charCount = Character.charCount(cp)
            symbols.add(spmText.substring(offset, offset + charCount))
            offset += charCount
        }

        val n = symbols.size
        val prev = IntArray(n) { it - 1 }
        val next = IntArray(n) { if (it == n - 1) -1 else it + 1 }

        val comparator = Comparator<Bigram> { a, b ->
            val sc = b.score.compareTo(a.score)
            if (sc != 0) sc else a.left.compareTo(b.left)
        }
        val pq = PriorityQueue(comparator)

        fun tryAdd(left: Int, right: Int) {
            if (left < 0 || right < 0) return
            val bigram = symbols[left] + symbols[right]
            val id = pieceToId[bigram] ?: return
            pq.add(Bigram(left, right, scores[id], bigram.length))
        }

        for (i in 0 until n - 1) {
            tryAdd(i, i + 1)
        }

        while (!pq.isEmpty()) {
            val top = pq.poll() ?: break
            val leftSym = symbols[top.left]
            val rightSym = symbols[top.right]
            if (leftSym.isEmpty() || rightSym.isEmpty()) continue
            if (leftSym.length + rightSym.length != top.length) continue

            // Merge right into left
            symbols[top.left] = leftSym + rightSym
            symbols[top.right] = ""
            val nextRight = next[top.right]
            next[top.left] = nextRight
            if (nextRight >= 0) {
                prev[nextRight] = top.left
            }

            tryAdd(prev[top.left], top.left)
            tryAdd(top.left, next[top.left])
        }

        // Build token ID sequence: <bos> (token 2) followed by encoded pieces
        val tokens = ArrayList<Long>()
        tokens.add(BOS_TOKEN_ID)

        var idx = 0
        while (idx >= 0) {
            val piece = symbols[idx]
            if (piece.isNotEmpty()) {
                val id = pieceToId[piece]
                if (id != null) {
                    tokens.add(id.toLong())
                } else {
                    for (i in 0 until piece.length) {
                        val ch = piece[i].toString()
                        val chId = pieceToId[ch] ?: UNK_TOKEN_ID
                        tokens.add(chId.toLong())
                    }
                }
            }
            idx = next[idx]
        }

        val count = minOf(tokens.size, maxLength)
        for (i in 0 until count) {
            inputIds[i] = tokens[i]
            attentionMask[i] = 1L
        }

        return EncodedPrompt(inputIds, attentionMask)
    }

    companion object {
        const val PAD_TOKEN_ID = 0L
        const val EOS_TOKEN_ID = 1L
        const val BOS_TOKEN_ID = 2L
        const val UNK_TOKEN_ID = 3L

        fun load(stream: InputStream): GemmaTokenizer {
            BufferedInputStream(stream).use { bis ->
                val header = ByteArray(8)
                val readHeader = bis.read(header)
                if (readHeader < 8) throw IllegalArgumentException("Invalid SPM1 file: truncated header")

                if (header[0] != 'S'.code.toByte() || header[1] != 'P'.code.toByte() ||
                    header[2] != 'M'.code.toByte() || header[3] != '1'.code.toByte()
                ) {
                    throw IllegalArgumentException("Invalid SPM1 magic header: ${header.take(4).map { it.toInt().toChar() }}")
                }

                val byteBuf = ByteBuffer.wrap(header, 4, 4).order(ByteOrder.LITTLE_ENDIAN)
                val pieceCount = byteBuf.int

                val pieceMap = HashMap<String, Int>(pieceCount * 4 / 3 + 1)
                val scores = FloatArray(pieceCount)

                val dis = DataInputStream(bis)
                val lengthBuf = ByteArray(2)
                val scoreBuf = ByteArray(4)

                for (i in 0 until pieceCount) {
                    dis.readFully(lengthBuf)
                    val len = (lengthBuf[0].toInt() and 0xFF) or ((lengthBuf[1].toInt() and 0xFF) shl 8)
                    val strBytes = ByteArray(len)
                    dis.readFully(strBytes)
                    dis.readFully(scoreBuf)

                    val pScore = ByteBuffer.wrap(scoreBuf).order(ByteOrder.LITTLE_ENDIAN).float
                    val pieceStr = String(strBytes, Charsets.UTF_8)

                    pieceMap[pieceStr] = i
                    scores[i] = pScore
                }

                return GemmaTokenizer(pieceMap, scores)
            }
        }

        fun load(file: File): GemmaTokenizer {
            if (!file.exists()) throw IllegalArgumentException("Gemma SPM file not found: ${file.absolutePath}")
            return file.inputStream().use { load(it) }
        }
    }
}
