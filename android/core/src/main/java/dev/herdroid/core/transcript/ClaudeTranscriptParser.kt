package dev.herdroid.core.transcript

import dev.herdroid.core.model.Message
import dev.herdroid.core.model.MessageKind
import dev.herdroid.core.model.Role
import dev.herdroid.core.model.Sidechain
import dev.herdroid.core.model.ToolInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

class ClaudeTranscriptParser : TranscriptParser {
    var title: String? = null
        private set

    private val json = Json { isLenient = true }
    private val messages = linkedMapOf<String, Message>()
    private val toolCalls = mutableMapOf<String, String>()
    private var lineNumber = 0

    override fun feed(line: String): List<Message> {
        lineNumber++
        val record =
            runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()
                ?: return listOf(unknown("claude-line-$lineNumber", line))
        when (record.string("type")) {
            "ai-title", "custom-title" -> {
                title = record.string("customTitle") ?: record.string("aiTitle") ?: record.string("title") ?: record.string("value")
                return emptyList()
            }

            "user" -> {
                return parseUser(record, line)
            }

            "assistant" -> {
                return parseAssistant(record, line)
            }

            "system" -> {
                if (record.string("subtype") == "compact_boundary") {
                    return remember(
                        Message(
                            id(record, "system"),
                            record.string("timestamp"),
                            Role.SYSTEM,
                            MessageKind.COMPACTION,
                            record.string("content"),
                            meta = true,
                            raw = line,
                        ),
                    )
                }
                return emptyList()
            }

            "attachment", "summary", "queue-operation", "last-prompt", "mode", "permission-mode", "agent-name",
            "pr-link", "cost-state", "worktree-state", "atis-latch", "bridge-session", "continued-in", "relocated",
            -> {
                return emptyList()
            }
        }
        if (record.string("type")?.startsWith("file-history-") == true) return emptyList()
        return listOf(unknown(id(record, "unknown"), line))
    }

    private fun parseUser(
        record: JsonObject,
        raw: String,
    ): List<Message> {
        val content = record.obj("message")?.get("content")
        val baseId = id(record, "user")
        val sidechain = record.sidechain()
        if (content is JsonPrimitive && content.isString) {
            val message =
                Message(
                    baseId,
                    record.string("timestamp"),
                    Role.USER,
                    MessageKind.TEXT,
                    content.contentOrNull,
                    sidechain = sidechain,
                    meta = record.boolean("isMeta"),
                    raw = raw,
                )
            return remember(message)
        }
        val blocks = content as? JsonArray ?: return listOf(unknown(baseId, raw))
        val output = mutableListOf<Message>()
        blocks.forEachIndexed { index, blockElement ->
            val block = blockElement as? JsonObject ?: return@forEachIndexed
            when (block.string("type")) {
                "text" -> {
                    output +=
                        remember(
                            Message(
                                "$baseId-$index",
                                record.string("timestamp"),
                                Role.USER,
                                MessageKind.TEXT,
                                block.string("text"),
                                sidechain = sidechain,
                                meta = record.boolean("isMeta"),
                                raw = raw,
                            ),
                        )
                }

                "image" -> {
                    output +=
                        remember(
                            Message(
                                "$baseId-$index",
                                record.string("timestamp"),
                                Role.USER,
                                MessageKind.TEXT,
                                "[image]",
                                sidechain = sidechain,
                                meta = record.boolean("isMeta"),
                                raw = raw,
                            ),
                        )
                }

                "tool_result" -> {
                    val callId = block.string("tool_use_id") ?: return@forEachIndexed
                    val messageId = toolCalls[callId] ?: return@forEachIndexed
                    val prior = messages[messageId] ?: return@forEachIndexed
                    val tool = prior.tool ?: return@forEachIndexed
                    val merged =
                        prior.copy(
                            tool =
                                tool.copy(
                                    output = block["content"].display(),
                                    isError = block.boolean("is_error"),
                                ),
                        )
                    output += remember(merged)
                }
            }
        }
        return output
    }

    private fun parseAssistant(
        record: JsonObject,
        raw: String,
    ): List<Message> {
        val content = record.obj("message")?.get("content") as? JsonArray ?: return emptyList()
        val output = mutableListOf<Message>()
        content.forEachIndexed { index, blockElement ->
            val block = blockElement as? JsonObject ?: return@forEachIndexed
            val baseId = id(record, "assistant")
            val blockId = if (content.size == 1) baseId else "$baseId-$index"
            val sidechain = record.sidechain()
            val meta = record.boolean("isMeta") || record.boolean("isCompactSummary")
            when (block.string("type")) {
                "text" -> {
                    output +=
                        remember(
                            Message(
                                blockId,
                                record.string("timestamp"),
                                Role.ASSISTANT,
                                MessageKind.TEXT,
                                block.string("text"),
                                sidechain = sidechain,
                                meta = meta,
                                raw = raw,
                            ),
                        )
                }

                "thinking" -> {
                    output +=
                        remember(
                            Message(
                                blockId,
                                record.string("timestamp"),
                                Role.ASSISTANT,
                                MessageKind.THINKING,
                                block.string("thinking"),
                                sidechain = sidechain,
                                meta = meta,
                                raw = raw,
                            ),
                        )
                }

                "tool_use" -> {
                    val callId = block.string("id") ?: blockId
                    val message =
                        Message(
                            blockId,
                            record.string("timestamp"),
                            Role.ASSISTANT,
                            MessageKind.TOOL_CALL,
                            tool = ToolInfo(callId, block.string("name"), block["input"]),
                            sidechain = sidechain,
                            meta = meta,
                            raw = raw,
                        )
                    toolCalls[callId] = blockId
                    output += remember(message)
                }
            }
        }
        if (record.boolean("isCompactSummary")) {
            output +=
                remember(
                    Message(
                        id(record, "compact"),
                        record.string("timestamp"),
                        Role.SYSTEM,
                        MessageKind.COMPACTION,
                        meta = true,
                        raw = raw,
                    ),
                )
        }
        return output
    }

    private fun JsonObject.sidechain(): Sidechain? =
        if (boolean("isSidechain")) {
            Sidechain(string("agentId") ?: string("agentName") ?: "sidechain", string("agentType"))
        } else {
            null
        }

    private fun remember(message: Message): List<Message> {
        messages[message.id] = message
        return listOf(message)
    }

    private fun unknown(
        id: String,
        raw: String,
    ) = Message(id, role = Role.SYSTEM, kind = MessageKind.UNKNOWN, raw = raw)

    private fun id(
        record: JsonObject,
        fallback: String,
    ) = record.string("uuid") ?: "claude-$fallback-$lineNumber"

    private fun JsonObject.obj(key: String) = this[key] as? JsonObject

    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.boolean(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull == "true"

    private fun JsonElement?.display(): String? =
        when (this) {
            null -> {
                null
            }

            is JsonPrimitive -> {
                contentOrNull
            }

            is JsonArray -> {
                joinToString("\n") { element ->
                    (element as? JsonObject)?.string("text") ?: element.toString()
                }
            }

            else -> {
                toString()
            }
        }
}
