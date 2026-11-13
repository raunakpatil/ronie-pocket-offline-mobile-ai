package com.raunakpatil.roniepocket

import android.content.Context
import com.raunakpatil.roniepocket.engine.LlamaEngine
import com.raunakpatil.roniepocket.models.Tier
import java.io.File

/**
 * The first Brain-print slider (Phase 3.5): Clean <-> Sweary.
 *
 * A steering direction trained on the PC (eval/steering, scripts/train-steering.cmd) is added
 * inside the model while it writes. The slider's position (-1..1) scales it; 0 means off.
 * Directions are model-specific, so the slider only exists for tiers that ship one.
 */
object Tone {
    data class Direction(val asset: String, val layerStart: Int, val layerEnd: Int, val maxStrength: Float)

    private val directions = mapOf(
        Tier.FULL to Direction("steering/swear-gemma4e2b.gguf", layerStart = 10, layerEnd = 30, maxStrength = 1.0f),
        // PC sweep (eval/steering/eval_steer.py): +1.0 keeps accuracy (94%); +2.0 drops it to 75%, +4.0 to 31%.
    )

    fun available(tier: Tier) = tier in directions

    fun saved(ctx: Context): Float = ctx.getSharedPreferences("ronie_tone", 0).getFloat("swear", 0f)

    fun save(ctx: Context, v: Float) = ctx.getSharedPreferences("ronie_tone", 0).edit().putFloat("swear", v).apply()

    /** Copies the tier's direction out of the APK (the engine needs a file path) and loads it. */
    suspend fun load(ctx: Context, tier: Tier, engine: LlamaEngine): Boolean {
        val d = directions[tier] ?: return false
        val file = File(ctx.filesDir, d.asset)
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            ctx.assets.open(d.asset).use { input -> file.outputStream().use { input.copyTo(it) } }
        }
        return engine.loadSteering(file.absolutePath) > 0
    }

    suspend fun apply(tier: Tier, engine: LlamaEngine, v: Float): Boolean {
        val d = directions[tier] ?: return false
        return engine.setSteering(v.coerceIn(-1f, 1f) * d.maxStrength, d.layerStart, d.layerEnd)
    }
}
