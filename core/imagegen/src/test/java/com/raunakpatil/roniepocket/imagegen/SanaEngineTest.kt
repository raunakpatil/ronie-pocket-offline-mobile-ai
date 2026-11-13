package com.raunakpatil.roniepocket.imagegen

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileInputStream

class SanaEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var checker: MinorsCheck

    @Before
    fun setup() {
        val candidates = listOf(
            File("src/main/assets/imagegen/minors.json"),
            File("core/imagegen/src/main/assets/imagegen/minors.json"),
            File("../core/imagegen/src/main/assets/imagegen/minors.json"),
            File("../../core/imagegen/src/main/assets/imagegen/minors.json"),
            File("app/src/main/assets/imagegen/minors.json"),
            File("../app/src/main/assets/imagegen/minors.json")
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: throw IllegalStateException("Could not find minors.json")
        checker = MinorsCheck.fromJson(FileInputStream(file))
    }

    @Test
    fun testCanLoadDetection() {
        val dir = tempFolder.newFolder("sana_model_dir")
        val ditFile = File(dir, "sana_dit.onnx").apply { createNewFile() }
        val vaeFile = File(dir, "sana_vae.onnx").apply { createNewFile() }

        assertTrue(SanaEngine.canLoad(dir))
        assertTrue(SanaEngine.canLoad(ditFile))

        val nonSanaDir = tempFolder.newFolder("sd_dir")
        File(nonSanaDir, "sd_turbo_q4_0.gguf").apply { createNewFile() }
        assertFalse(SanaEngine.canLoad(nonSanaDir))
    }

    @Test
    fun testImageGenFactoryDelegation() {
        val sanaDir = tempFolder.newFolder("sana")
        File(sanaDir, "sana_dit.onnx").apply { createNewFile() }

        val loaded = ImageGen.load(sanaDir, checker)
        assertNotNull(loaded)
        assertTrue(loaded is SanaEngine)
        loaded?.close()
    }

    @Test
    fun testMinorsSafetyGateStrictBlocking() = runBlocking {
        val sanaDir = tempFolder.newFolder("sana_safety_test")
        val engine = SanaEngine(sanaDir, checker)

        // Illegal prompt combining minor + sexual keyword must be strictly blocked
        try {
            engine.generate("nude schoolgirl sitting on chair")
            fail("Expected MinorsSafetyException for minor sexual content")
        } catch (e: MinorsSafetyException) {
            assertTrue(e.message?.contains("won't draw that") == true)
        }
        engine.close()
    }
}
