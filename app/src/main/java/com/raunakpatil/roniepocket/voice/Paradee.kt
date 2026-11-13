package com.raunakpatil.roniepocket.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import org.json.JSONObject
import java.nio.LongBuffer

/**
 * Paradee-8M v1.2 (Apache-2.0), a Kokoro-distilled TTS, int8 ONNX. Inputs: phoneme ids, speed, voice.
 * Output: 24 kHz mono float audio. Ronie always uses `am_puck` (index read from the config).
 */
class Paradee private constructor(
    private val session: OrtSession,
    private val vocab: Map<Char, Long>,
    private val voice: Long,
) : AutoCloseable {
    companion object {
        const val SAMPLE_RATE = 24_000
        private const val MAX_IDS = 510
        private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

        fun load(ctx: Context, voiceName: String = "am_puck"): Paradee {
            val cfg = JSONObject(ctx.assets.open("voice/paradee_config.json").bufferedReader().readText())
            val v = cfg.getJSONObject("vocab")
            val vocab = HashMap<Char, Long>()
            for (k in v.keys()) if (k.length == 1) vocab[k[0]] = v.getLong(k)
            val voices = cfg.getJSONArray("voices")
            val idx = (0 until voices.length()).first { voices.getString(it) == voiceName }.toLong()
            val bytes = ctx.assets.open("voice/paradee_int8.onnx").use { it.readBytes() }
            val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
            return Paradee(env.createSession(bytes, opts), vocab, idx)
        }
    }

    /** Speaks phonemes; long input is split at spaces so each piece stays under the model's limit. */
    fun synth(phonemes: String, speed: Float): FloatArray {
        val pieces = mutableListOf<String>()
        var rest = phonemes
        while (rest.length > MAX_IDS) {
            val cut = rest.lastIndexOf(' ', MAX_IDS).takeIf { it > 0 } ?: MAX_IDS
            pieces += rest.substring(0, cut); rest = rest.substring(cut).trimStart()
        }
        if (rest.isNotBlank()) pieces += rest
        return pieces.map { synthOne(it, speed) }.fold(FloatArray(0)) { a, b -> a + b }
    }

    private fun synthOne(ps: String, speed: Float): FloatArray {
        val ids = LongArray(ps.length + 2)
        var n = 1
        for (c in ps) vocab[c]?.let { ids[n++] = it }   // unknown characters are dropped
        val seq = ids.copyOf(n + 1)                      // 0 = pad at both ends
        OnnxTensor.createTensor(env, LongBuffer.wrap(seq), longArrayOf(1, seq.size.toLong())).use { tIds ->
            OnnxTensor.createTensor(env, floatArrayOf(speed)).use { tSpeed ->
                OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(voice)), longArrayOf(1)).use { tVoice ->
                    session.run(mapOf("input_ids" to tIds, "speed" to tSpeed, "voice" to tVoice)).use { out ->
                        @Suppress("UNCHECKED_CAST")
                        val w = out[0].value
                        return when (w) {
                            is Array<*> -> (w[0] as FloatArray)
                            is FloatArray -> w
                            else -> FloatArray(0)
                        }
                    }
                }
            }
        }
    }

    override fun close() = session.close()
}
