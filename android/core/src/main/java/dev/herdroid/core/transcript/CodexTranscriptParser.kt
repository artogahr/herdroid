package dev.herdroid.core.transcript

import dev.herdroid.core.model.Message
import dev.herdroid.core.model.MessageKind
import dev.herdroid.core.model.Phase
import dev.herdroid.core.model.Role
import dev.herdroid.core.model.ToolInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

class CodexTranscriptParser : TranscriptParser {
    private val json = Json { isLenient = true }
    private val messages = linkedMapOf<String, Message>()
    private val toolCalls = mutableMapOf<String, String>()
    private var lineNumber = 0L
    private var turnId: String? = null

    override fun feed(line: String): List<Message> {
        lineNumber++
        val envelope =
            runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()
                ?: return listOf(unknown("codex-line-$lineNumber", line))
        val type = envelope.string("type")
        val payload = envelope.obj("payload") ?: JsonObject(emptyMap())
        if (type == "turn_context" || (type == "event_msg" && payload.string("type") == "task_started")) {
            payload.string("turn_id")?.let { turnId = it }
            return emptyList()
        }
        return when (type) {
            "event_msg" -> {
                parseEvent(payload, line)
            }

            "response_item" -> {
                parseResponseItem(payload, line)
            }

            "compacted" -> {
                listOf(
                    remember(
                        Message(
                            stableId("compaction"),
                            envelope.string("timestamp"),
                            Role.SYSTEM,
                            MessageKind.COMPACTION,
                            turnId = turnId,
                            meta = true,
                            raw = line,
                        ),
                    ),
                )
            }

            "session_meta", "turn_context", "token_usage_record", "world_state" -> {
                emptyList()
            }

            else -> {
                listOf(unknown(stableId(type ?: "unknown"), line))
            }
        }
    }

    private fun parseEvent(
        payload: JsonObject,
        raw: String,
    ): List<Message> {
        val eventType = payload.string("type")
        return when (eventType) {
            "user_message" -> {
                listOf(
                    remember(
                        Message(
                            stableId("user"),
                            role = Role.USER,
                            kind = MessageKind.TEXT,
                            text = payload.string("message"),
                            turnId = turnId,
                            raw = raw,
                        ),
                    ),
                )
            }

            "turn_aborted" -> {
                listOf(
                    remember(
                        Message(
                            stableId("turn-aborted"),
                            role = Role.SYSTEM,
                            kind = MessageKind.STATUS,
                            text = payload.string("reason") ?: "Turn aborted",
                            turnId = turnId,
                            raw = raw,
                        ),
                    ),
                )
            }

            "task_started", "task_complete", "item_completed", "token_count", "thread_settings_applied" -> {
                emptyList()
            }

            else -> {
                listOf(unknown(stableId(eventType ?: "unknown-event"), raw))
            }
        }
    }

    private fun parseResponseItem(
        payload: JsonObject,
        raw: String,
    ): List<Message> {
        val itemType = payload.string("type")
        val callId = payload.string("call_id")
        val id = stableId(itemType ?: "response", callId)
        return when (itemType) {
            "message" -> {
                val role = payload.string("role")
                if (role == "user") return emptyList()
                if (role != "assistant") return listOf(unknown(id, raw))
                val text =
                    (payload["content"] as? JsonArray)
                        ?.mapNotNull { part ->
                            (part as? JsonObject)?.takeIf { it.string("type") == "output_text" }?.string("text")
                        }?.joinToString("")
                        .orEmpty()
                if (text.isEmpty()) {
                    emptyList()
                } else {
                    listOf(
                        remember(
                            Message(
                                id,
                                envelopeTimestamp(raw),
                                Role.ASSISTANT,
                                MessageKind.TEXT,
                                text,
                                turnId = turnId,
                                phase =
                                    when (payload.string("phase")) {
                                        "commentary" -> Phase.COMMENTARY
                                        "final_answer" -> Phase.FINAL
                                        else -> null
                                    },
                                raw = raw,
                            ),
                        ),
                    )
                }
            }

            "reasoning" -> {
                val text =
                    (payload["summary"] as? JsonArray ?: JsonArray(emptyList()))
                        .mapNotNull { (it as? JsonObject)?.string("text") ?: it.textValue() }
                        .joinToString("\n")
                if (text.isNullOrEmpty()) {
                    emptyList()
                } else {
                    listOf(
                        remember(
                            Message(
                                id,
                                envelopeTimestamp(raw),
                                Role.ASSISTANT,
                                MessageKind.THINKING,
                                text,
                                turnId = turnId,
                                raw = raw,
                            ),
                        ),
                    )
                }
            }

            "function_call", "custom_tool_call" -> {
                val callId = payload.string("call_id") ?: id
                val input = payload["arguments"]?.parseJsonString() ?: payload["input"]
                val message =
                    Message(
                        id,
                        envelopeTimestamp(raw),
                        Role.ASSISTANT,
                        MessageKind.TOOL_CALL,
                        tool = ToolInfo(callId, payload.string("name"), input),
                        turnId = turnId,
                        raw = raw,
                    )
                toolCalls[callId] = id
                listOf(remember(message))
            }

            "function_call_output", "custom_tool_call_output" -> {
                val callId = payload.string("call_id") ?: return emptyList()
                val messageId = toolCalls[callId] ?: return emptyList()
                val prior = messages[messageId] ?: return emptyList()
                listOf(
                    remember(
                        prior.copy(
                            tool =
                                prior.tool?.copy(
                                    output = payload["output"].display(),
                                    isError = payload.string("is_error") == "true",
                                ),
                        ),
                    ),
                )
            }

            else -> {
                listOf(unknown(id, raw))
            }
        }
    }

    private fun stableId(
        type: String,
        callId: String? = null,
    ) = listOfNotNull("codex-$lineNumber-$type", callId).joinToString("-")

    private fun remember(message: Message): Message {
        messages[message.id] = message
        return message
    }

    private fun unknown(
        id: String,
        raw: String,
    ) = Message(id, role = Role.SYSTEM, kind = MessageKind.UNKNOWN, raw = raw)

    private fun envelopeTimestamp(raw: String): String? =
        runCatching {
            json.parseToJsonElement(raw).jsonObject.string("timestamp")
        }.getOrNull()

    private fun JsonObject.obj(key: String) = this[key] as? JsonObject

    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonElement?.textValue() = (this as? JsonPrimitive)?.contentOrNull

    private fun JsonElement?.parseJsonString(): JsonElement? {
        val value = this as? JsonPrimitive ?: return this
        if (!value.isString) return value
        return runCatching { json.parseToJsonElement(value.content) }.getOrElse { JsonPrimitive(value.content) }
    }

    private fun JsonElement?.display(): String? =
        when (this) {
            null -> null
            is JsonPrimitive -> contentOrNull
            else -> toString()
        }
}
