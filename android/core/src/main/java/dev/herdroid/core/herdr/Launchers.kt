package dev.herdroid.core.herdr

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** An agent CLI installed on the server that a new tab can start. */
data class Launcher(
    /** What `agent.start` takes as `kind`. */
    val kind: String,
    val label: String,
)

object Launchers {
    /**
     * The kinds `agent.start` accepts (from `herdr agent` in 0.9). Integration targets mostly
     * equal their kind, but not always: the target "antigravity_cli" is kind "agy", its command.
     */
    private val kinds =
        setOf(
            "pi",
            "claude",
            "codex",
            "gemini",
            "cursor",
            "devin",
            "agy",
            "cline",
            "omp",
            "mastracode",
            "opencode",
            "copilot",
            "kimi",
            "kiro",
            "droid",
            "amp",
            "grok",
            "hermes",
            "kilo",
            "qodercli",
            "qwen",
            "letta",
            "maki",
            "muse",
        )

    private val labels =
        mapOf(
            "claude" to "Claude Code",
            "codex" to "Codex",
            "kimi" to "Kimi Code",
            "opencode" to "OpenCode",
            "cursor" to "Cursor",
            "agy" to "Antigravity",
            "copilot" to "Copilot",
            "gemini" to "Gemini",
            "droid" to "Droid",
            "qwen" to "Qwen Code",
            "grok" to "Grok",
        )

    /** The agents an `integration.list` result reports as installed. */
    fun fromIntegrations(result: JsonObject): List<Launcher> =
        result["integrations"]
            ?.jsonArray
            .orEmpty()
            .map { it.jsonObject }
            .filter { it["available"]?.jsonPrimitive?.booleanOrNull == true }
            .mapNotNull { entry ->
                val names = listOf("target", "command", "label").mapNotNull { entry[it]?.jsonPrimitive?.content }
                val kind = names.firstOrNull { it in kinds } ?: names.firstOrNull() ?: return@mapNotNull null
                Launcher(kind, labels[kind] ?: entry["label"]?.jsonPrimitive?.content ?: kind)
            }.distinctBy { it.kind }

    /** The last used agent first, then those that open as a chat, then by name. */
    fun sorted(
        launchers: List<Launcher>,
        lastUsed: String?,
        chatKinds: Set<String>,
    ): List<Launcher> =
        launchers.sortedWith(
            compareBy<Launcher>({ it.kind != lastUsed }, { it.kind !in chatKinds }, { it.label.lowercase() }),
        )

    /**
     * A unique agent name for [kind]: the kind itself, or the kind with the first free number.
     * herdr requires `[a-z][a-z0-9_-]{0,31}` and uniqueness among live agents.
     */
    fun freeName(
        kind: String,
        taken: Set<String>,
    ): String {
        val base =
            kind
                .lowercase()
                .replace(Regex("[^a-z0-9_-]"), "-")
                .let { if (it.firstOrNull()?.isLetter() == true) it else "a$it" }
                .take(28)
        if (base !in taken) return base
        return generateSequence(2) { it + 1 }.map { "$base-$it" }.first { it !in taken }
    }
}
