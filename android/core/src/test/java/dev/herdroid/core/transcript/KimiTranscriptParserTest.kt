package dev.herdroid.core.transcript

import dev.herdroid.core.model.Message
import dev.herdroid.core.model.MessageKind
import dev.herdroid.core.model.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class KimiTranscriptParserTest {
    private fun parse(): List<Message> {
        val parser = KimiTranscriptParser()
        val byId = LinkedHashMap<String, Message>()
        javaClass
            .getResource("/fixtures/kimi.jsonl")!!
            .readText()
            .lines()
            .filter { it.isNotBlank() }
            .forEach { line -> parser.feed(line).forEach { byId[it.id] = it } }
        return byId.values.toList()
    }

    @Test
    fun readsPromptTextThinkingAndTools() {
        val messages = parse()
        assertEquals(
            listOf(
                MessageKind.TEXT to Role.USER,
                MessageKind.THINKING to Role.ASSISTANT,
                MessageKind.TOOL_CALL to Role.ASSISTANT,
                MessageKind.TEXT to Role.ASSISTANT,
            ),
            messages.map { it.kind to it.role },
        )
    }

    @Test
    fun mergesToolResultIntoCall() {
        val call = parse().single { it.kind == MessageKind.TOOL_CALL }
        assertNotNull(call.tool?.output)
    }

    @Test
    fun skipsInjectedContext() {
        assertEquals(1, parse().count { it.role == Role.USER })
    }
}
