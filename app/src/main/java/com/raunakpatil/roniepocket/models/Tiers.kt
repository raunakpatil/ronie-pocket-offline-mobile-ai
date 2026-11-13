package com.raunakpatil.roniepocket.models

import com.raunakpatil.roniepocket.engine.PromptFormat

/** A pinned Hugging Face file asset (weights, mmproj projector, etc.). */
data class FileSpec(
    val repo: String,
    val revision: String,
    val file: String,
    val sizeBytes: Long,
    val sha256: String,
) {
    val url: String get() = "https://huggingface.co/$repo/resolve/$revision/$file"
}

/**
 * The curated model list: exactly one model per tier (eval/DECISION.md). Files are pinned to a
 * Hugging Face revision and verified by SHA-256 after download.
 */
data class ModelSpec(
    val repo: String,
    val revision: String,
    val file: String,
    val sizeBytes: Long,
    val sha256: String,
    val format: PromptFormat,
    val gpuLayers: Int,
    /** Approximate memory in use while chatting (weights + 8k context + buffers). */
    val ramBytes: Long,
    /** Optional multimodal vision projector file. */
    val mmproj: FileSpec? = null,
) {
    val url: String get() = "https://huggingface.co/$repo/resolve/$revision/$file"
    val sizeLabel: String get() = "%.1f GB".format(sizeBytes / 1e9)
    val ramLabel: String get() = "~%.1f GB".format(ramBytes / 1e9)
}

enum class Tier(
    val label: String,
    val tagline: String,
    val model: ModelSpec,
    /** Minimum steady generation speed (tok/s) before Ronie offers to step down a tier. */
    val minTokPerSec: Double,
) {
    FULL(
        "Full", "Gemma 4 E2B on your GPU: Ronie's smartest brain",
        ModelSpec(
            repo = "unsloth/gemma-4-E2B-it-GGUF", revision = "0314792d7f1f7e229411f620751375812bb9faf2",
            file = "gemma-4-E2B-it-Q4_K_M.gguf", sizeBytes = 3_106_738_272,
            sha256 = "740185b21d22ceb83a11c3aa62ad5842ef32c70f6096d756bbee85a1e4ec34b8",
            format = PromptFormat.GEMMA4, gpuLayers = 99,
            ramBytes = 4_500_000_000,   // reported by OnDevice LLM; re-measure on the Pixel
            mmproj = FileSpec(
                repo = "unsloth/gemma-4-E2B-it-GGUF", revision = "0314792d7f1f7e229411f620751375812bb9faf2",
                file = "mmproj-F16.gguf", sizeBytes = 985_654_080,
                sha256 = "140be8d7849741f88c50757d529b84373ee8e27052cc2236855b537f4a8215fa"
            ),
        ),
        minTokPerSec = 6.0,
    ),
    STANDARD(
        "Standard", "Qwen3.5-2B: quick and capable",
        ModelSpec(
            repo = "unsloth/Qwen3.5-2B-GGUF", revision = "f6d5376be1edb4d416d56da11e5397a961aca8ae",
            file = "Qwen3.5-2B-Q4_K_M.gguf", sizeBytes = 1_280_835_840,
            sha256 = "aaf42c8b7c3cab2bf3d69c355048d4a0ee9973d48f16c731c0520ee914699223",
            format = PromptFormat.QWEN35, gpuLayers = 99,
            ramBytes = 2_000_000_000,   // estimate; re-measure
        ),
        minTokPerSec = 6.0,
    ),
    LITE(
        "Lite", "Qwen2.5-1.5B: light and capable",
        ModelSpec(
            repo = "Qwen/Qwen2.5-1.5B-Instruct-GGUF", revision = "91cad51170dc346986eccefdc2dd33a9da36ead9",
            file = "qwen2.5-1.5b-instruct-q4_k_m.gguf", sizeBytes = 1_117_320_736L,
            sha256 = "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e",
            format = PromptFormat.CHATML, gpuLayers = 99,
            ramBytes = 1_400_000_000,   // estimate; re-measure
        ),
        minTokPerSec = 0.0,   // the floor; nothing lighter to step down to
    );

    /** The next lighter tier, or null at the bottom. */
    fun lighter(): Tier? = entries.getOrNull(ordinal + 1)
}
