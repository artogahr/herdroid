package dev.herdroid.core.herdr

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class LaunchersTest {
    // Trimmed from a real `integration.list` answer (herdr 0.9.1).
    private val result =
        Json
            .parseToJsonElement(
                """
                {"type":"integration_list","integrations":[
                  {"target":"pi","label":"pi","command":"pi","available":false,"state":"not_installed"},
                  {"target":"claude","label":"claude","command":"claude","available":true,"state":"outdated"},
                  {"target":"codex","label":"codex","command":"codex","available":true,"state":"current"},
                  {"target":"kimi","label":"kimi","command":"kimi","available":true,"state":"outdated"},
                  {"target":"opencode","label":"opencode","command":"opencode","available":true,"state":"not_installed"},
                  {"target":"cursor","label":"cursor","command":"cursor-agent","available":true,"state":"not_installed"},
                  {"target":"antigravity_cli","label":"antigravity-cli","command":"agy","available":true,"state":"not_installed"},
                  {"target":"newagent","label":"newagent","command":"newagent","available":true,"state":"current"}
                ]}
                """,
            ).jsonObject

    @Test
    fun installedAgentsBecomeKindsAgentStartAccepts() {
        val launchers = Launchers.fromIntegrations(result)
        assertEquals(listOf("claude", "codex", "kimi", "opencode", "cursor", "agy", "newagent"), launchers.map { it.kind })
        assertEquals("Antigravity", launchers.first { it.kind == "agy" }.label)
        // An agent this version does not know yet still gets a button, under herdr's label.
        assertEquals("newagent", launchers.last().label)
    }

    @Test
    fun lastUsedFirstThenChatAgentsThenByName() {
        val sorted =
            Launchers.sorted(
                Launchers.fromIntegrations(result),
                lastUsed = "opencode",
                chatKinds = setOf("claude", "codex", "kimi"),
            )
        assertEquals(listOf("opencode", "claude", "codex", "kimi", "agy", "cursor", "newagent"), sorted.map { it.kind })
    }

    @Test
    fun namesAreUniqueAndValid() {
        assertEquals("claude", Launchers.freeName("claude", emptySet()))
        assertEquals("claude-3", Launchers.freeName("claude", setOf("claude", "claude-2")))
        assertEquals("antigravity-cli", Launchers.freeName("Antigravity CLI".replace(' ', '-'), emptySet()))
        assertEquals("a2x", Launchers.freeName("2x", emptySet()))
    }
}
