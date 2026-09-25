package dev.herdroid.core.transcript

import dev.herdroid.core.herdr.HerdrApi
import dev.herdroid.core.model.AgentKind
import dev.herdroid.core.model.Message
import dev.herdroid.core.transport.LocalHostTransport
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** Rebuilds every live Claude/Codex conversation on this machine. Prints structure only. */
class TranscriptSourceLiveTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(120)

    @Test
    fun rebuildsLiveConversations() {
        assumeTrue(System.getenv("HERDROID_LIVE") == "1")
        runBlocking {
            val transport = LocalHostTransport()
            val api = HerdrApi(transport, HerdrApi.locateHerdr(transport))
            val source = TranscriptSource(transport)
            val agents =
                api.snapshot().panes.mapNotNull { pane ->
                    val session = pane.agentSession ?: return@mapNotNull null
                    val kind = AgentKind.entries.firstOrNull { it.name.equals(session.agent, ignoreCase = true) } ?: return@mapNotNull null
                    Triple(pane, kind, session.value)
                }
            assumeTrue(agents.isNotEmpty())
            var rebuilt = 0
            for ((pane, kind, sessionId) in agents) {
                val path = source.locate(kind, sessionId, pane.cwd)
                if (path == null) {
                    println("${pane.id} $kind: transcript not found")
                    continue
                }
                val size = transport.run("wc -c < '${path.replace("'", "'\\''")}'").trim().toLong()
                val parser = TranscriptSource.parserFor(kind)
                val messages = LinkedHashMap<String, Message>()
                var end = 0L
                withTimeoutOrNull(20_000) {
                    source
                        .follow(path)
                        .takeWhile { line ->
                            parser.feed(line.text).forEach { messages[it.id] = it }
                            end = line.endOffset
                            end < size
                        }.collect {}
                }
                assertTrue("$path: read $end of $size bytes", end >= size)
                val visible = messages.values.filterNot { it.meta }
                val kinds = visible.groupingBy { it.kind }.eachCount()
                println("${pane.id} $kind lines->messages=${messages.size} visible=${visible.size} $kinds")
                val unknownTypes =
                    visible
                        .filter { it.kind == dev.herdroid.core.model.MessageKind.UNKNOWN }
                        .groupingBy { m ->
                            val o =
                                runCatching {
                                    kotlinx.serialization.json.Json
                                        .parseToJsonElement(m.raw ?: "")
                                }.getOrNull()
                            val obj = o as? kotlinx.serialization.json.JsonObject
                            val payload = obj?.get("payload") as? kotlinx.serialization.json.JsonObject
                            listOfNotNull(obj?.get("type"), obj?.get("subtype"), payload?.get("type")).joinToString("/")
                        }.eachCount()
                println("    unknown: $unknownTypes")
                assertTrue(visible.isNotEmpty())
                val items =
                    dev.herdroid.core.thread.ThreadItems
                        .build(messages.values.toList())
                println("    items: " + items.groupingBy { it::class.simpleName }.eachCount())
                if (pane.id == System.getenv("HERDR_PANE_ID")) {
                    items.takeLast(12).forEach { item ->
                        val line =
                            when (item) {
                                is dev.herdroid.core.thread.ThreadItem.Activity -> {
                                    "[activity] " +
                                        dev.herdroid.core.thread.ToolSummary
                                            .summarize(item.tools)
                                }

                                else -> {
                                    item.toString().take(90)
                                }
                            }
                        println("      $line")
                    }
                }
                rebuilt++
            }
            assertNotNull(rebuilt)
            assertTrue(rebuilt > 0)
        }
    }
}
