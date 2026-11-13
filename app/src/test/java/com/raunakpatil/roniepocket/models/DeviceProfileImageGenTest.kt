package com.raunakpatil.roniepocket.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceProfileImageGenTest {

    /** Only what the download worker can actually fetch may be offered as available (today: SD-Turbo). */
    @Test
    fun onlyDownloadableModelsAreAvailable() {
        assertTrue(ImageGenTier.SD_TURBO.isAvailableNow)
        assertFalse(ImageGenTier.SANA_SPRINT.isAvailableNow)
        assertFalse(ImageGenTier.NITRO_E.isAvailableNow)
    }

    @Test
    fun testPixel9Pro16GB_RecommendsWhatCanBeDownloaded() {
        val pixel9Pro = DeviceProfile(
            totalRamBytes = 16L * 1024L * 1024L * 1024L, // 16 GB
            is64Bit = true,
            hasDotProd = true,
            hasI8mm = true,
            hasVulkan11 = true,
            freeStorageBytes = 64L * 1024L * 1024L * 1024L,
            model = "Google Pixel 9 Pro"
        )

        val rec = TierPolicy.recommendImageGen(pixel9Pro)
        assertTrue(rec.isDeviceCapable)
        assertEquals(ImageGenTier.SD_TURBO, rec.recommendedTier)
        assertTrue(rec.recommendedTier.isAvailableNow)
        assertEquals(Fit.RECOMMENDED, rec.options.first { it.tier == ImageGenTier.SD_TURBO }.fit)
    }

    @Test
    fun test8GBDevice_RecommendsSdTurbo() {
        val device8GB = DeviceProfile(
            totalRamBytes = 8L * 1024L * 1024L * 1024L, // 8 GB
            is64Bit = true,
            hasDotProd = true,
            hasI8mm = false,
            hasVulkan11 = true,
            freeStorageBytes = 32L * 1024L * 1024L * 1024L,
            model = "Pixel 7a"
        )

        val rec = TierPolicy.recommendImageGen(device8GB)
        assertTrue(rec.isDeviceCapable)
        assertEquals(ImageGenTier.SD_TURBO, rec.recommendedTier)

        val sdOpt = rec.options.firstOrNull { it.tier == ImageGenTier.SD_TURBO }
        assertNotNull(sdOpt)
        assertEquals(Fit.RECOMMENDED, sdOpt!!.fit)
        assertTrue(rec.recommendedTier.isAvailableNow)
    }

    @Test
    fun testLowMemoryDevice_Disabled() {
        val lowRamPhone = DeviceProfile(
            totalRamBytes = 4L * 1024L * 1024L * 1024L, // 4 GB
            is64Bit = true,
            hasDotProd = false,
            hasI8mm = false,
            hasVulkan11 = false,
            freeStorageBytes = 10L * 1024L * 1024L * 1024L,
            model = "Entry Phone"
        )

        val rec = TierPolicy.recommendImageGen(lowRamPhone)
        assertFalse(rec.isDeviceCapable)
    }
}
