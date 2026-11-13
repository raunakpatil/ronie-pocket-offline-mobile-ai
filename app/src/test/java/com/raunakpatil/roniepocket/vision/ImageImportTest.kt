package com.raunakpatil.roniepocket.vision

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageImportTest {

    @Test
    fun dimensionsSmallerThanMaxArePreserved() {
        val (w, h) = ImageImport.calculateTargetDimensions(800, 600, maxLongSide = 1536)
        assertEquals(800, w)
        assertEquals(600, h)
    }

    @Test
    fun landscapeDownscalesByWidth() {
        val (w, h) = ImageImport.calculateTargetDimensions(3072, 2048, maxLongSide = 1536)
        assertEquals(1536, w)
        assertEquals(1024, h)
    }

    @Test
    fun portraitDownscalesByHeight() {
        val (w, h) = ImageImport.calculateTargetDimensions(2000, 4000, maxLongSide = 1536)
        assertEquals(768, w)
        assertEquals(1536, h)
    }

    @Test
    fun squareDownscalesBoth() {
        val (w, h) = ImageImport.calculateTargetDimensions(3000, 3000, maxLongSide = 1536)
        assertEquals(1536, w)
        assertEquals(1536, h)
    }

    @Test
    fun sha1MatchesKnownDigest() {
        val input = "hello world".toByteArray(Charsets.UTF_8)
        val hash = ImageImport.sha1(input)
        assertEquals("2aae6c35c94fcfb415dbe95f408b9ce91ee846ed", hash)
    }

    @Test
    fun sha1EmptyBytes() {
        val hash = ImageImport.sha1(ByteArray(0))
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", hash)
    }
}
