package dev.herdroid.core.transcript

import dev.herdroid.core.herdr.HerdrApi
import dev.herdroid.core.model.AgentKind
import dev.herdroid.core.transport.LocalHostTransport
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test

class LocateByFolderTest {
    @Test
    fun parsesPsElapsedTime() {
        assertEquals(89L, TranscriptSource.parseEtime("01:29"))
        assertEquals(3_723L, TranscriptSource.parseEtime(" 01:02:03"))
        assertEquals(2 * 86_400L + 3_723L, TranscriptSource.parseEtime("2-01:02:03"))
        assertNull(TranscriptSource.parseEtime(""))
    }

    /** Every live agent pane without a herdr session should resolve by folder or stay unresolved. Prints only ids. */
    @Test
    fun resolvesAgentsHerdrLostTrackOf() {
        assumeTrue(System.getenv("HERDROID_LIVE") == "1")
        runBlocking {
            val transport = LocalHostTransport()
            val api = HerdrApi(transport, HerdrApi.locateHerdr(transport))
            val snap = api.snapshot()
            val known = snap.panes.mapNotNull { it.agentSession?.value }.toSet()
            val source = TranscriptSource(transport)
            for (pane in snap.panes.filter { it.agentSession == null && AgentKind.of(it.agent) != null }) {
                val info = api.request("pane.process_info", buildJsonObject { put("pane_id", pane.id) })
                val pid =
                    info["process_info"]!!
                        .jsonObject["foreground_processes"]!!
                        .jsonArray
                        .firstOrNull()
                        ?.jsonObject
                        ?.get("pid")
                        ?.jsonPrimitive
                        ?.int ?: continue
                val found = source.locateByFolder(AgentKind.of(pane.agent)!!, pane.cwd ?: continue, pid, known)
                println("${pane.id} ${pane.agent} pid=$pid -> ${found?.substringAfterLast('/')}")
            }
        }
    }
}
