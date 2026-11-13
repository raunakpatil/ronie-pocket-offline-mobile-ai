package com.raunakpatil.roniepocket.engine

/** A chat message for prompt formatting. [images] holds local file paths in order. */
data class ChatMessage(val role: Role, val content: String, val images: List<String> = emptyList()) {
    enum class Role { System, User, Assistant }
}

/**
 * Formats chat turns exactly as each model family's GGUF chat template does (verified by rendering
 * the embedded Jinja templates with jinja2; see Phase 3 notes). Kept in Kotlin because llama.cpp's
 * built-in template list doesn't know Gemma 4's turn markers.
 */
enum class PromptFormat {
    /** Gemma 4: `<bos><|turn>system\n…<turn|>\n<|turn>user\n…<turn|>\n<|turn>model\n` */
    GEMMA4 {
        override fun format(messages: List<ChatMessage>, thinking: Boolean): String = buildString {
            append("<bos>")
            messages.forEach { m ->
                val role = when (m.role) {
                    ChatMessage.Role.System -> "system"
                    ChatMessage.Role.User -> "user"
                    ChatMessage.Role.Assistant -> "model"
                }
                append("<|turn>").append(role).append('\n')
                if (m.role == ChatMessage.Role.System && thinking) append("<|think|>\n")
                if (m.role == ChatMessage.Role.User && m.images.isNotEmpty()) {
                    val marker = LlamaEngine.mediaMarker
                    m.images.forEach { _ ->
                        append(marker).append('\n')
                    }
                }
                append(m.content.trim()).append("<turn|>\n")
            }
            append("<|turn>model\n")
        }
    },

    /** Qwen3.5 (ChatML). With thinking off, the template pre-fills an empty think block. */
    QWEN35 {
        override fun format(messages: List<ChatMessage>, thinking: Boolean): String = buildString {
            messages.forEach { m ->
                val role = when (m.role) {
                    ChatMessage.Role.System -> "system"
                    ChatMessage.Role.User -> "user"
                    ChatMessage.Role.Assistant -> "assistant"
                }
                append("<|im_start|>").append(role).append('\n')
                if (m.role == ChatMessage.Role.User && m.images.isNotEmpty()) {
                    val marker = LlamaEngine.mediaMarker
                    m.images.forEach { _ ->
                        append(marker).append('\n')
                    }
                }
                append(m.content.trim()).append("<|im_end|>\n")
            }
            append("<|im_start|>assistant\n")
            append(if (thinking) "<think>\n" else "<think>\n\n</think>\n\n")
        }
    },

    /** Plain ChatML (Qwen2.5): the same turns as Qwen3.5 but no think block, which this family doesn't have. */
    CHATML {
        override fun format(messages: List<ChatMessage>, thinking: Boolean): String =
            QWEN35.format(messages, thinking = true).removeSuffix("<think>\n")
    };

    abstract fun format(messages: List<ChatMessage>, thinking: Boolean = false): String

    /**
     * The part of every prompt that comes before the first user turn: BOS (if any) plus the system
     * turn. Prefilling exactly this lets the engine reuse it for every message.
     */
    fun systemPrefix(system: String, thinking: Boolean = false): String {
        val full = format(listOf(ChatMessage(ChatMessage.Role.System, system), ChatMessage(ChatMessage.Role.User, "\u0000")), thinking)
        return full.substring(0, full.indexOf(userTurnMarker()))
    }

    private fun userTurnMarker(): String = when (this) {
        GEMMA4 -> "<|turn>user\n"
        QWEN35, CHATML -> "<|im_start|>user\n"
    }
}
