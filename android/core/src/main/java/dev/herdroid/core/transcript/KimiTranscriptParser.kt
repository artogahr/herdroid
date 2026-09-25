package dev.herdroid.core.transcript

import dev.herdroid.core.model.Message
import dev.herdroid.core.model.MessageKind
import dev.herdroid.core.model.Role
import dev.herdroid.core.model.ToolInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.time.Instant

/**
 * Kimi Code writes one `wire.jsonl` per agent under
 * `~/.kimi-code/sessions/<workdir>/<session>/agents/main/`. User prompts are `turn.prompt`
 * records; model output and tools are `context.append_loop_event` records.
 */
class KimiTranscriptParser : TranscriptParser {
    private val json = Json { ignoreUnknownKeys = true }
    private val calls = HashMap<String, Message>()

    override fun feed(line: String): List<Message> {
        val record =
            runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()
                ?: return listOf(Message(contentId("malformed", line), role = Role.SYSTEM, kind = MessageKind.UNKNOWN, raw = line))
        val type = record.string("type") ?: return emptyList()
        val ts = (record["time"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()?.let { Instant.ofEpochMilli(it).toString() }
        return when (type) {
            "turn.prompt" -> {
                if (record.obj("origin")?.string("kind") != "user") return emptyList()
                val text = (record["input"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.string("text") }?.joinToString("\n")
                listOf(Message(record.string("promptId") ?: contentId("prompt", line), ts, Role.USER, MessageKind.TEXT, text))
            }

            "context.append_loop_event" -> {
                event(record.obj("event") ?: return emptyList(), ts, line)
            }

            "turn.ended" -> {
                val reason = record.string("reason")
                if (reason == null || reason == "completed") {
                    emptyList()
                } else {
                    listOf(Message(contentId("end", line), ts, Role.SYSTEM, MessageKind.STATUS, "Turn $reason"))
                }
            }

            else -> {
                emptyList()
            }
        }
    }

    private fun event(
        e: JsonObject,
        ts: String?,
        raw: String,
    ): List<Message> {
        val id = e.string("uuid") ?: contentId("event", raw)
        val turn = e.string("turnId")
        return when (e.string("type")) {
            "content.part" -> {
                val part = e.obj("part") ?: return emptyList()
                when (part.string("type")) {
                    "text" -> listOf(Message(id, ts, Role.ASSISTANT, MessageKind.TEXT, part.string("text"), turnId = turn))
                    "think" -> listOf(Message(id, ts, Role.ASSISTANT, MessageKind.THINKING, part.string("think"), turnId = turn))
                    else -> emptyList()
                }
            }

            "tool.call" -> {
                val callId = e.string("toolCallId") ?: id
                val m =
                    Message(
                        id,
                        ts,
                        Role.ASSISTANT,
                        MessageKind.TOOL_CALL,
                        tool = ToolInfo(callId, e.string("name"), e["args"]),
                        turnId = turn,
                    )
                calls[callId] = m
                listOf(m)
            }

            "tool.result" -> {
                val callId = e.string("toolCallId") ?: return emptyList()
                val call = calls[callId] ?: return emptyList()
                val result = e.obj("result")
                val output = (result?.get("output") as? JsonPrimitive)?.contentOrNull ?: result?.get("output")?.toString()
                val updated =
                    call.copy(
                        tool = call.tool?.copy(output = output, isError = result?.string("isError") == "true"),
                    )
                calls[callId] = updated
                listOf(updated)
            }

            "step.begin", "step.end" -> {
                emptyList()
            }

            else -> {
                listOf(Message(id, ts, Role.SYSTEM, MessageKind.UNKNOWN, raw = raw))
            }
        }
    }

    /** Ids must not depend on where parsing started, so fallbacks hash the record itself. */
    private fun contentId(
        kind: String,
        line: String,
    ) = "kimi-$kind-${Integer.toHexString(line.hashCode())}"

    private fun JsonObject.obj(key: String) = this[key] as? JsonObject

    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
}
