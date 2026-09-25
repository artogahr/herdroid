package dev.herdroid.core.thread

import java.util.UUID

/** A message the user sent that the transcript has not shown yet. */
data class Outgoing(
    val id: String,
    val text: String,
    val state: State,
    /** Transcript byte offset when sent; only user records after it can confirm this. */
    val sentAt: Long,
    val error: String? = null,
) {
    enum class State { SENDING, SUBMITTED, UNCLEAR, FAILED }
}

/**
 * Delivery states for sent messages. herdr confirms typing, not that the agent took the
 * message, so a message only counts as delivered once a new user record with the same text
 * shows up in the transcript. Nothing here ever resends on its own.
 */
class Outbox {
    var entries: List<Outgoing> = emptyList()
        private set

    fun queue(
        text: String,
        transcriptOffset: Long,
    ): Outgoing =
        Outgoing(UUID.randomUUID().toString(), text.trim(), Outgoing.State.SENDING, transcriptOffset).also {
            entries =
                entries + it
        }

    fun submitted(id: String) = update(id) { if (it.state == Outgoing.State.SENDING) it.copy(state = Outgoing.State.SUBMITTED) else it }

    fun failed(
        id: String,
        error: String,
    ) = update(id) { it.copy(state = Outgoing.State.FAILED, error = error) }

    /** The transcript did not show the message in time. */
    fun timedOut(id: String) = update(id) { if (it.state == Outgoing.State.SUBMITTED) it.copy(state = Outgoing.State.UNCLEAR) else it }

    fun dismiss(id: String) {
        entries = entries.filterNot { it.id == id }
    }

    /**
     * A user record appeared in the transcript ending at [lineEnd]. Confirms the oldest
     * matching message sent before it; returns whether one was confirmed.
     */
    fun onUserMessage(
        text: String,
        lineEnd: Long,
    ): Boolean {
        val trimmed = text.trim()
        val match =
            entries.firstOrNull {
                it.state != Outgoing.State.FAILED && it.text == trimmed && it.sentAt < lineEnd
            } ?: return false
        dismiss(match.id)
        return true
    }

    private fun update(
        id: String,
        change: (Outgoing) -> Outgoing,
    ) {
        entries = entries.map { if (it.id == id) change(it) else it }
    }
}
