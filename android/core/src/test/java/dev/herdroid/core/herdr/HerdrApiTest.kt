package dev.herdroid.core.herdr

import dev.herdroid.core.transport.ExecChannel
import dev.herdroid.core.transport.HostTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A transport that answers every exec with fixed lines and records what was written. */
private class CannedTransport(
    private val replies: List<String>,
) : HostTransport {
    val commands = ArrayList<String>()
    val written = ArrayList<String>()
    var closed = 0

    override suspend fun exec(
        command: String,
        stopOnEof: Boolean,
    ): ExecChannel {
        commands += command
        return object : ExecChannel {
            override val lines: Flow<String> = replies.asFlow()

            override suspend fun write(text: String) {
                written += text
            }

            override suspend fun closeInput() {}

            override fun close() {
                closed++
            }
        }
    }

    override suspend fun run(command: String) = replies.joinToString("\n")

    override fun close() {}
}

class HerdrApiTest {
    @Test
    fun sendsOneRequestPerChannelAndClosesIt() =
        runBlocking {
            val t = CannedTransport(listOf("""{"id":"h1","result":{"type":"pong"}}"""))
            val result = HerdrApi(t, "/opt/my herdr/herdr").request("ping")
            assertEquals("pong", result["type"]!!.jsonPrimitive.content)
            assertEquals(listOf("'/opt/my herdr/herdr' remote-api-bridge"), t.commands)
            val req =
                kotlinx.serialization.json.Json
                    .parseToJsonElement(t.written.single())
                    .jsonObject
            assertEquals("ping", req["method"]!!.jsonPrimitive.content)
            assertEquals(1, t.closed)
        }

    @Test
    fun surfacesHerdrErrors() =
        runBlocking {
            val t = CannedTransport(listOf("""{"id":"h1","error":{"code":"agent_blocked","message":"waiting"}}"""))
            val e = runCatching { HerdrApi(t, "herdr").request("agent.prompt", buildJsonObject { put("target", "x") }) }.exceptionOrNull()
            assertTrue(e is HerdrApiException)
            assertEquals("agent_blocked", (e as HerdrApiException).code)
        }

    @Test
    fun subscriptionSkipsTheStartedAck() =
        runBlocking {
            val t =
                CannedTransport(
                    listOf(
                        """{"id":"s1","result":{"type":"subscription_started"}}""",
                        """{"event":"pane_created","data":{"type":"pane_created"}}""",
                        """{"event":"pane_closed","data":{"type":"pane_closed"}}""",
                    ),
                )
            val events = HerdrApi(t, "herdr").subscribe(listOf(buildJsonObject { put("type", "pane.created") })).toList()
            assertEquals(listOf("pane_created", "pane_closed"), events.map { it["event"]!!.jsonPrimitive.content })
        }

    @Test
    fun quotesPathsForTheShell() {
        assertEquals("""'it'\''s'""", shellQuote("it's"))
    }

    @Test
    fun paneOrderReadsRowsThenColumns() {
        val layout =
            Layout(
                "t",
                listOf(
                    LayoutPane("right", Rect(50, 0, 50, 20)),
                    LayoutPane("bottom", Rect(0, 20, 100, 20)),
                    LayoutPane("left", Rect(0, 0, 50, 20)),
                ),
            )
        assertEquals(listOf("left", "right", "bottom"), layout.paneOrder())
    }
}
