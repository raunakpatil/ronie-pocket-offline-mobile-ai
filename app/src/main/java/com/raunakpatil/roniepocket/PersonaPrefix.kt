package com.raunakpatil.roniepocket

import com.raunakpatil.roniepocket.engine.PromptFormat
import com.raunakpatil.roniepocket.models.Tier
import com.raunakpatil.roniepocket.tools.ToolPrompt

/**
 * Ronie's system prompt, split into the part that is the same for everyone on this app version and brain
 * (persona + tool rules + built-in tool descriptions) and the per-message tail (connector tools, today's
 * dates, the user's name).
 *
 * The fixed part's model state is computed ahead of time on the PC (scripts\make-persona-state.cmd →
 * tools-pc/persona_state) and shipped in assets/state/, so the first launch restores it in ~60 ms instead
 * of computing it (~49 s on the Pixel 9 Pro). The unit test PersonaPrefixExport writes the exact text for
 * the PC tool, so the two can never drift; the engine also refuses a state whose tokens don't match.
 */
object PersonaPrefix {
    private const val TAIL = "\nToday is "

    /** Tools use Gemma 4's native call format; the Qwen tiers don't have tools yet. */
    fun toolsOn(format: PromptFormat) = format == PromptFormat.GEMMA4

    fun systemText(format: PromptFormat, builtinBlock: String, remoteDecls: String = "", dateLine: String = ToolPrompt.dateLine(),
                   nameLine: String = ""): String {
        val persona = Persona.systemPrompt()
        if (!toolsOn(format)) return persona + nameLine          // the persona ends with its own "Today is <date>."
        val stable = persona.substringBefore("Today is ").trimEnd()
        return stable + "\n" + ToolPrompt.GUIDE.trim() + builtinBlock + remoteDecls + "\n" + dateLine + nameLine
    }

    /** The fixed start of every prompt (BOS + system turn up to the date line): the cached / shipped part. */
    fun stable(format: PromptFormat, builtinBlock: String): String =
        format.systemPrefix(systemText(format, builtinBlock)).substringBefore(TAIL)

    /** File name of the saved state: changes whenever the model or the fixed text changes. */
    fun stateFileName(t: Tier, stable: String): String = "${t.name}-${sha1(t.model.sha256 + stable)}.bin"

    private fun sha1(s: String): String =
        java.security.MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
}
