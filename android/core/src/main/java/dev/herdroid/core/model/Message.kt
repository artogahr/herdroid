package dev.herdroid.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
enum class AgentKind { CLAUDE, CODEX, KIMI }

@Serializable
enum class Role { USER, ASSISTANT, SYSTEM, TOOL }

@Serializable
enum class MessageKind { TEXT, THINKING, TOOL_CALL, TOOL_RESULT, COMPACTION, STATUS, UNKNOWN }

@Serializable
enum class Phase { COMMENTARY, FINAL }

@Serializable
data class ToolInfo(
    val callId: String,
    val name: String? = null,
    val input: JsonElement? = null,
    val output: String? = null,
    val isError: Boolean = false,
)

@Serializable
data class Sidechain(
    val agentId: String,
    val agentType: String? = null,
)

/**
 * One normalized transcript entry. [id] is stable across re-parses of the same file so the
 * cache can upsert. [meta] entries (injected context, bookkeeping) are hidden by default;
 * [MessageKind.UNKNOWN] entries carry [raw] and mark the history as possibly incomplete.
 */
@Serializable
data class Message(
    val id: String,
    val ts: String? = null,
    val role: Role,
    val kind: MessageKind,
    val text: String? = null,
    val tool: ToolInfo? = null,
    val turnId: String? = null,
    val phase: Phase? = null,
    val sidechain: Sidechain? = null,
    val meta: Boolean = false,
    val raw: String? = null,
)
