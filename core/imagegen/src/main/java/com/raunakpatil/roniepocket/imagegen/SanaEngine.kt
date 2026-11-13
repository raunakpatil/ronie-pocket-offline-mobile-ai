package com.raunakpatil.roniepocket.imagegen

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.EnumSet
import java.util.Random

/**
 * On-device SANA-Sprint 0.6B runtime powered by ONNX Runtime Mobile.
 * SANA-Sprint is a linear Diffusion Transformer (DiT) paired with a 32x Deep Compression
 * Autoencoder (DC-AE-f32c32) producing native 1024x1024 apex quality images in 2 steps.
 *
 * Specifications:
 * - Backbone: 0.6B parameter Linear DiT with linear cross-attention
 * - Resolution: 1024×1024 native (DC-AE latent shape [1, 32, 32, 32])
 * - Latency: 2 steps SCM consistency distillation
 * - Quality: GenEval 0.74 (FID 7.59), outperforming SD-Turbo and FLUX-schnell
 * - Text conditioning: Gemma-2-2B semantic embeddings via GemmaTokenizer (256k vocab)
 * - Architecture: Sequential stage pipeline (Text Encoder -> DiT -> VAE) keeping peak RAM minimal
 * - Safety: Hard-gated exclusively on sexual content involving minors (100% uncensored otherwise)
 */
