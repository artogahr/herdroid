package dev.herdroid.core.transcript

import dev.herdroid.core.transport.LocalHostTransport
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.io.File

class TranscriptFollowTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(30)

    private val source = TranscriptSource(LocalHostTransport())

    @Test
    fun resumesFromOffsetWithoutDuplicates() =
        runBlocking {
            val file = File.createTempFile("follow", ".jsonl").apply { writeText("one\ntwo ü\nthree\n") }
            val first = withTimeout(5_000) { source.follow(file.path).take(2).toList() }
            assertEquals(listOf("one", "two ü"), first.map { it.text })
            // Offsets count bytes, so the multi-byte character must not shift the resume point.
            assertEquals("one\ntwo ü\n".toByteArray().size.toLong(), first.last().endOffset)
            file.appendText("four\n")
            val rest = withTimeout(5_000) { source.follow(file.path, first.last().endOffset).take(2).toList() }
            assertEquals(listOf("three", "four"), rest.map { it.text })
        }

    @Test
    fun recentTailSkipsThePartialFirstLine() =
        runBlocking {
            val lines = (1..200).map { "line-$it-" + "x".repeat(50) }
            val file = File.createTempFile("recent", ".jsonl").apply { writeText(lines.joinToString("\n") + "\n") }
            val got = withTimeout(5_000) { source.followRecent(file.path, maxBytes = 1_000).take(5).toList() }
            // Every emitted line is whole: none starts mid-line.
            got.forEach { assertEquals(true, it.text in lines) }
            var last = 0L
            withTimeout(5_000) {
                source.followRecent(file.path, maxBytes = 1_000).takeWhile { it.endOffset < file.length() }.collect { last = it.endOffset }
            }
            // The final line ends exactly at the file size, which proves offsets stay aligned.
            assertEquals(file.length(), withTimeout(5_000) { source.follow(file.path, last).take(1).toList() }.single().endOffset)
        }

    @Test
    fun claudeProjectDirMatchesClaudeCode() {
        assertEquals("-Users-a-b-c-d", TranscriptSource.claudeProjectDir("/Users/a/b.c/d"))
    }
}
