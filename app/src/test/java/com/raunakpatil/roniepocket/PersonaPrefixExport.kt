package com.raunakpatil.roniepocket

import com.raunakpatil.roniepocket.models.Tier
import com.raunakpatil.roniepocket.tools.ToolSpec
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Writes the exact fixed prompt text for each brain (app/build/persona/<TIER>.txt) and the state file name
 * the app will look for (<TIER>.name). scripts\make-persona-state.cmd feeds these to tools-pc/persona_state,
 * which computes the model state on the PC so the app can ship it (fast first launch).
 */
class PersonaPrefixExport {
    @Test fun export() {
        val specs = JSONObject(File("src/main/assets/tools/builtin.json").readText()).getJSONArray("tools")
            .let { a -> (0 until a.length()).map { ToolSpec.fromMcp(a.getJSONObject(it)) } }
        val builtinBlock = specs.joinToString("") { it.declaration() }
        val out = File("build/persona").apply { mkdirs() }
        for (t in Tier.entries) {
            val stable = PersonaPrefix.stable(t.model.format, if (PersonaPrefix.toolsOn(t.model.format)) builtinBlock else "")
            assertTrue(stable.length > 500 && !stable.contains("Today is"))
            File(out, "${t.name}.txt").writeBytes(stable.toByteArray(Charsets.UTF_8))
            File(out, "${t.name}.name").writeText(PersonaPrefix.stateFileName(t, stable))
        }
    }
}
