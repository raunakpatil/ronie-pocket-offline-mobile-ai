package com.raunakpatil.roniepocket.models

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.StatFs
import java.io.File

/** What Ronie knows about the phone when picking a tier. */
data class DeviceProfile(
    val totalRamBytes: Long,
    val is64Bit: Boolean,
    val hasDotProd: Boolean,
    val hasI8mm: Boolean,
    val hasVulkan11: Boolean,
    val freeStorageBytes: Long,
    val model: String,
) {
    val ramGb: Double get() = totalRamBytes / 1e9

    companion object {
        fun read(context: Context): DeviceProfile {
            val am = context.getSystemService(ActivityManager::class.java)
            val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            val features = runCatching {
                File("/proc/cpuinfo").readLines().firstOrNull { it.startsWith("Features") }.orEmpty()
            }.getOrDefault("").split(' ', '\t', ':').toSet()
            // 0x401000 = Vulkan 1.1 (feature version encoding: major << 22 | minor << 12)
            val vulkan = context.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, 0x401000)
            return DeviceProfile(
                totalRamBytes = mem.totalMem,
                is64Bit = Build.SUPPORTED_64_BIT_ABIS.contains("arm64-v8a"),
                hasDotProd = "asimddp" in features,
                hasI8mm = "i8mm" in features,
                hasVulkan11 = vulkan,
                freeStorageBytes = StatFs(context.filesDir.absolutePath).availableBytes,
                model = "${Build.MANUFACTURER} ${Build.MODEL}",
            )
        }
    }
}

/** Result of the tier choice. [tier] is null when the phone can't run Ronie offline. */
data class TierChoice(
    val tier: Tier?,
    val reason: String,
    val options: List<TierOption> = emptyList(),
    /** e.g. "Your phone: 16 GB RAM · 19.2 GB free storage" */
    val deviceSummary: String = "",
)

/** How well a curated model fits this phone, shown on the first-run picker. */
enum class Fit { RECOMMENDED, OK, SLOW, NO_SPACE }

data class TierOption(val tier: Tier, val fit: Fit, val note: String) {
    val selectable: Boolean get() = fit != Fit.NO_SPACE
}

object TierPolicy {
    // Phones sold as "12 GB" report ~11.x GB of usable RAM, "8 GB" ~7.x GB, so thresholds sit below.
    private const val FULL_RAM = 11.0e9
    private const val STANDARD_RAM = 7.0e9
    private const val LITE_RAM = 3.5e9
    private const val STORAGE_HEADROOM = 1.5e9   // keep space for the OS, chats and helper models

    fun choose(d: DeviceProfile): TierChoice {
        if (!d.is64Bit) return TierChoice(null, "Ronie needs a 64-bit phone to run offline.")
        if (d.totalRamBytes < LITE_RAM) return TierChoice(null, "Ronie needs at least 4 GB of RAM to run offline.")

        val ram = "%.0f GB RAM".format(d.ramGb)
        val candidate = when {
            d.totalRamBytes >= FULL_RAM && d.hasVulkan11 -> Tier.FULL
            d.totalRamBytes >= STANDARD_RAM && d.hasDotProd -> Tier.STANDARD
            else -> Tier.LITE
        }
        // Step down if there isn't room for the download.
        var t: Tier? = candidate
        while (t != null && d.freeStorageBytes < t.model.sizeBytes + STORAGE_HEADROOM) t = t.lighter()
        if (t == null) {
            val need = (Tier.LITE.model.sizeBytes + STORAGE_HEADROOM - d.freeStorageBytes) / 1e9
            return TierChoice(null, "Free up about %.1f GB of storage so Ronie can move in.".format(need))
        }
        val why = when (t) {
            Tier.FULL -> "$ram and a capable GPU"
            Tier.STANDARD -> if (candidate == Tier.FULL) "$ram, but less free storage" else "$ram and a modern CPU"
            Tier.LITE -> if (candidate != Tier.LITE) "$ram, but less free storage" else "$ram"
        }
        val summary = "Your phone: %.0f GB RAM · %.1f GB free storage".format(d.ramGb, d.freeStorageBytes / 1e9)
        return TierChoice(t, "Picked for your ${d.model}: $why.", options(d, t), summary)
    }

