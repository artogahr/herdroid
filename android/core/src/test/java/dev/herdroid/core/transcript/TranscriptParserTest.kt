package dev.herdroid.core.transcript

import dev.herdroid.core.model.MessageKind
import dev.herdroid.core.model.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptParserTest {
    private fun fixture(name: String) =
        javaClass
            .getResource("/fixtures/$name")!!
            .readText()
            .trimEnd()
            .lines()

    private fun parse(
        parser: TranscriptParser,
        lines: List<String>,
    ) = lines.flatMap(parser::feed)

    @Test
    fun claudeParsesKindsAndMergesToolResults() {
        val parser = ClaudeTranscriptParser()
        val messages = parse(parser, fixture("claude.jsonl")).associateBy { it.id }.values

        assertEquals(1, messages.count { it.kind == MessageKind.THINKING })
        assertEquals(1, messages.count { it.kind == MessageKind.TOOL_CALL })
        assertEquals(1, messages.count { it.kind == MessageKind.COMPACTION })
        assertTrue(messages.any { it.kind == MessageKind.TOOL_CALL && it.tool?.output == "[scrubbed]" })
        assertEquals("[scrubbed]", parser.title)
        assertTrue(messages.any { it.sidechain != null })
    }

    @Test
    fun claudeMarksMetaAndSkipsBookkeeping() {
        val messages = parse(ClaudeTranscriptParser(), fixture("claude.jsonl"))

        assertTrue(messages.any { it.role.name == "USER" && it.meta })
        assertFalse(messages.any { it.kind == MessageKind.UNKNOWN })
    }

    @Test
    fun codexParsesPhasesThinkingToolsAndAborts() {
        val messages = parse(CodexTranscriptParser(), fixture("codex.jsonl"))

        assertTrue(messages.any { it.role.name == "USER" })
        assertTrue(messages.any { it.phase == Phase.COMMENTARY })
        assertTrue(messages.any { it.phase == Phase.FINAL })
        assertTrue(messages.any { it.kind == MessageKind.THINKING })
        assertTrue(messages.any { it.kind == MessageKind.STATUS && it.text == "[scrubbed]" })
        assertTrue(messages.any { it.kind == MessageKind.TOOL_CALL && it.tool?.output != null })
        assertTrue(messages.all { it.id.startsWith("codex-") })
    }

    @Test
    fun codexSkipsInjectedUserContextAndMergesCallsByCallId() {
        val parser = CodexTranscriptParser()
        val lines = fixture("codex.jsonl")
        val messages = parse(parser, lines)

        assertFalse(messages.any { it.raw?.contains("\"role\":\"user\"") == true })
        val calls = messages.filter { it.kind == MessageKind.TOOL_CALL }
        assertTrue(
            calls.any { message ->
                message.tool?.let { tool -> tool.callId.startsWith("call_") && tool.output != null } == true
            },
        )
        assertTrue(calls.all { message -> message.tool?.let { call -> call.callId in message.id } == true })
        assertNotNull(messages.firstOrNull { it.turnId != null })
    }

    @Test
    fun unknownRecordsKeepTheirRawLine() {
        val raw = "{\"type\":\"future_record\",\"value\":\"kept\"}"
        val claude = ClaudeTranscriptParser().feed(raw).single()
        val codex = CodexTranscriptParser().feed("{\"type\":\"future_envelope\",\"payload\":{}} ").single()

        assertEquals(MessageKind.UNKNOWN, claude.kind)
        assertEquals(raw, claude.raw)
        assertEquals(MessageKind.UNKNOWN, codex.kind)
        assertNotNull(codex.raw)
    }

    @Test
    fun incrementalAndRepeatedFeedsAreEquivalent() {
        val lines = fixture("claude.jsonl")
        val incremental = parse(ClaudeTranscriptParser(), lines)
        val repeatedParser = ClaudeTranscriptParser()
        val repeated = lines.flatMap { repeatedParser.feed(it) }

        assertEquals(incremental, repeated)
    }

    @Test
    fun malformedLastLineEmitsUnknownInsteadOfThrowing() {
        val parser = CodexTranscriptParser()

        val result = parser.feed("{truncated")

        assertEquals(MessageKind.UNKNOWN, result.single().kind)
        assertNull(result.single().text)
    }
}
