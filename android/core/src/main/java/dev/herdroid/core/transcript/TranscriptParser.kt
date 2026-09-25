package dev.herdroid.core.transcript

import dev.herdroid.core.model.Message

/**
 * Incremental JSONL transcript parser. Callers feed complete lines in file order, possibly
 * across many calls as the file grows. A parser may emit an updated version of an earlier
 * message (same [Message.id]) when later lines complete it, e.g. a tool result arriving.
 */
interface TranscriptParser {
    fun feed(line: String): List<Message>

    /** The conversation's own title, when the agent records one. */
    val title: String? get() = null
}
