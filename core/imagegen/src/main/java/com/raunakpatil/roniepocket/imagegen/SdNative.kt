package com.raunakpatil.roniepocket.imagegen

import android.util.Log

object SdNative {
    private const val TAG = "RonieImageGen"
    private var loaded = false

    init {
        try {
            System.loadLibrary("omp")
        } catch (t: Throwable) {
            runCatching { Log.w(TAG, "libomp.so load: ${t.message}") }   // Log isn't there in JVM unit tests
        }
        try {
            System.loadLibrary("stable-diffusion")
        } catch (t: Throwable) {
            runCatching { Log.e(TAG, "libstable-diffusion.so load failed: ${t.message}", t) }   // Log isn't there in JVM unit tests
        }
        try {
            System.loadLibrary("ronie_imagegen")
            loaded = true
            Log.i(TAG, "ronie_imagegen JNI native library loaded successfully")
        } catch (t: Throwable) {
            runCatching { Log.e(TAG, "libronie_imagegen.so load failed: ${t.message}", t) }   // Log isn't there in JVM unit tests
        }
    }

    const val SAMPLER_EULER = 0
    const val SAMPLER_EULER_A = 1
    const val SCHEDULER_DISCRETE = 0
    const val SCHEDULER_KARRAS = 1
    const val SCHEDULER_AYS = 3
    const val SCHEDULER_SGM_UNIFORM = 5

    val isAvailable: Boolean get() = loaded

    fun interface StepCallback {
        fun onStep(step: Int, total: Int, pixels: IntArray?, width: Int, height: Int)
    }

    external fun initContext(
        modelPath: String,
        taesdPath: String? = null,
        nThreads: Int = 4,
        useVulkan: Boolean = true
    ): Long

    external fun generate(
        ctxHandle: Long,
        prompt: String,
        negativePrompt: String,
        width: Int,
        height: Int,
        steps: Int,
        cfgScale: Float,
        sampleMethod: Int,
        scheduler: Int,
        seed: Long,
        callback: StepCallback? = null
    ): IntArray?

    external fun edit(
        ctxHandle: Long,
        initPixels: IntArray,
        initWidth: Int,
        initHeight: Int,
        prompt: String,
        negativePrompt: String,
        steps: Int,
        cfgScale: Float,
        sampleMethod: Int,
        scheduler: Int,
        strength: Float,
        seed: Long,
        callback: StepCallback? = null
    ): IntArray?

    external fun freeContext(ctxHandle: Long)
}