    /**
     * All curated models for the first-run picker: the recommended one first, then the rest
     * (heavier ones warn that they may be slow; ones that don't fit in storage are disabled).
     */
    fun options(d: DeviceProfile, recommended: Tier): List<TierOption> {
        val all = Tier.entries.map { t ->
            val fitsStorage = d.freeStorageBytes >= t.model.sizeBytes + STORAGE_HEADROOM
            // Leave most RAM to Android and other apps: warn if the model would take over 40% of it.
            val ramTight = t.model.ramBytes > d.totalRamBytes * 0.4
            when {
                t == recommended -> TierOption(t, Fit.RECOMMENDED, "Best balance of speed and smarts for this phone")
                !fitsStorage -> TierOption(t, Fit.NO_SPACE,
                    "Needs %.1f GB free storage".format((t.model.sizeBytes + STORAGE_HEADROOM) / 1e9))
                ramTight -> TierOption(t, Fit.SLOW, "Uses a lot of this phone's memory: other apps may close and replies may be slow")
                t.ordinal < recommended.ordinal -> TierOption(t, Fit.SLOW, when (t) {
                    Tier.FULL -> "Smarter, but made for 12 GB+ phones with a strong GPU: may be slow and warm here"
                    else -> "Smarter, but may be slow on this phone"
                })
                else -> TierOption(t, Fit.OK, "Lighter and faster, a bit less capable; saves memory and storage")
            }
        }
        return all.sortedBy { if (it.fit == Fit.RECOMMENDED) -1 else it.tier.ordinal }
    }

    /**
     * Hardware-based recommendation for on-device image generation based on device RAM and GPU capabilities.
     * Flagship (12 GB+): SANA-Sprint 0.6B (1024px, 1-2 steps, ~3.3 GB) & SD-Turbo (512px, Vulkan).
     * Mid-range (8 GB): Nitro-E 0.3B (512px 4-step, ~1.6 GB) & SD-Turbo (512px, ~1.8 GB).
     * Entry (< 8 GB): Not recommended due to low memory headroom with LLM concurrent usage.
     */
    fun recommendImageGen(d: DeviceProfile): ImageGenRecommendation {
        val ramGb = d.ramGb
        return when {
            d.totalRamBytes >= 11.0e9 -> { // 12-16 GB flagships (e.g. Pixel 9 Pro 16 GB)
                ImageGenRecommendation(
                    recommendedTier = ImageGenTier.SD_TURBO,
                    headline = "Recommended for your phone: SD-Turbo drawing",
                    explanation = "Your phone has %.0f GB RAM, plenty for Ronie's chat brain and 512×512 drawing on the GPU.".format(ramGb),
                    isDeviceCapable = true,
                    options = listOf(
                        ImageGenOption(
                            tier = ImageGenTier.SD_TURBO,
                            fit = Fit.RECOMMENDED,
                            recommendationBadge = "Recommended",
                            note = "512×512 in 3 steps on the GPU, under a minute on a Pixel 9 Pro. Fully offline and uncensored."
                        ),
                        ImageGenOption(
                            tier = ImageGenTier.SANA_SPRINT,
                            fit = Fit.SLOW,
                            recommendationBadge = "Coming later",
                            note = "1024×1024 in 1–2 steps. Not downloadable yet."
                        ),
                    )
                )
            }
            d.totalRamBytes >= 7.0e9 -> { // 8 GB phones
                ImageGenRecommendation(
                    recommendedTier = ImageGenTier.SD_TURBO,
                    headline = "Recommended for 8 GB phone: Compact 512px drawing",
                    explanation = "Your phone has %.0f GB RAM. 512px models (~1.6–1.8 GB) fit safely alongside Ronie without causing Android memory pressure.".format(ramGb),
                    isDeviceCapable = true,
                    options = listOf(
                        ImageGenOption(
                            tier = ImageGenTier.SD_TURBO,
                            fit = Fit.RECOMMENDED,
                            recommendationBadge = "Recommended for 8 GB",
                            note = "Balanced 512px diffusion via Vulkan (~1.8 GB). Fits comfortably in 8 GB RAM."
                        ),
                        ImageGenOption(
                            tier = ImageGenTier.NITRO_E,
                            fit = Fit.SLOW,
                            recommendationBadge = "Not recommended",
                            note = "Severe 512px DC-AE latent blur and twin-head artifacts."
                        )
                    )
                )
            }
            else -> { // < 8 GB (4-6 GB)
                ImageGenRecommendation(
                    recommendedTier = ImageGenTier.SD_TURBO,
                    headline = "Drawing not recommended: High memory demand",
                    explanation = "Your phone has %.0f GB RAM. Running both a language brain and image diffusion simultaneously requires at least 8 GB RAM.".format(ramGb),
                    isDeviceCapable = false,
                    options = listOf(
                        ImageGenOption(
                            tier = ImageGenTier.SD_TURBO,
                            fit = Fit.SLOW,
                            recommendationBadge = "May run out of memory",
                            note = "Requires ~2.4 GB peak VRAM. Background apps will likely be closed by Android."
                        )
                    )
                )
            }
        }
    }
}

