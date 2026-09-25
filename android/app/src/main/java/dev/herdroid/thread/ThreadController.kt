package dev.herdroid.thread

import android.util.Log
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.herdroid.core.herdr.AgentSession
import dev.herdroid.core.herdr.AgentStatus
import dev.herdroid.core.herdr.HerdrApi
import dev.herdroid.core.herdr.HerdrApiException
import dev.herdroid.core.herdr.Pane
import dev.herdroid.core.model.AgentKind
import dev.herdroid.core.model.Message
import dev.herdroid.core.model.MessageKind
import dev.herdroid.core.model.Role
import dev.herdroid.core.thread.Outbox
import dev.herdroid.core.thread.Outgoing
import dev.herdroid.core.thread.ThreadItem
import dev.herdroid.core.thread.ThreadItems
import dev.herdroid.core.thread.promptTail
import dev.herdroid.core.transcript.TranscriptParser
import dev.herdroid.core.transcript.TranscriptSource
import dev.herdroid.data.ConnectionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * State for one agent conversation: transcript messages, live agent status, the prompt
 * text when blocked, and messages on their way. Lives for the whole app session, across
 * reconnects; only the pane on screen streams (see [activate]).
 */
class ThreadController(
    private val scope: CoroutineScope,
    private var connected: ConnectionState.Connected,
    val pane: Pane,
) {
    private val api: HerdrApi get() = connected.api

    // Mutated only on the main thread; item building works on copies.
    private val messages = ArrayList<Message>()
    private val index = HashMap<String, Int>()

    var items by mutableStateOf<List<ThreadItem>>(emptyList())
        private set
    var status by mutableStateOf(pane.agentStatus ?: AgentStatus.UNKNOWN)
        private set
    var blockedPrompt by mutableStateOf<String?>(null)
        private set
    var loadError by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(true)
        private set
    var title by mutableStateOf<String?>(null)
        private set

    /** True while a new agent has no saved conversation yet; sending the first message creates it. */
    var awaitingFirstMessage by mutableStateOf(false)
        private set
    var hasOlder by mutableStateOf(false)
        private set

    private val outbox = Outbox()
    var outgoing by mutableStateOf<List<Outgoing>>(emptyList())
        private set

    val kind: AgentKind? = AgentKind.of(pane.agentSession?.agent ?: pane.agent)

    /** Scroll position survives swiping away and back. */
    val listState = LazyListState()

    private var job: Job? = null
    private var path: String? = null
    private var parser: TranscriptParser? = null

    /** Byte offset just past the last line read; -1 before the first read. */
    private var offset = -1L

    /** Byte offset of the oldest loaded line; older history is read on demand. */
    private var historyStart = -1L
    private var loadingOlder = false

    /**
     * Streams live only while on screen. Messages, the file offset and the scroll position
     * stay, so coming back resumes from where it stopped instead of reloading.
     */
    fun activate() {
        if (job?.isActive == true) return
        job =
            scope.launch {
                launch { followTranscript() }
                launch { followStatus() }
            }
    }

    fun deactivate() {
        job?.cancel()
        job = null
    }

    /** After a reconnect: same conversation, new link. The next [activate] resumes at [offset]. */
    fun rebind(next: ConnectionState.Connected) {
        if (next === connected) return
        val wasActive = job?.isActive == true
        deactivate()
        connected = next
        if (wasActive) activate()
    }

    fun send(text: String) {
        if (text.isBlank()) return
        val out = outbox.queue(text, offset)
        publishOutbox()
        scope.launch {
            val result =
                runCatching {
                    api.request(
                        "agent.prompt",
                        buildJsonObject {
                            put("target", pane.id)
                            put("text", out.text)
                        },
                    )
                }
            result.fold({ outbox.submitted(out.id) }, { outbox.failed(out.id, describe(it)) })
            publishOutbox()
            // herdr confirms typing, not that the agent took the message. If the transcript
            // never shows it, say so instead of claiming it was sent.
            delay(DELIVERY_TIMEOUT_MS)
            outbox.timedOut(out.id)
            publishOutbox()
        }
    }

    fun resend(id: String) {
        val out = outgoing.firstOrNull { it.id == id } ?: return
        dismiss(id)
        send(out.text)
    }

    fun dismiss(id: String) {
        outbox.dismiss(id)
        publishOutbox()
    }

    fun interrupt() {
        scope.launch {
            runCatching {
                api.request(
                    "agent.send_keys",
                    buildJsonObject {
                        put("target", pane.id)
                        put("keys", JsonArray(listOf(JsonPrimitive("esc"))))
                    },
                )
            }.onFailure { Log.w(TAG, "interrupt failed", it) }
        }
    }

    /** Prepends the chunk of transcript before what is loaded. Called when scrolling up. */
    suspend fun loadOlder() {
        val file = path ?: return
        val kind = kind ?: return
        if (loadingOlder || historyStart <= 0) return
        loadingOlder = true
        try {
            do {
                val (lines, start) = connected.transcripts.readBefore(file, historyStart, WINDOW * 2)
                val older =
                    withContext(Dispatchers.Default) {
                        // A fresh parser: tool results whose call is further back stay unmatched.
                        val p = TranscriptSource.parserFor(kind)
                        val byId = LinkedHashMap<String, Message>()
                        lines.forEach { line -> p.feed(line).forEach { byId[it.id] = trim(it) } }
                        byId.values.filter { it.id !in index }
                    }
                historyStart = start
                messages.addAll(0, older)
                index.clear()
                messages.forEachIndexed { i, m -> index[m.id] = i }
                rebuild()
                hasOlder = historyStart > 0
            } while (hasOlder && textItems() < MIN_TEXT_ITEMS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "loading older history failed", e)
        } finally {
            loadingOlder = false
        }
    }

    private suspend fun followTranscript() {
        val kind = kind
        if (kind == null) {
            loading = false
            loadError = "No chat view for this agent yet. Open the terminal instead."
            return
        }
        val file = path ?: locate(kind) ?: return
        path = file
        loadError = null
        val parser = parser ?: TranscriptSource.parserFor(kind).also { parser = it }
        var dirty = false
        coroutineScope {
            // Rebuild the item list at most every 150 ms while a large backlog streams in.
            val ticker =
                launch {
                    while (true) {
                        delay(150)
                        if (!dirty) continue
                        dirty = false
                        rebuild()
                        title = parser.title
                        loading = false
                        hasOlder = historyStart > 0
                        if (hasOlder && textItems() < MIN_TEXT_ITEMS) launch { loadOlder() }
                    }
                }
            val lines =
                if (offset < 0) connected.transcripts.followRecent(file, WINDOW) else connected.transcripts.follow(file, offset)
            try {
                lines
                    .map { line -> Parsed(line.endOffset, line.text.encodeToByteArray().size + 1L, parser.feed(line.text).map(::trim)) }
                    .flowOn(Dispatchers.Default)
                    .collect { chunk ->
                        if (historyStart < 0) historyStart = chunk.end - chunk.bytes
                        offset = chunk.end
                        for (m in chunk.messages) {
                            val at = index[m.id]
                            if (at == null) {
                                index[m.id] = messages.size
                                messages += m
                                if (m.role == Role.USER && m.kind == MessageKind.TEXT) {
                                    if (outbox.onUserMessage(m.text.orEmpty(), chunk.end)) publishOutbox()
                                }
                            } else {
                                messages[at] = m
                            }
                            dirty = true
                        }
                        if (!dirty) loading = false
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The connection banner explains drops; keep what is already on screen.
                Log.w(TAG, "transcript stream ended", e)
                if (messages.isEmpty()) loadError = "Could not read the conversation: ${e.message}"
            } finally {
                ticker.cancel()
            }
        }
    }

    private class Parsed(
        val end: Long,
        val bytes: Long,
        val messages: List<Message>,
    )

    private suspend fun locate(kind: AgentKind): String? {
        val session = pane.agentSession ?: waitForSession()
        repeat(5) {
            val found =
                runCatching { connected.transcripts.locate(kind, session.value, pane.cwd) }
                    .onFailure { Log.w(TAG, "locating transcript failed", it) }
                    .getOrNull()
            if (found != null) return found
            delay(1_500)
        }
        loading = false
        loadError = "This agent has no saved conversation yet."
        return null
    }

    /** herdr learns a new agent's session from its hooks, usually at the first prompt. */
    private suspend fun waitForSession(): AgentSession {
        loading = false
        awaitingFirstMessage = true
        while (true) {
            val live =
                runCatching { api.request("pane.get", buildJsonObject { put("pane_id", pane.id) }) }
                    .getOrNull()
                    ?.get("pane")
                    ?.let { HerdrApi.json.decodeFromJsonElement(Pane.serializer(), it) }
            live?.agentSession?.let {
                awaitingFirstMessage = false
                loading = true
                return it
            }
            delay(2_000)
        }
    }

    private suspend fun followStatus() {
        runCatching { api.request("pane.get", buildJsonObject { put("pane_id", pane.id) }) }
            .getOrNull()
            ?.get("pane")
            ?.jsonObject
            ?.get("agent_status")
            ?.jsonPrimitive
            ?.content
            ?.let(::parseStatus)
            ?.let { onStatus(it) }
        val sub =
            buildJsonObject {
                put("type", "pane.agent_status_changed")
                put("pane_id", pane.id)
            }
        runCatching {
            api.subscribe(listOf(sub)).collect { event ->
                event["data"]
                    ?.jsonObject
                    ?.get("agent_status")
                    ?.jsonPrimitive
                    ?.content
                    ?.let(::parseStatus)
                    ?.let { onStatus(it) }
            }
        }.onFailure { if (it !is CancellationException) Log.w(TAG, "status subscription ended", it) }
    }

    private suspend fun onStatus(s: AgentStatus) {
        status = s
        blockedPrompt = if (s == AgentStatus.BLOCKED) readPrompt() else null
    }

    private suspend fun readPrompt(): String? =
        runCatching {
            api
                .request(
                    "pane.read",
                    buildJsonObject {
                        put("pane_id", pane.id)
                        put("source", "visible")
                        put("format", "text")
                    },
                ).getValue("read")
                .jsonObject["text"]
                ?.jsonPrimitive
                ?.content
        }.getOrNull()?.let { promptTail(it) }

    private suspend fun rebuild() {
        val snapshot = messages.toList()
        items = withContext(Dispatchers.Default) { ThreadItems.build(snapshot) }
    }

    private fun textItems() = items.count { it !is ThreadItem.Activity }

    private fun publishOutbox() {
        outgoing = outbox.entries
    }

    /**
     * Tool output can be megabytes (base64 screenshots), and raw JSON doubles memory; the
     * chat shows neither in full. Raw stays only where the thread offers "view raw".
     */
    private fun trim(m: Message): Message {
        var out = if (m.kind == MessageKind.UNKNOWN) m else m.copy(raw = null)
        val tool = out.tool
        val output = tool?.output
        if (tool != null && output != null && output.length > MAX_TOOL_OUTPUT) {
            out = out.copy(tool = tool.copy(output = output.take(MAX_TOOL_OUTPUT) + "\n…"))
        }
        return out
    }

    private fun describe(e: Throwable) =
        when ((e as? HerdrApiException)?.code) {
            "agent_blocked" -> "The agent is waiting for an answer first."
            "agent_not_ready" -> "The agent is not ready for input."
            else -> e.message ?: "Could not send"
        }

    private fun parseStatus(s: String) = AgentStatus.entries.firstOrNull { it.name.equals(s, ignoreCase = true) }

    private companion object {
        const val TAG = "ThreadController"
        const val WINDOW = 512L * 1024
        const val MIN_TEXT_ITEMS = 12
        const val MAX_TOOL_OUTPUT = 16_000
        const val DELIVERY_TIMEOUT_MS = 20_000L
    }
}
