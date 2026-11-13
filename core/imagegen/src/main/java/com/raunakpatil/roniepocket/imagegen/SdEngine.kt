package com.raunakpatil.roniepocket.imagegen

import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * On-device Stable Diffusion runtime powered by stable-diffusion.cpp with Vulkan GPU
 * acceleration on Mali-G715 and ARM NEON CPU fallback.
 * Uses SD-Turbo 4-step distilled diffusion at 512x512 native resolution.
 */
class SdEngine(
    private val modelFile: File,
    private val taesdFile: File? = null,
    private val minorsCheck: MinorsCheck? = null,
    private val nThreads: Int = 4,
    private val useVulkan: Boolean = true
) : ImageGen {

    private val ctxHandle = AtomicLong(0L)

    private fun ensureContext(): Long {
        val current = ctxHandle.get()
        if (current != 0L) return current
        synchronized(this) {
            if (ctxHandle.get() == 0L) {
                if (!SdNative.isAvailable) {
                    throw IllegalStateException("SdNative libraries not loaded")
                }
                val taePath = taesdFile?.takeIf { it.exists() }?.absolutePath
                Log.i(TAG, "Initializing SD context with model: ${modelFile.absolutePath}, taesd: $taePath")
                val ptr = SdNative.initContext(modelFile.absolutePath, taePath, nThreads, useVulkan)
                if (ptr == 0L) {
                    throw IllegalStateException("Failed to initialize SD context from ${modelFile.absolutePath}")
                }
                ctxHandle.set(ptr)
            }
        }
        return ctxHandle.get()
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
        // Checked before the lock: taking it unloads the chat brain, which a refused prompt shouldn't cost.
        if (minorsCheck?.check(prompt)?.blocked == true) throw MinorsSafetyException("I won't draw that.")
        HeavyWork.withLock {
            // SD-Turbo optimal resolution is 512x512; must be multiple of 64
            val validW = ((width.coerceIn(256, 1024)) / 64) * 64
            val validH = ((height.coerceIn(256, 1024)) / 64) * 64
            // SD-Turbo operates in 1 to 4 steps with Karras schedule; default is 3
            val validSteps = steps.coerceIn(1, 8)
            val cfg = 1.0f
            val negPrompt = if (negativePrompt.isNotBlank()) negativePrompt
            else "blurry, low quality, distorted, deformed, bad anatomy, artifacts"

            val ptr = ensureContext()
            onStep?.invoke(0, validSteps, null)

            val nativeCallback = if (onStep != null) {
                SdNative.StepCallback { step, total, pixels, w, h ->
                    val preview = if (pixels != null && w > 0 && h > 0) {
                        runCatching {
                            Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
                        }.getOrNull()
                    } else null
                    onStep.invoke(step, total, preview)
                }
            } else null

            val startTime = System.currentTimeMillis()
            val sampleMethod = SdNative.SAMPLER_EULER_A
            val pixels = SdNative.generate(
                ctxHandle = ptr,
                prompt = prompt,
                negativePrompt = negPrompt,
                width = validW,
                height = validH,
                steps = validSteps,
                cfgScale = cfg,
                sampleMethod = sampleMethod,
                scheduler = SdNative.SCHEDULER_SGM_UNIFORM,   // at 3 steps "discrete" stops early and leaves noise in the picture (eval/imagegen/sampler_ab.py)
                seed = seed,
                callback = nativeCallback
            ) ?: throw RuntimeException("SD generation returned null pixels")

            val durationMs = System.currentTimeMillis() - startTime
            Log.i(TAG, "SD generated ${validW}x${validH} in ${durationMs}ms ($validSteps steps)")

            val bmp = Bitmap.createBitmap(pixels, validW, validH, Bitmap.Config.ARGB_8888)
            onStep?.invoke(validSteps, validSteps, bmp)
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
        // Checked before the lock: taking it unloads the chat brain, which a refused prompt shouldn't cost.
        if (minorsCheck?.check(instruction)?.blocked == true) throw MinorsSafetyException("I won't draw that.")
        HeavyWork.withLock {
            val validW = ((source.width.coerceIn(256, 1024)) / 64) * 64
            val validH = ((source.height.coerceIn(256, 1024)) / 64) * 64
            val scaledSource = if (source.width != validW || source.height != validH) {
                Bitmap.createScaledBitmap(source, validW, validH, true)
            } else {
                source
            }

            val inPixels = IntArray(validW * validH)
            scaledSource.getPixels(inPixels, 0, validW, 0, 0, validW, validH)

            val validSteps = steps.coerceIn(1, 8)
            val cfg = 1.0f
            val strength = 0.7f
            val negPrompt = if (negativePrompt.isNotBlank()) negativePrompt
            else "blurry, low quality, distorted, deformed, bad anatomy, artifacts"

            val ptr = ensureContext()
            onStep?.invoke(0, validSteps, null)

            val nativeCallback = if (onStep != null) {
                SdNative.StepCallback { step, total, pixels, w, h ->
                    val preview = if (pixels != null && w > 0 && h > 0) {
                        runCatching {
                            Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
                        }.getOrNull()
                    } else null
                    onStep.invoke(step, total, preview)
                }
            } else null

            val startTime = System.currentTimeMillis()
            val sampleMethod = SdNative.SAMPLER_EULER_A
            val outPixels = SdNative.edit(
                ctxHandle = ptr,
                initPixels = inPixels,
                initWidth = validW,
                initHeight = validH,
                prompt = instruction,
                negativePrompt = negPrompt,
                steps = validSteps,
                cfgScale = cfg,
                sampleMethod = sampleMethod,
                scheduler = SdNative.SCHEDULER_SGM_UNIFORM,   // at 3 steps "discrete" stops early and leaves noise in the picture (eval/imagegen/sampler_ab.py)
                strength = strength,
                seed = seed,
                callback = nativeCallback
            ) ?: throw RuntimeException("SD edit returned null pixels")

            val durationMs = System.currentTimeMillis() - startTime
            Log.i(TAG, "SD edited ${validW}x${validH} in ${durationMs}ms ($validSteps steps)")

            val bmp = Bitmap.createBitmap(outPixels, validW, validH, Bitmap.Config.ARGB_8888)
            onStep?.invoke(validSteps, validSteps, bmp)
            bmp
        }
    }

    override fun close() {
        val ptr = ctxHandle.getAndSet(0L)
        if (ptr != 0L) {
            Log.i(TAG, "Closing SD context: $ptr")
            SdNative.freeContext(ptr)
        }
    }

    companion object {
        private const val TAG = "RonieImageGen"

        fun load(modelFileOrDir: File, minorsCheck: MinorsCheck? = null): ImageGen? {
            if (!SdNative.isAvailable) {
                Log.w(TAG, "SdNative is not available; cannot load SdEngine")
                return null
            }
            val file = if (modelFileOrDir.isDirectory) {
                File(modelFileOrDir, "sd_turbo_q4_0.gguf").takeIf { it.exists() }
                    ?: modelFileOrDir.listFiles()?.firstOrNull { it.extension.equals("gguf", true) }
            } else {
                modelFileOrDir.takeIf { it.exists() }
            } ?: return null

            Log.i(TAG, "Found SD model file at ${file.absolutePath} (${file.length()} bytes)")
            val taesd = listOfNotNull(
                File(file.parentFile, "taesd.safetensors"),
                File(file.parentFile, "models/draw/taesd.safetensors"),
                file.parentFile?.parentFile?.let { File(it, "draw/taesd.safetensors") },
                file.parentFile?.parentFile?.let { File(it, "models/draw/taesd.safetensors") }
            ).firstOrNull { it.exists() }
            if (taesd != null) {
                Log.i(TAG, "Found TAESD weights at ${taesd.absolutePath} (${taesd.length()} bytes)")
            }

            return runCatching {
                SdEngine(
                    modelFile = file,
                    taesdFile = taesd,
                    minorsCheck = minorsCheck
                )
            }.onFailure { Log.e(TAG, "Failed to instantiate SdEngine: ${it.message}", it) }.getOrNull()
        }
    }
}