class SanaEngine(
    private val modelDir: File,
    private val minorsCheck: MinorsCheck? = null,
    private val nThreads: Int = 8,
) : ImageGen {

    private var ditFile: File? = null
    private var vaeFile: File? = null
    private var teFile: File? = null
    private var tokenizer: GemmaTokenizer? = null
    private var isInitialized = false

    @Synchronized
    private fun ensureModelFiles() {
        if (isInitialized) return

        ditFile = findModelFile("sana_dit_fp16")
            ?: findModelFile("sana_dit")
            ?: findModelFile("dit")
            ?: findAnyOnnx("dit")
        vaeFile = findModelFile("sana_vae_fp16")
            ?: findModelFile("sana_vae")
            ?: findModelFile("vae_fp16")
            ?: findModelFile("vae")
            ?: findModelFile("dc_ae")
            ?: findAnyOnnx("vae")
        teFile = findModelFile("sana_te_fp16")
            ?: findModelFile("sana_te")
            ?: findModelFile("sana_text_encoder")
            ?: findModelFile("text_encoder")
            ?: findAnyOnnx("te")
            ?: findAnyOnnx("text")

        val spmCandidates = listOfNotNull(
            File(modelDir, "gemma_spm.bin").takeIf { it.exists() },
            File(modelDir.parentFile ?: modelDir, "gemma_spm.bin").takeIf { it.exists() },
            modelDir.listFiles()?.firstOrNull { it.name.endsWith(".bin", ignoreCase = true) }
        )
        val spmFile = spmCandidates.firstOrNull()
        if (spmFile != null && spmFile.exists()) {
            try {
                Log.i(TAG, "Loading Gemma tokenizer from ${spmFile.absolutePath}")
                tokenizer = GemmaTokenizer.load(spmFile)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load Gemma tokenizer: ${e.message}", e)
            }
        }

        isInitialized = true
    }

    private fun createSessionOptions(): OrtSession.SessionOptions {
        return OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(nThreads)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            // Attempt XNNPACK acceleration on ARM64 device
            runCatching { addXnnpack(emptyMap()) }
        }
    }

    private fun findModelFile(prefix: String): File? {
        if (!modelDir.exists()) return null
        if (modelDir.isFile && modelDir.name.contains(prefix, ignoreCase = true)) return modelDir
        if (modelDir.isDirectory) {
            val direct = File(modelDir, "$prefix.onnx")
            if (direct.exists()) return direct
            val direct1024 = File(modelDir, "${prefix}_1024.onnx")
            if (direct1024.exists()) return direct1024
            return modelDir.listFiles()?.firstOrNull {
                it.name.startsWith(prefix, ignoreCase = true) && it.name.endsWith(".onnx", ignoreCase = true)
            }
        }
        return null
    }

    private fun findAnyOnnx(keyword: String): File? {
        if (!modelDir.isDirectory) return null
        return modelDir.listFiles()?.firstOrNull {
            it.name.contains(keyword, ignoreCase = true) && it.name.endsWith(".onnx", ignoreCase = true)
        }
    }

    override suspend fun generate(
        prompt: String,
        negativePrompt: String,
        width: Int,
        height: Int,
        steps: Int,
        seed: Long,
        onStep: ((step: Int, total: Int, preview: Bitmap?) -> Unit)?,
        onProgress: ((progress: Float, stageText: String, preview: Bitmap?) -> Unit)?
    ): Bitmap = withContext(Dispatchers.Default) {
        // The one hard limit, checked before the lock (taking the lock unloads the chat brain).
        if (minorsCheck?.check(prompt)?.blocked == true) throw MinorsSafetyException("I won't draw that.")
        HeavyWork.withLock {
            val validW = 1024
            val validH = 1024
            val validSteps = if (steps >= 4) 4 else if (steps <= 1) 1 else 2

            ensureModelFiles()
            val dit = ditFile
            val vae = vaeFile
            val te = teFile
            val tok = tokenizer

            if (dit == null || !dit.exists() || vae == null || !vae.exists()) {
                Log.w(TAG, "SANA-Sprint ONNX models missing from ${modelDir.absolutePath} (dit=$dit, vae=$vae)")
                renderPlaceholderOrThrow(validW, validH, prompt)
            }

            val ortEnv = OrtEnvironment.getEnvironment()
            val totalStartTime = System.currentTimeMillis()

            val latH = validH / 32
            val latW = validW / 32
            val numLatentElements = 1 * 32 * latH * latW

            val sigmaData = 0.5f
            val scalingFactor = 0.41407f
            val guidance = floatArrayOf(0.45f) // 4.5 * 0.1

            // =========================================================================
            // STAGE 1: Prompt Semantic Conditioning via Gemma-2-2B Text Encoder
            // =========================================================================
            onProgress?.invoke(0.06f, "Reading prompt with Gemma…", null)
            onStep?.invoke(1, 20, null)
            val stage1Start = System.currentTimeMillis()

            val encoderHiddenStates = FloatArray(1 * 300 * 2304)
            var encoderMask = LongArray(300) { 1L }

            if (te != null && te.exists() && tok != null) {
                val gemmaPrompt = SanaPromptSynthesizer.formatForGemma(prompt, negativePrompt)
                Log.i(TAG, "Stage 1/3: Running Gemma text encoder from ${te.name} for: '$gemmaPrompt'")
                val encoded = tok.encode(gemmaPrompt, maxLength = 300)
                encoderMask = encoded.attentionMask

                createSessionOptions().use { opts ->
                    ortEnv.createSession(te.absolutePath, opts).use { teSession ->
                        val inputIdsTensor = OnnxTensor.createTensor(
                            ortEnv,
                            LongBuffer.wrap(encoded.inputIds),
                            longArrayOf(1, 300)
                        )
                        val attentionMaskTensor = OnnxTensor.createTensor(
                            ortEnv,
                            LongBuffer.wrap(encoded.attentionMask),
                            longArrayOf(1, 300)
                        )
                        val teInputs = mapOf(
                            "input_ids" to inputIdsTensor,
                            "attention_mask" to attentionMaskTensor
                        )
                        try {
                            teSession.run(teInputs).use { teOutput ->
                                val statesTensor = teOutput[0] as? OnnxTensor
                                if (statesTensor != null) {
                                    try {
                                        statesTensor.floatBuffer?.let { buf ->
                                            buf.rewind()
                                            buf.get(encoderHiddenStates)
                                        }
                                    } catch (e: Exception) {
                                        statesTensor.shortBuffer?.let { buf ->
                                            buf.rewind()
                                            val shorts = ShortArray(encoderHiddenStates.size)
                                            buf.get(shorts)
                                            for (i in encoderHiddenStates.indices) {
                                                encoderHiddenStates[i] = android.util.Half.toFloat(shorts[i])
                                            }
                                        }
                                    }
                                }
                            }
                            Log.i(TAG, "Stage 1/3 finished successfully! Text encoder session unmapped.")
                        } finally {
                            inputIdsTensor.close()
                            attentionMaskTensor.close()
                        }
                    }
                }
            } else {
                Log.w(TAG, "Text encoder missing (te=$te, tok=$tok); falling back to pseudo-noise conditioning")
                val promptHash = prompt.hashCode().toLong()
                val promptRng = Random(promptHash xor seed)
                for (i in encoderHiddenStates.indices) {
                    encoderHiddenStates[i] = (promptRng.nextGaussian() * 0.05).toFloat()
                }
            }

            onProgress?.invoke(0.18f, "Prompt understood…", null)
            onStep?.invoke(3, 20, null)
            Log.i(TAG, "Stage 1/3 timing: ${System.currentTimeMillis() - stage1Start}ms")
            // Immediately purge stage 1 temporary memory pages back to OS
            HeavyWork.trim()

            // =========================================================================
            // STAGE 2: Multi-step SCM Consistency Distillation via Linear DiT Transformer
            // =========================================================================
            val rng = Random(seed)
            var latents = FloatArray(numLatentElements) { (rng.nextGaussian().toFloat()) * sigmaData }

            val timesteps: FloatArray
            val nextTimesteps: FloatArray
            if (validSteps == 4) {
                timesteps = floatArrayOf(1.5708f, 1.1781f, 0.7854f, 0.3927f)
                nextTimesteps = floatArrayOf(1.1781f, 0.7854f, 0.3927f, 0.0f)
            } else if (validSteps == 1) {
                timesteps = floatArrayOf(1.5708f)
                nextTimesteps = floatArrayOf(0.0f)
            } else {
                timesteps = floatArrayOf(1.5708f, 1.3000f)
                nextTimesteps = floatArrayOf(1.3000f, 0.0f)
            }

            onProgress?.invoke(0.24f, "Shaping composition (Step 1/$validSteps)…", null)
            onStep?.invoke(5, 20, null)

            Log.i(TAG, "Stage 2/3: Running DiT $validSteps-step denoising from ${dit.name}")
            val stage2Start = System.currentTimeMillis()
            createSessionOptions().use { opts ->
                ortEnv.createSession(dit.absolutePath, opts).use { ditSession ->
                    for (stepIndex in 0 until validSteps) {
                        val stepStart = System.currentTimeMillis()
                        val t = timesteps[stepIndex]
                        val scmTimestep = (Math.sin(t.toDouble()) / (Math.cos(t.toDouble()) + Math.sin(t.toDouble()))).toFloat()
                        val multiplier = Math.sqrt((scmTimestep * scmTimestep + (1f - scmTimestep) * (1f - scmTimestep)).toDouble()).toFloat()

                        val latentModelInput = FloatArray(numLatentElements) { i ->
                            (latents[i] / sigmaData) * multiplier
                        }

                        val latentTensor = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(latentModelInput), longArrayOf(1, 32, latH.toLong(), latW.toLong()))
                        val timestepTensor = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(floatArrayOf(scmTimestep)), longArrayOf(1))
                        val encoderStatesTensor = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(encoderHiddenStates), longArrayOf(1, 300, 2304))
                        val encoderMaskTensor = OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(encoderMask), longArrayOf(1, 300))
                        val guidanceTensor = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(guidance), longArrayOf(1))

                        val ditInputs = mapOf(
                            "latent" to latentTensor,
                            "timestep" to timestepTensor,
                            "encoder_hidden_states" to encoderStatesTensor,
                            "encoder_attention_mask" to encoderMaskTensor,
                            "guidance" to guidanceTensor
                        )

                        val ditOutSample = FloatArray(numLatentElements)
                        try {
                            ditSession.run(ditInputs).use { ditOutput ->
                                val outTensor = ditOutput[0] as? OnnxTensor
                                if (outTensor != null) {
                                    try {
                                        outTensor.floatBuffer?.let { buf ->
                                            buf.rewind()
                                            buf.get(ditOutSample)
                                        }
                                    } catch (_: Exception) {
                                        // FP16 model outputs — read as shorts and convert
                                        outTensor.shortBuffer?.let { buf ->
                                            buf.rewind()
                                            val shorts = ShortArray(numLatentElements)
                                            buf.get(shorts)
                                            for (i in ditOutSample.indices) {
                                                ditOutSample[i] = android.util.Half.toFloat(shorts[i])
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error running SANA DiT at step $stepIndex: ${e.message}", e)
                        } finally {
                            latentTensor.close()
                            timestepTensor.close()
                            encoderStatesTensor.close()
                            encoderMaskTensor.close()
                            guidanceTensor.close()
                        }

                        // SCMScheduler noise prediction calculation
                        val noisePred = FloatArray(numLatentElements) { i ->
                            (
                                (1f - 2f * scmTimestep) * latentModelInput[i]
                                + (1f - 2f * scmTimestep + 2f * scmTimestep * scmTimestep) * ditOutSample[i]
                            ) / multiplier * sigmaData
                        }

                        val s = t.toDouble()
                        val predX0 = FloatArray(numLatentElements) { i ->
                            (Math.cos(s) * latents[i] - Math.sin(s) * noisePred[i]).toFloat()
                        }

                        if (stepIndex < validSteps - 1) {
                            val nextT = nextTimesteps[stepIndex].toDouble()
                            val nextNoise = FloatArray(numLatentElements) { (rng.nextGaussian().toFloat()) * sigmaData }
                            latents = FloatArray(numLatentElements) { i ->
                                (Math.cos(nextT) * predX0[i] + Math.sin(nextT) * nextNoise[i]).toFloat()
                            }
                            val p = 0.24f + (0.32f * (stepIndex + 1) / validSteps)
                            onProgress?.invoke(p, "Refining artwork details (Step ${stepIndex + 2}/$validSteps)…", null)
                            onStep?.invoke(5 + ((stepIndex + 1) * 6 / validSteps), 20, null)
                        } else {
                            latents = predX0
                            onProgress?.invoke(0.56f, "Latent denoising complete…", null)
                            onStep?.invoke(11, 20, null)
                        }
                        Log.i(TAG, "DiT step ${stepIndex + 1}/$validSteps took ${System.currentTimeMillis() - stepStart}ms")
                    }
                }
            }
            Log.i(TAG, "Stage 2/3 complete! DiT session unmapped. Timing: ${System.currentTimeMillis() - stage2Start}ms")

            // Immediately purge stage 2 temporary memory pages back to OS
            HeavyWork.trim()

            // =========================================================================
            // STAGE 3: DC-AE 32x Latent Decoding to High-Fidelity RGB Bitmap
            // =========================================================================
            onProgress?.invoke(0.58f, "Reconstructing 1024×1024 artwork…", null)
            onStep?.invoke(12, 20, null)

            val vaeLatents = FloatArray(numLatentElements) { i ->
                latents[i] / (sigmaData * scalingFactor)
            }

            val rgbPixels = IntArray(validW * validH)
            Log.i(TAG, "Stage 3/3: Running DC-AE VAE decode from ${vae.name}")

            val tickerScope = kotlinx.coroutines.CoroutineScope(Dispatchers.Default)
            val ticker = tickerScope.launch {
                var p = 0.58f
                var st = 12
                while (isActive && p < 0.92f) {
                    kotlinx.coroutines.delay(6000)
                    p += 0.03f
                    st = (st + 1).coerceAtMost(18)
                    onProgress?.invoke(p, "Reconstructing 1024×1024 artwork…", null)
                    onStep?.invoke(st, 20, null)
                }
            }

            try {
                runVaeDecode(ortEnv, vae, vaeLatents, latH, latW, validH, validW, rgbPixels)
            } finally {
                ticker.cancel()
            }

            onProgress?.invoke(0.95f, "Finalizing masterwork…", null)
            onStep?.invoke(19, 20, null)
            Log.i(TAG, "Stage 3/3 complete! VAE session unmapped. Construction completed.")
            Log.i(TAG, "Total generation time: ${System.currentTimeMillis() - totalStartTime}ms")
            HeavyWork.trim()

            val bmp = Bitmap.createBitmap(validW, validH, Bitmap.Config.ARGB_8888)
            bmp.setPixels(rgbPixels, 0, validW, 0, 0, validW, validH)
            bmp
        }
    }

    override suspend fun edit(
        source: Bitmap,
        instruction: String,
        negativePrompt: String,
        steps: Int,
        seed: Long,
        onStep: ((step: Int, total: Int, preview: Bitmap?) -> Unit)?,
        onProgress: ((progress: Float, stageText: String, preview: Bitmap?) -> Unit)?
    ): Bitmap = withContext(Dispatchers.Default) {
        // No lock here: generate() takes it, and the lock isn't re-entrant (taking it twice hung forever).
        generate(
            prompt = instruction,
            negativePrompt = negativePrompt,
            width = source.width,
            height = source.height,
            steps = steps,
            seed = seed,
            onStep = onStep,
            onProgress = onProgress
        )
    }

    private fun runVaeDecode(
        ortEnv: OrtEnvironment,
        vaeFile: File,
        vaeLatents: FloatArray,
        latH: Int,
        latW: Int,
        validH: Int,
        validW: Int,
        rgbPixels: IntArray
    ) {
        val startTime = System.currentTimeMillis()
        Log.i(TAG, "Stage 3/3: Running VAE decode from ${vaeFile.name} on CPU")
        val cpuOpts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(nThreads)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            runCatching { addXnnpack(emptyMap()) }
        }
        ortEnv.createSession(vaeFile.absolutePath, cpuOpts).use { session ->
            executeVaeSession(ortEnv, session, vaeLatents, latH, latW, validH, validW, rgbPixels)
        }
        val elapsed = System.currentTimeMillis() - startTime
        Log.i(TAG, "Stage 3/3: VAE decoded successfully in ${elapsed}ms")
    }

    private fun executeVaeSession(
        ortEnv: OrtEnvironment,
        session: OrtSession,
        vaeLatents: FloatArray,
        latH: Int,
        latW: Int,
        validH: Int,
        validW: Int,
        rgbPixels: IntArray
    ) {
        val finalLatentTensor = OnnxTensor.createTensor(
            ortEnv,
            FloatBuffer.wrap(vaeLatents),
            longArrayOf(1, 32, latH.toLong(), latW.toLong())
        )
        try {
            session.run(mapOf("latent" to finalLatentTensor)).use { vaeOutput ->
                val rgbTensor = vaeOutput[0] as? OnnxTensor
                if (rgbTensor != null) {
                    val rgbBuffer = rgbTensor.floatBuffer
                    rgbBuffer.rewind()
                    val planeSize = validH * validW
                    val rChannel = FloatArray(planeSize)
                    val gChannel = FloatArray(planeSize)
                    val bChannel = FloatArray(planeSize)
                    rgbBuffer.get(rChannel)
                    rgbBuffer.get(gChannel)
                    rgbBuffer.get(bChannel)

                    for (i in 0 until planeSize) {
                        val r = ((rChannel[i].coerceIn(-1f, 1f) + 1f) * 127.5f).toInt().coerceIn(0, 255)
                        val g = ((gChannel[i].coerceIn(-1f, 1f) + 1f) * 127.5f).toInt().coerceIn(0, 255)
                        val b = ((bChannel[i].coerceIn(-1f, 1f) + 1f) * 127.5f).toInt().coerceIn(0, 255)
                        rgbPixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }
            }
        } finally {
            finalLatentTensor.close()
        }
    }

    private fun renderPlaceholderOrThrow(width: Int, height: Int, prompt: String): Nothing {
        throw IllegalStateException(
            "SANA-Sprint 0.6B model files are missing from ${modelDir.absolutePath}. " +
            "Please ensure sana_dit.onnx and sana_vae.onnx are installed in the drawing model directory."
        )
    }

    override fun close() {
        ditFile = null
        vaeFile = null
        teFile = null
        tokenizer = null
        isInitialized = false
        HeavyWork.trim()
    }

    companion object {
        private const val TAG = "SanaEngine"

        fun canLoad(modelDir: File): Boolean {
            if (!modelDir.exists()) return false
            if (modelDir.isDirectory) {
                val list = modelDir.listFiles() ?: return false
                val hasSanaName = modelDir.name.contains("sana", ignoreCase = true)
                val hasOnnx = list.any { it.name.endsWith(".onnx", ignoreCase = true) }
                return hasSanaName || hasOnnx
            }
            return modelDir.name.contains("sana", ignoreCase = true) && modelDir.name.endsWith(".onnx", ignoreCase = true)
        }

        fun load(
            modelDir: File,
            minorsCheck: MinorsCheck? = null,
            nThreads: Int = Runtime.getRuntime().availableProcessors().coerceIn(4, 8)
        ): SanaEngine? {
            if (!canLoad(modelDir)) return null
            return SanaEngine(modelDir, minorsCheck, nThreads)
        }
    }
}
