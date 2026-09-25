package dev.herdroid.thread

import android.util.Log
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.herdroid.core.herdr.AgentStatus
import dev.herdroid.core.herdr.HerdrApi
import dev.herdroid.core.herdr.HerdrApiException
import dev.herdroid.core.herdr.Pane
import dev.herdroid.core.model.AgentKind
import dev.herdroid.core.model.Message
import dev.herdroid.core.model.MessageKind
import dev.herdroid.core.model.Role
import dev.herdroid.core.prompt.AnsiScreen
import dev.herdroid.core.prompt.Prompt
import dev.herdroid.core.prompt.PromptParser
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
    /** Session ids other panes own, so a folder match never takes another pane's conversation. */
    private val otherSessions: () -> Set<String> = { emptySet() },
) {
    private val api: HerdrApi get() = connected.api

    // Mutated only on the main thread; item building works on copies.
    private val messages = ArrayList<Message>()
    private val index = HashMap<String, Int>()

    var items by mutableStateOf<List<ThreadItem>>(emptyList())
        private set
    var status by mutableStateOf(pane.agentStatus ?: AgentStatus.UNKNOWN)
        private set

    /** The question the agent is showing, parsed from its screen. */
    var prompt by mutableStateOf<Prompt?>(null)
        private set

    /** The raw bottom of the screen while blocked, for prompts the parser does not understand. */
    var blockedPrompt by mutableStateOf<String?>(null)
        private set
    var answering by mutableStateOf(false)
        private set
    private var lastSeenSignature: String? = null
    var answerError by mutableStateOf<String?>(null)
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

    /** herdr had no session for this agent; the conversation was found by folder and start time. */
    var matchedByFolder by mutableStateOf(false)
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
                launch { watchPrompts() }
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
                        Latency.log("built", (items.lastOrNull() as? ThreadItem.AssistantText)?.text)
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
                                if (m.kind == MessageKind.TEXT && m.role == Role.ASSISTANT) Latency.log("recv", m.text)
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
        pane.agentSession?.let { return locateSession(kind, it.value) }
        // herdr knows no session: resumed or imported sessions (e.g. `codex resume`) often
        // skip the hook that reports it, and a brand-new agent has none until its first prompt.
        var announced = false
        while (true) {
            locateByFolder(kind)?.let {
                awaitingFirstMessage = false
                matchedByFolder = true
                loading = true
                return it
            }
            val live =
                runCatching { api.request("pane.get", buildJsonObject { put("pane_id", pane.id) }) }
                    .getOrNull()
                    ?.get("pane")
                    ?.let { HerdrApi.json.decodeFromJsonElement(Pane.serializer(), it) }
            live?.agentSession?.let {
                awaitingFirstMessage = false
                loading = true
                return locateSession(kind, it.value)
            }
            if (!announced) {
                announced = true
                loading = false
                awaitingFirstMessage = true
            }
            delay(2_000)
        }
    }

    private suspend fun locateSession(
        kind: AgentKind,
        sessionId: String,
    ): String? {
        repeat(5) {
            val found =
                runCatching { connected.transcripts.locate(kind, sessionId, pane.cwd) }
                    .onFailure { Log.w(TAG, "locating transcript failed", it) }
                    .getOrNull()
            if (found != null) return found
            delay(1_500)
        }
        loading = false
        loadError = "This agent has no saved conversation yet."
        return null
    }

    private suspend fun locateByFolder(kind: AgentKind): String? {
        val process =
            runCatching { api.request("pane.process_info", buildJsonObject { put("pane_id", pane.id) }) }
                .getOrNull()
                ?.get("process_info")
                ?.jsonObject
                ?.get("foreground_processes")
                ?.let { it as? JsonArray }
                ?.firstOrNull()
                ?.jsonObject ?: return null
        val pid = process["pid"]?.jsonPrimitive?.content?.toIntOrNull() ?: return null
        val cwd = process["cwd"]?.jsonPrimitive?.content ?: pane.cwd ?: return null
        return connected.transcripts.locateByFolder(kind, cwd, pid, otherSessions())
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
        refreshPrompt()
    }

    /**
     * herdr misses some prompts (Codex's folder trust reads as idle), so while on screen and
     * not working, look at the screen every few seconds too.
     */
    private suspend fun watchPrompts() {
        while (true) {
            delay(3_000)
            if (status != AgentStatus.WORKING && !answering) refreshPrompt()
        }
    }

    private suspend fun refreshPrompt() {
        val screen = readScreen()
        val parsed = screen?.let { PromptParser.parse(AnsiScreen.parse(it)) }
        // herdr saying "blocked" is enough. Otherwise (it misses some prompts) the same
        // question with a key hint must be on screen twice in a row, so a screen that only
        // looks like a question for a moment, such as streaming output, never shows a card.
        val stable = parsed != null && parsed.signature == lastSeenSignature
        lastSeenSignature = parsed?.signature
        prompt =
            when {
                parsed == null || status == AgentStatus.WORKING -> null
                status == AgentStatus.BLOCKED -> parsed
                parsed.hint != null && stable -> parsed
                else -> null
            }
        blockedPrompt =
            if (status == AgentStatus.BLOCKED) {
                screen?.let { promptTail(AnsiScreen.parse(it).joinToString("\n") { l -> l.text }) }
            } else {
                null
            }
    }

    private suspend fun readScreen(): String? =
        runCatching {
            api
                .request(
                    "pane.read",
                    buildJsonObject {
                        put("pane_id", pane.id)
                        put("source", "visible")
                        put("format", "ansi")
                    },
                ).getValue("read")
                .jsonObject["text"]
                ?.jsonPrimitive
                ?.content
        }.getOrNull()

    /**
     * Picks option [index] of [shown], typing [text] when the option asks for it. Re-reads the
     * screen before every key so an answer never lands on a different question: if the
     * prompt changed, nothing is sent.
     */
    fun answer(
        shown: Prompt,
        index: Int,
        text: String? = null,
    ) {
        if (answering) return
        answering = true
        answerError = null
        scope.launch {
            try {
                val now = currentPrompt()
                if (now == null || now.signature != shown.signature) {
                    answerError = "The question changed. Check it again."
                    return@launch
                }
                val delta = index - now.selected.coerceAtLeast(0)
                if (delta != 0) keys(List(kotlin.math.abs(delta)) { if (delta > 0) "down" else "up" })
                delay(250)
                val moved = currentPrompt()
                if (moved == null || moved.signature != shown.signature || moved.selected != index) {
                    answerError = "Could not select that option. Try again or use the terminal."
                    return@launch
                }
                val option = shown.options[index]
                if (option.wantsText && !text.isNullOrBlank()) {
                    if (!moved.textEntry) {
                        keys(listOf("enter"))
                        delay(400)
                    }
                    typeAndSubmit(text)
                } else {
                    keys(listOf("enter"))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                answerError = e.message ?: "Could not answer"
            } finally {
                delay(500)
                answering = false
                refreshPrompt()
            }
        }
    }

    /** For a prompt that is waiting for typed text. */
    fun answerText(text: String) {
        if (answering || text.isBlank()) return
        answering = true
        answerError = null
        scope.launch {
            try {
                if (currentPrompt()?.textEntry != true) {
                    answerError = "The agent is no longer waiting for text."
                    return@launch
                }
                typeAndSubmit(text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                answerError = e.message ?: "Could not answer"
            } finally {
                delay(500)
                answering = false
                refreshPrompt()
            }
        }
    }

    fun cancelPrompt() {
        scope.launch {
            runCatching { keys(listOf("esc")) }
            delay(500)
            refreshPrompt()
        }
    }

    private suspend fun currentPrompt(): Prompt? = readScreen()?.let { PromptParser.parse(AnsiScreen.parse(it)) }

    private suspend fun typeAndSubmit(text: String) {
        api.request(
            "pane.send_text",
            buildJsonObject {
                put("pane_id", pane.id)
                put("text", text)
            },
        )
        delay(150)
        keys(listOf("enter"))
    }

    private suspend fun keys(keys: List<String>) {
        api.request(
            "pane.send_keys",
            buildJsonObject {
                put("pane_id", pane.id)
                put("keys", JsonArray(keys.map { JsonPrimitive(it) }))
            },
        )
    }

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
