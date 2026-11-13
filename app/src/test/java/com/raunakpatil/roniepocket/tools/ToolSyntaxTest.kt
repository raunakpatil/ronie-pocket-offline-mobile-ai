package com.raunakpatil.roniepocket.tools

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Tool syntax checks (runs on the PC: scripts\test.cmd). */
class ToolSyntaxTest {
    private val specs = JSONObject(File("src/main/assets/tools/builtin.json").readText()).getJSONArray("tools")
        .let { a -> (0 until a.length()).map { ToolSpec.fromMcp(a.getJSONObject(it)) } }
    private val known = specs.associate { it.name to it.required }
    private fun spec(n: String) = specs.first { it.name == n }

    /** Same bytes as Gemma 4's own chat template (golden rendered with jinja2 by eval/tools_v2/check_template.py). */
    @Test fun matchesGemmaTemplate() {
        val golden = File("src/test/resources/tools/gemma_template_golden.txt").readText()
        val mine = "<bos><|turn>system\nSYS" + specs.joinToString("") { it.declaration() } +
            "<turn|>\n<|turn>user\nadd milk<turn|>\n<|turn>model\n" +
            ToolSyntax.call("create_task", mapOf("title" to "milk", "due" to "2026-11-10")) +
            ToolSyntax.response("create_task", "Added task 'milk'") + "Done.<turn|>\n"
        assertEquals(golden, mine)
    }

    @Test fun parsesNativeCalls() {
        val c = ToolSyntax.parseCalls("<|tool_call>call:set_alarm{label:<|\"|>gym, legs<|\"|>,time:<|\"|>17:45<|\"|>}<tool_call|>", known).single()
        assertEquals("set_alarm", c.name)
        assertEquals(mapOf("label" to "gym, legs", "time" to "17:45"), c.args)
        assertFalse(c.repaired)
        val n = ToolSyntax.parseCalls("<|tool_call>call:set_timer{minutes:0.75}<tool_call|>", known).single()
        assertEquals(0.75, n.args!!["minutes"])
    }

    @Test fun repairsBareCalls() {
        assertEquals(mapOf("show" to "open"), ToolSyntax.parseCalls("list_tasks{show:<|\"|>open<|\"|>}<turn|>", known).single().args)
        assertEquals(mapOf("time" to "06:30"), ToolSyntax.parseCalls("set_alarm(time=\"06:30\")<turn|>", known).single().args)
        assertEquals(mapOf("show" to "done"), ToolSyntax.parseCalls("list_tasks(show: \"done\")", known).single().args)
        assertEquals(emptyMap<String, Any?>(), ToolSyntax.parseCalls("list_tasks\n<eos>", known).single().args)
        assertEquals(mapOf("fact" to "AI engineer at Snorkel"), ToolSyntax.parseCalls("remember(AI engineer at Snorkel)<turn|>", known).single().args)
        // Ordinary text is never a call.
        assertTrue(ToolSyntax.parseCalls("remember that eggs go off", known).isEmpty())
        assertTrue(ToolSyntax.parseCalls("Done, added it.", known).isEmpty())
        assertTrue(ToolSyntax.parseCalls("delete_everything{}", known).isEmpty())
    }

    @Test fun holdsBackPossibleBareCalls() {
        val names = known.keys
        assertTrue(ToolSyntax.maybeBareCall("list_ta", names))
        assertTrue(ToolSyntax.maybeBareCall("list_tasks{sh", names))
        assertFalse(ToolSyntax.maybeBareCall("Sure", names))
        assertFalse(ToolSyntax.maybeBareCall("Listen, ", names))
    }

    @Test fun visibleTextHidesControlTokens() {
        assertEquals("Added it.", ToolSyntax.visible("<|tool_call>call:create_task{title:<|\"|>x<|\"|>}<tool_call|>Added it.").trim())
        assertEquals("Let me check", ToolSyntax.visible("Let me check<|tool_call>call:get_cal").trim())
    }

    @Test fun argCheckFixesAndRejects() {
        val ok = ArgCheck.check(spec("set_timer"), mapOf("minutes" to "10", "label" to "pasta", "junk" to 1))
        assertTrue(ok.errors.isEmpty()); assertEquals(10.0, ok.args["minutes"]); assertNull(ok.args["junk"])
        assertEquals(listOf("missing time"), ArgCheck.check(spec("set_reminder"), mapOf("text" to "x")).errors)
        assertEquals(7L, ArgCheck.check(spec("get_calendar"), mapOf("start" to "2026-11-09", "days" to 7.0)).args["days"])
        assertTrue(ArgCheck.check(spec("list_tasks"), mapOf("show" to "later")).errors.single().contains("one of"))
    }

    @Test fun gateIsDecidedByAnnotations() {
        assertEquals(Gate.RUN, Gate.of(spec("list_tasks"), afterToolResult = false))
        assertEquals(Gate.RUN_WITH_UNDO, Gate.of(spec("create_task"), afterToolResult = false))
        assertEquals(Gate.CONFIRM, Gate.of(spec("delete_task"), afterToolResult = false))
        // After a tool result, a change could have been suggested by tool output: ask.
        assertEquals(Gate.CONFIRM, Gate.of(spec("create_task"), afterToolResult = true))
        assertEquals(Gate.RUN, Gate.of(spec("get_calendar"), afterToolResult = true))
        // Remote tools without annotations count as destructive (MCP default) and are never "local".
        val remote = ToolSpec.fromMcp(JSONObject("""{"name":"create_page","description":"Make a page","inputSchema":{"type":"object"}}"""),
            "notion_create_page", connectorId = "c1", host = "mcp.notion.com")
        assertEquals(Gate.CONFIRM, Gate.of(remote, afterToolResult = false))
    }

    @Test fun routerPicksRelevantConnectorTools() {
        val tools = (1..8).map { i ->
            ToolSpec("gh_tool$i", "Does github thing $i", mapOf("type" to "object"), true, false, false, "gh", "api.github.com")
        } + ToolSpec("notion_search", "Search pages in the Notion workspace", mapOf("type" to "object"), true, false, false, "n", "mcp.notion.com")
        val picked = Router.pick(tools, "find my notion pages about trips", "", mapOf("n" to "Notion", "gh" to "GitHub"))
        assertEquals("notion_search", picked.first().name)
        assertTrue(picked.size <= Router.MAX)
    }
}
