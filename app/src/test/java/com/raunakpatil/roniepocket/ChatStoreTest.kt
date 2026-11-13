package com.raunakpatil.roniepocket

import com.raunakpatil.roniepocket.engine.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ChatStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun saveAndLoadPreservesImages() {
        val root = tempFolder.newFolder("store")
        val store = ChatStore(root)

        val imagePath1 = File(root, "img1.jpg").apply { writeText("fake image 1") }.absolutePath
        val imagePath2 = File(root, "img2.jpg").apply { writeText("fake image 2") }.absolutePath

        val messages = listOf(
            UiMessage(1L, fromUser = true, text = "Check this", images = listOf(imagePath1, imagePath2)),
            UiMessage(2L, fromUser = false, text = "I see two images.")
        )
        val history = listOf(
            ChatMessage(ChatMessage.Role.User, "Check this", listOf(imagePath1, imagePath2)),
            ChatMessage(ChatMessage.Role.Assistant, "I see two images.")
        )

        store.save(100L, messages, history)
        val loaded = store.load(100L)

        assertNotNull(loaded)
        assertEquals(2, loaded!!.messages.size)
        assertEquals(listOf(imagePath1, imagePath2), loaded.messages[0].images)
        assertEquals(2, loaded.history.size)
        assertEquals(listOf(imagePath1, imagePath2), loaded.history[0].images)
    }

    @Test
    fun deleteRemovesOrphanedImagesOnly() {
        val root = tempFolder.newFolder("store")
        val store = ChatStore(root)

        val sharedImg = File(root, "shared.jpg").apply { writeText("shared") }.absolutePath
        val exclusiveImg = File(root, "exclusive.jpg").apply { writeText("exclusive") }.absolutePath

        // Chat 1 has both images
        store.save(1L,
            listOf(UiMessage(1L, true, "Chat 1", images = listOf(sharedImg, exclusiveImg))),
            listOf(ChatMessage(ChatMessage.Role.User, "Chat 1", listOf(sharedImg, exclusiveImg)))
        )

        // Chat 2 has sharedImg only
        store.save(2L,
            listOf(UiMessage(2L, true, "Chat 2", images = listOf(sharedImg))),
            listOf(ChatMessage(ChatMessage.Role.User, "Chat 2", listOf(sharedImg)))
        )

        assertTrue(File(sharedImg).exists())
        assertTrue(File(exclusiveImg).exists())

        // Delete chat 1
        store.delete(1L)

        // exclusiveImg should be deleted because chat 1 was the only reference
        assertFalse(File(exclusiveImg).exists())
        // sharedImg should still exist because chat 2 references it
        assertTrue(File(sharedImg).exists())
    }
}
