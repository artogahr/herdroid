package dev.herdroid.core.herdr

import dev.herdroid.core.transport.LocalHostTransport
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** Talks to the real herdr server on this machine. Run with HERDROID_LIVE=1. */
class HerdrApiLiveTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(30)

    private val transport = LocalHostTransport()

    private fun live(block: suspend () -> Unit) {
        assumeTrue(System.getenv("HERDROID_LIVE") == "1")
        runBlocking { withTimeout(20_000) { block() } }
    }

    @Test
    fun locatesHerdrAndBridge() =
        live {
            val path = HerdrApi.locateHerdr(transport)
            assertTrue(path, path.endsWith("/herdr"))
            assertTrue(HerdrApi.checkBridge(transport, path))
        }

    @Test
    fun readsSnapshot() =
        live {
            val snapshot = HerdrApi(transport, HerdrApi.locateHerdr(transport)).snapshot()
            assertEquals(22, snapshot.protocol)
            assertTrue(snapshot.workspaces.isNotEmpty())
            val agents = snapshot.panes.filter { it.agentSession != null }
            println("workspaces=${snapshot.workspaces.size} panes=${snapshot.panes.size} agents=${agents.size}")
            agents.forEach { println("  ${it.id} ${it.agent} ${it.agentStatus} ${it.title}") }
        }

    @Test
    fun reportsErrors() =
        live {
            val api = HerdrApi(transport, HerdrApi.locateHerdr(transport))
            val error = runCatching { api.request("pane.get", buildJsonObject { put("pane_id", "w999:p999") }) }.exceptionOrNull()
            assertTrue(error.toString(), error is HerdrApiException)
        }

    @Test
    fun subscriptionStarts() =
        live {
            val api = HerdrApi(transport, HerdrApi.locateHerdr(transport))
            val pane = System.getenv("HERDR_PANE_ID") ?: return@live
            // A per-pane status subscription emits immediately when the pane already matches.
            val status =
                api
                    .snapshot()
                    .panes
                    .first { it.id == pane }
                    .agentStatus ?: return@live
            val sub =
                buildJsonObject {
                    put("type", "pane.agent_status_changed")
                    put("pane_id", pane)
                    put("agent_status", status.name.lowercase())
                }
            val event = api.subscribe(listOf(sub)).first()
            println(event)
        }
}
