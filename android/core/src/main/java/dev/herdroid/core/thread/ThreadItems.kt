package dev.herdroid.core.thread

import dev.herdroid.core.model.Message
import dev.herdroid.core.model.MessageKind
import dev.herdroid.core.model.Role

/** What the chat screen shows: messages, with tool work folded between them. */
sealed interface ThreadItem {
    val key: String

    data class UserText(
        override val key: String,
        val text: String,
    ) : ThreadItem

    data class AssistantText(
        override val key: String,
        val text: String,
    ) : ThreadItem

    /** A run of tool calls and thinking between two messages. */
    data class Activity(
        override val key: String,
        val steps: List<Message>,
    ) : ThreadItem {
        val tools get() = steps.mapNotNull { it.tool }
        val failed get() = tools.count { it.isError }
    }

    data class Notice(
        override val key: String,
        val text: String,
    ) : ThreadItem
}

object ThreadItems {
    fun build(messages: List<Message>): List<ThreadItem> {
        val items = ArrayList<ThreadItem>()
        val run = ArrayList<Message>()

        fun flush() {
            if (run.isEmpty()) return
            items += ThreadItem.Activity("activity-" + run.first().id, run.toList())
            run.clear()
        }
        for (m in messages) {
            if (m.meta || m.sidechain != null) continue
            when {
                m.kind == MessageKind.TEXT && m.role == Role.USER -> {
                    val text = m.text?.trim().orEmpty()
                    val notice = noticeFor(text)
                    flush()
                    when {
                        notice != null -> items += ThreadItem.Notice(m.id, notice)
                        text.isNotEmpty() && !text.startsWith("<") -> items += ThreadItem.UserText(m.id, text)
                    }
                }

                m.kind == MessageKind.TEXT && m.role == Role.ASSISTANT -> {
                    val text = m.text?.trim().orEmpty()
                    if (text.isEmpty()) continue
                    flush()
                    items += ThreadItem.AssistantText(m.id, text)
                }

                m.kind == MessageKind.TOOL_CALL || m.kind == MessageKind.THINKING || m.kind == MessageKind.TOOL_RESULT -> {
                    run += m
                }

                m.kind == MessageKind.COMPACTION -> {
                    flush()
                    items += ThreadItem.Notice(m.id, "Conversation compacted")
                }

                m.kind == MessageKind.STATUS -> {
                    flush()
                    items += ThreadItem.Notice(m.id, m.text ?: "Turn ended")
                }

                m.kind == MessageKind.UNKNOWN -> {}
            }
        }
        flush()
        return items
    }

    private fun noticeFor(text: String): String? =
        when {
            text.startsWith("[Request interrupted") -> {
                "Interrupted"
            }

            text.startsWith("<command-name>") -> {
                Regex("<command-name>(.*?)</command-name>")
                    .find(text)
                    ?.groupValues
                    ?.get(1)
                    ?.let { "Ran $it" }
            }

            else -> {
                null
            }
        }
}
