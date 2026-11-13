package com.raunakpatil.roniepocket.imagegen

import android.graphics.Bitmap
import java.io.Closeable
import java.io.File

/**
 * Thrown when an image prompt violates the hard safety gate (sexual content involving minors).
 */
class MinorsSafetyException(message: String = "I won't draw that.") : IllegalArgumentException(message)

/**
 * High-level on-device image generation and instruction-guided editing engine.
 */
interface ImageGen : Closeable {

    /**
     * Generates an image from a text prompt.
     * @param prompt Description of the desired image.
     * @param negativePrompt Optional negative prompt tokens.
     * @param width Image width in pixels (multiple of 64, default 512).
     * @param height Image height in pixels (multiple of 64, default 512).
     * @param steps Denoising steps (default 4).
     * @param seed Random seed for reproducibility.
     * @param onStep Callback invoked after each denoising step with progress and optional preview.
     * @return Generated [Bitmap].
     * @throws MinorsSafetyException if prompt involves minors sexual content.
     */
    suspend fun generate(
        prompt: String,
        negativePrompt: String = "",
        width: Int = 512,
        height: Int = 512,
        steps: Int = 4,
        seed: Long = System.currentTimeMillis(),
        onStep: ((step: Int, total: Int, preview: Bitmap?) -> Unit)? = null,
        onProgress: ((progress: Float, stageText: String, preview: Bitmap?) -> Unit)? = null
    ): Bitmap

    /**
     * Edits an existing image following a natural language instruction.
     * @param source The input [Bitmap] to edit.
     * @param instruction What to add, remove, or modify.
     * @param negativePrompt Optional negative prompt tokens.
     * @param steps Denoising steps (default 4).
     * @param seed Random seed for reproducibility.
     * @param onStep Callback invoked after each denoising step with progress.
     * @return Edited [Bitmap].
     * @throws MinorsSafetyException if instruction involves minors sexual content.
     */
    suspend fun edit(
        source: Bitmap,
        instruction: String,
        negativePrompt: String = "",
        steps: Int = 4,
        seed: Long = System.currentTimeMillis(),
        onStep: ((step: Int, total: Int, preview: Bitmap?) -> Unit)? = null,
        onProgress: ((progress: Float, stageText: String, preview: Bitmap?) -> Unit)? = null
    ): Bitmap

    override fun close()

    companion object {
        /**
         * Loads the ImageGen engine from model weights stored in [modelDir] or model file.
         * Automatically loads [SanaEngine] if SANA-Sprint ONNX models are present,
         * or falls back to [SdEngine] for SD-Turbo GGUF weights.
         * Returns null if model weights are missing or corrupt.
         */
        fun load(modelDir: File, minorsCheck: MinorsCheck? = null): ImageGen? {
            if (SanaEngine.canLoad(modelDir)) {
                return SanaEngine.load(modelDir, minorsCheck)
            }
            return SdEngine.load(modelDir, minorsCheck)
        }
    }
}