/** Curated image generation model options with hardware suitability. */
enum class ImageGenTier(
    val id: String,
    val title: String,
    val subtitle: String,
    val resolution: String,
    val stepsLabel: String,
    val downloadSizeLabel: String,
    val qualityMetric: String,
    val license: String,
    val minRamGb: Double,
    /** The files to download, pinned by size and SHA-256. Empty = nothing hosted yet, so it can't be downloaded. */
    val files: List<DrawFile>,
) {
    SANA_SPRINT(
        id = "sana_sprint",
        title = "SANA-Sprint 0.6B",
        subtitle = "Ultra-fast 1024px DiT · GenEval 0.74 · Flagship NPU/GPU",
        resolution = "1024×1024",
        stepsLabel = "1–2 steps",
        downloadSizeLabel = "3.3 GB int8",
        qualityMetric = "GenEval 0.74 (FID 7.59)",
        license = "Apache-2.0",
        minRamGb = 11.0,
        files = emptyList(),   // the ONNX export isn't hosted anywhere yet; it works when the files are copied onto the phone
    ),
    SD_TURBO(
        id = "sd_turbo",
        title = "SD-Turbo + TAESD",
        subtitle = "On-device Vulkan GPU diffusion · Stable & Uncensored",
        resolution = "512×512",
        stepsLabel = "3 steps (~45 s on a Pixel 9 Pro)",
        downloadSizeLabel = "1.8 GB GGUF",
        qualityMetric = "GenEval ~0.49",
        license = "Stability AI non-commercial",
        minRamGb = 7.0,
        files = listOf(
            DrawFile("sd_turbo_q4_0.gguf", "https://huggingface.co/samoudi/sd-turbo-gguf/resolve/main/sd_turbo_q4_0.gguf",
                1_847_909_248L, "d069ef2b2d3d5c2465662d706c242a1016db56163c109bd7e3008a94cdd00359"),
            DrawFile("taesd.safetensors", "https://huggingface.co/madebyollin/taesd/resolve/main/diffusion_pytorch_model.safetensors",
                9_793_292L, "db169d69145ec4ff064e49d99c95fa05d3eb04ee453de35824a6d0f325513549"),
        ),
    ),
    NITRO_E(
        id = "nitro_e",
        title = "Nitro-E 0.3B (4-step)",
        subtitle = "Ultralight 304M DiT · GenEval 0.67 · Best for 8 GB phones",
        resolution = "512×512",
        stepsLabel = "4 steps (0.5–1.5 s on NPU)",
        downloadSizeLabel = "1.6 GB int8",
        qualityMetric = "GenEval 0.67",
        license = "MIT",
        minRamGb = 7.0,
        files = emptyList(),
    );

    /** True when the app can download it (see [files]). */
    val isAvailableNow: Boolean get() = files.isNotEmpty()
    /** Folder under models/draw the files live in ("" = models/draw itself). */
    val folder: String get() = if (this == SANA_SPRINT) "sana" else ""
}

/** One file of a drawing model: its name on disk, where it comes from, its exact size and SHA-256. */
data class DrawFile(val name: String, val url: String, val sizeBytes: Long, val sha256: String)

/** Hardware recommendation for image generation. */
data class ImageGenRecommendation(
    val recommendedTier: ImageGenTier,
    val headline: String,
    val explanation: String,
    val isDeviceCapable: Boolean,
    val options: List<ImageGenOption>,
)

data class ImageGenOption(
    val tier: ImageGenTier,
    val fit: Fit,
    val recommendationBadge: String?,
    val note: String,
)
