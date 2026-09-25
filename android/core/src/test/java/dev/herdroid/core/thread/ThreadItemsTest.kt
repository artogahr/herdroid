package dev.herdroid.core.thread

import dev.herdroid.core.model.Message
import dev.herdroid.core.model.MessageKind
import dev.herdroid.core.model.Role
import dev.herdroid.core.model.ToolInfo
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class ThreadItemsTest {
    private fun bash(id: String) =
        Message(id, role = Role.ASSISTANT, kind = MessageKind.TOOL_CALL, tool = ToolInfo(id, "Bash", buildJsonObject { put("command", "ls") }))

    private fun read(id: String) =
        Message(id, role = Role.ASSISTANT, kind = MessageKind.TOOL_CALL, tool = ToolInfo(id, "Read", buildJsonObject { put("file_path", "/a/b/Theme.kt") }))

    @Test
    fun foldsToolRunsBetweenMessages() {
        val items =
            ThreadItems.build(
                listOf(
                    Message("u1", role = Role.USER, kind = MessageKind.TEXT, text = "hi"),
                    Message("t1", role = Role.ASSISTANT, kind = MessageKind.THINKING, text = "hmm"),
                    bash("b1"),
                    bash("b2"),
                    read("r1"),
                    Message("a1", role = Role.ASSISTANT, kind = MessageKind.TEXT, text = "done"),
                    Message("m1", role = Role.USER, kind = MessageKind.TEXT, text = "<system-reminder>x</system-reminder>"),
                ),
            )
        assertEquals(listOf("UserText", "Activity", "AssistantText"), items.map { it::class.simpleName })
        val activity = items[1] as ThreadItem.Activity
        assertEquals("Ran 2 commands, read 1 file", ToolSummary.summarize(activity.tools))
    }

    @Test
    fun extractsCodexExecCommand() {
        val js = "const r = await tools.exec_command({cmd:\"git status --short\", workdir: \"/x\"})"
        val d = ToolSummary.describe(ToolInfo("c", "exec", JsonPrimitive(js)))
        assertEquals(ToolDescription(ToolVerb.RAN, "git status --short"), d)
    }

    @Test
    fun describesPatchFiles() {
        val patch = "*** Begin Patch\n*** Update File: src/a/Main.kt\n*** Add File: b.md\n"
        assertEquals("Main.kt, b.md", ToolSummary.describe(ToolInfo("c", "apply_patch", JsonPrimitive(patch))).detail)
    }
}
