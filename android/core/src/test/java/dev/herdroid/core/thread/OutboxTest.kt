package dev.herdroid.core.thread

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutboxTest {
    @Test
    fun confirmsOnlyWithARecordWrittenAfterSending() {
        val box = Outbox()
        val out = box.queue("run the tests", transcriptOffset = 1_000)
        box.submitted(out.id)
        // The same text already in history, before the send, must not count.
        assertFalse(box.onUserMessage("run the tests", lineEnd = 900))
        assertEquals(1, box.entries.size)
        assertTrue(box.onUserMessage("run the tests", lineEnd = 1_200))
        assertTrue(box.entries.isEmpty())
    }

    @Test
    fun sameTextSentTwiceNeedsTwoRecords() {
        val box = Outbox()
        val a = box.queue("yes", 10)
        val b = box.queue("yes", 20)
        box.submitted(a.id)
        box.submitted(b.id)
        assertTrue(box.onUserMessage("yes", 30))
        assertEquals(listOf(b.id), box.entries.map { it.id })
    }

    @Test
    fun timeoutMarksOnlySubmittedAsUnclear() {
        val box = Outbox()
        val sending = box.queue("a", 0)
        val sent = box.queue("b", 0)
        box.submitted(sent.id)
        box.timedOut(sending.id)
        box.timedOut(sent.id)
        assertEquals(listOf(Outgoing.State.SENDING, Outgoing.State.UNCLEAR), box.entries.map { it.state })
        // An unclear message is still confirmed if the agent shows it later.
        assertTrue(box.onUserMessage("b", 5))
    }

    @Test
    fun failedMessagesAreNeverConfirmed() {
        val box = Outbox()
        val out = box.queue("x", 0)
        box.failed(out.id, "agent_blocked")
        assertFalse(box.onUserMessage("x", 10))
        assertEquals("agent_blocked", box.entries.single().error)
    }

    @Test
    fun promptTailKeepsTheQuestionAtTheBottom() {
        val screen = "\n\nold output\n" + (1..20).joinToString("\n") { "l$it" } + "\n  Do you want to proceed?\n  1. Yes\n  2. No   \n\n\n"
        val tail = promptTail(screen, maxLines = 4)
        assertEquals("l19\nl20\n  Do you want to proceed?\n  1. Yes\n  2. No".lines().takeLast(4).joinToString("\n"), tail)
    }
}
