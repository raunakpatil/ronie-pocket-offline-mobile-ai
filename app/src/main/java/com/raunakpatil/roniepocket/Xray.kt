package com.raunakpatil.roniepocket

import android.content.Context
import com.raunakpatil.roniepocket.engine.LlamaEngine
import com.raunakpatil.roniepocket.models.Tier
import org.json.JSONObject
import kotlin.math.exp

/**
 * X-ray Mode v0 (Phase 3.5): a linear probe on one of Gemma's layers, trained on the PC/Modal
 * (eval/xray), that scores whether Ronie's words match what he was given or are made up.
 * Each sentence is coloured by the average score of its tokens.
 */
object Xray {
    data class Probe(val layer: Int, val weights: FloatArray, val bias: Float, val receipt: String)

    /** One sentence of a reply with its probe score (logit; > 0 = grounded). */
    data class Segment(val text: String, val score: Float?)

    private val assets = mapOf(Tier.FULL to "xray/probe-gemma4e2b.json")

    fun available(ctx: Context, tier: Tier): Boolean =
        assets[tier]?.let { a -> runCatching { ctx.assets.open(a).close() }.isSuccess } ?: false

    fun load(ctx: Context, tier: Tier): Probe? = runCatching {
        val o = JSONObject(ctx.assets.open(assets.getValue(tier)).bufferedReader().readText())
        val w = o.getJSONArray("weights")
        val r = o.getJSONObject("receipt")
        Probe(
            layer = o.getInt("layer"),
            weights = FloatArray(w.length()) { w.getDouble(it).toFloat() },
            bias = o.getDouble("bias").toFloat(),
            receipt = "Lab test: AUC ${"%.2f".format(r.getDouble("test_auc"))} on test answers about short notes " +
                "(1.0 = perfect, 0.5 = guessing). Real-world accuracy isn't measured yet, so treat colours as a hint. " +
                "Only meaningful when you give Ronie text to work from.",
        )
    }.onFailure { android.util.Log.e("Ronie", "X-ray probe failed to load", it) }.getOrNull()

    suspend fun enable(engine: LlamaEngine, p: Probe?) =
        if (p == null) engine.setProbe(0, null, 0f) else engine.setProbe(p.layer, p.weights, p.bias)

    fun probability(score: Float) = 1f / (1f + exp(-score))

    /** Builds sentence segments while streaming: text chunks with the score of their token. */
    class Collector {
        private val done = mutableListOf<Segment>()
        private val cur = StringBuilder()
        private var sum = 0f
        private var n = 0

        fun add(text: String, score: Float) {
            cur.append(text)
            if (!score.isNaN()) { sum += score; n++ }
            if (text.any { it == '.' || it == '!' || it == '?' || it == '\n' }) close()
        }

        private fun close() {
            if (cur.isNotBlank()) done += Segment(cur.toString(), if (n > 0) sum / n else null)
            else if (cur.isNotEmpty()) done += Segment(cur.toString(), null)
            cur.setLength(0); sum = 0f; n = 0
        }

        fun segments(): List<Segment> =
            done + if (cur.isNotEmpty()) listOf(Segment(cur.toString(), if (n > 0) sum / n else null)) else emptyList()
    }
}
