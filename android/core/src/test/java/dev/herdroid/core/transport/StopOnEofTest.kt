package dev.herdroid.core.transport

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.io.File

class StopOnEofTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(30)

    private val transport = LocalHostTransport()

    private fun running(marker: String): Boolean {
        val ps = ProcessBuilder("ps", "-axo", "command").start()
        return ps.inputStream
            .bufferedReader()
            .readLines()
            .any { marker in it && "ps -axo" !in it }
    }

    @Test
    fun killsCommandAndChildrenOnEof() =
        runBlocking {
            val marker = "herdroid-eof-${System.nanoTime()}"
            val file = File.createTempFile(marker, ".log").apply { writeText("first\n") }
            // A subshell plus tail: both must die, not just the direct child.
            val channel = transport.exec("sh -c 'tail -F ${file.path}; true'", stopOnEof = true)
            // Stopping collection closes the channel by design, so keep collecting meanwhile.
            val seen = kotlinx.coroutines.CompletableDeferred<String>()
            val reader = launch { channel.lines.collect { seen.complete(it) } }
            assertEquals("first", withTimeout(5_000) { seen.await() })
            assertTrue("command should be running", running(marker))
            channel.closeInput()
            var gone = false
            repeat(50) {
                if (!running(marker)) {
                    gone = true
                    return@repeat
                }
                delay(100)
            }
            reader.cancel()
            channel.close()
            assertTrue("tail survived stdin EOF", gone)
        }

    @Test
    fun endsWhenCommandExitsOnItsOwn() =
        runBlocking {
            val channel = transport.exec("printf 'a\\nb\\n'", stopOnEof = true)
            assertEquals(listOf("a", "b"), withTimeout(5_000) { channel.lines.toList() })
            channel.close()
        }
}
