package com.raunakpatil.roniepocket.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class PromptFormatTest {

    @Test
    fun gemma4TextOnlyMatchesTemplate() {
        val msgs = listOf(
            ChatMessage(ChatMessage.Role.System, "You are Ronie."),
            ChatMessage(ChatMessage.Role.User, "Hello"),
            ChatMessage(ChatMessage.Role.Assistant, "Hi there!"),
            ChatMessage(ChatMessage.Role.User, "How's the weather?")
        )
        val prompt = PromptFormat.GEMMA4.format(msgs, thinking = false)
        val expected = "<bos><|turn>system\nYou are Ronie.<turn|>\n" +
            "<|turn>user\nHello<turn|>\n" +
            "<|turn>model\nHi there!<turn|>\n" +
            "<|turn>user\nHow's the weather?<turn|>\n" +
            "<|turn>model\n"
        assertEquals(expected, prompt)
    }

    @Test
    fun gemma4OneImagePutsMediaMarkerFirst() {
        val msgs = listOf(
            ChatMessage(ChatMessage.Role.System, "You are Ronie."),
            ChatMessage(ChatMessage.Role.User, "What is this?", listOf("/path/to/img1.jpg"))
        )
        val prompt = PromptFormat.GEMMA4.format(msgs, thinking = false)
        val expected = "<bos><|turn>system\nYou are Ronie.<turn|>\n" +
            "<|turn>user\n<__media__>\nWhat is this?<turn|>\n" +
            "<|turn>model\n"
        assertEquals(expected, prompt)
    }

    @Test
    fun gemma4TwoImagesPutsTwoMarkersFirst() {
        val msgs = listOf(
            ChatMessage(ChatMessage.Role.System, "You are Ronie."),
            ChatMessage(ChatMessage.Role.User, "Compare these", listOf("/path/to/img1.jpg", "/path/to/img2.jpg"))
        )
        val prompt = PromptFormat.GEMMA4.format(msgs, thinking = false)
        val expected = "<bos><|turn>system\nYou are Ronie.<turn|>\n" +
            "<|turn>user\n<__media__>\n<__media__>\nCompare these<turn|>\n" +
            "<|turn>model\n"
        assertEquals(expected, prompt)
    }

    @Test
    fun qwen35TwoImagesPutsTwoMarkersFirst() {
        val msgs = listOf(
            ChatMessage(ChatMessage.Role.System, "You are Ronie."),
            ChatMessage(ChatMessage.Role.User, "Compare these", listOf("/path/to/img1.jpg", "/path/to/img2.jpg"))
        )
        val prompt = PromptFormat.QWEN35.format(msgs, thinking = false)
        val expected = "<|im_start|>system\nYou are Ronie.<|im_end|>\n" +
            "<|im_start|>user\n<__media__>\n<__media__>\nCompare these<|im_end|>\n" +
            "<|im_start|>assistant\n<think>\n\n</think>\n\n"
        assertEquals(expected, prompt)
    }

    @Test
    fun systemPrefixMatchesFullPromptStart() {
        val sys = "You are Ronie, personal companion."
        val prefix = PromptFormat.GEMMA4.systemPrefix(sys)
        val expected = "<bos><|turn>system\n$sys<turn|>\n"
        assertEquals(expected, prefix)
    }

    @Test fun chatmlHasNoThinkBlock() {
        val msgs = listOf(ChatMessage(ChatMessage.Role.System, "sys"), ChatMessage(ChatMessage.Role.User, "hi"))
        val prompt = PromptFormat.CHATML.format(msgs)
        assertEquals("<|im_start|>system\nsys<|im_end|>\n<|im_start|>user\nhi<|im_end|>\n<|im_start|>assistant\n", prompt)
        assertEquals("<|im_start|>system\nsys<|im_end|>\n", PromptFormat.CHATML.systemPrefix("sys"))
    }
}
