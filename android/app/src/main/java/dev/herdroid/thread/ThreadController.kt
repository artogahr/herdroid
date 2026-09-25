package dev.herdroid.thread

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import dev.herdroid.core.thread.ThreadItem
import dev.herdroid.core.thread.ThreadItems
import dev.herdroid.core.transcript.TranscriptSource
import dev.herdroid.data.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

/** A message the user sent that the transcript has not shown yet. */
data class Outgoing(
    val id: String,
    val text: String,
    val state: State,
    val error: String? = null,
) {
    enum class State { SENDING, SUBMITTED, UNCLEAR, FAILED }
}

/**
 * State for one agent conversation: transcript messages, live agent status, the prompt
 * text when blocked, and messages on their way. Lives as long as the thread screen.
 */
class ThreadController(
    private val scope: CoroutineScope,
    private val connected: ConnectionState.Connected,
    val pane: Pane,
) {
    private val api: HerdrApi = connected.api
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
    val outgoing = mutableStateListOf<Outgoing>()

    val kind: AgentKind? =
        (pane.agentSession?.agent ?: pane.agent)?.let { a -> AgentKind.entries.firstOrNull { it.name.equals(a, ignoreCase = true) } }

    /** True while a new agent has no saved conversation yet; sending the first message creates it. */
    var awaitingFirstMessage by mutableStateOf(false)
        private set

    fun start() {
        scope.launch { followTranscript() }
        scope.launch { followStatus() }
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val id = UUID.randomUUID().toString()
        outgoing += Outgoing(id, trimmed, Outgoing.State.SENDING)
        scope.launch {
            val result =
                runCatching {
                    api.request(
                        "agent.prompt",
                        buildJsonObject {
                            put("target", pane.id)
                            put("text", trimmed)
                        },
                    )
                }
            update(id) {
                result.fold(
                    onSuccess = { _ -> it.copy(state = Outgoing.State.SUBMITTED) },
                    onFailure = { e -> it.copy(state = Outgoing.State.FAILED, error = describe(e)) },
                )
            }
            // herdr confirms typing, not that the agent took the message. If the transcript
            // never shows it, say so instead of claiming it was sent.
            delay(20_000)
            update(id) { if (it.state == Outgoing.State.SUBMITTED) it.copy(state = Outgoing.State.UNCLEAR) else it }
        }
    }

    fun resend(id: String) {
        val out = outgoing.firstOrNull { it.id == id } ?: return
        dismiss(id)
        send(out.text)
    }

    fun dismiss(id: String) {
        outgoing.removeAll { it.id == id }
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
            }
        }
    }

    private suspend fun followTranscript() {
        val kind = kind
        if (kind == null) {
            loading = false
            loadError = "No chat view for this agent yet. Open the terminal instead."
            return
        }
        val session = pane.agentSession ?: waitForSession()
        var path: String? = null
        for (attempt in 1..5) {
            path =
                runCatching { connected.transcripts.locate(kind, session.value, pane.cwd) }
                    .onFailure { loadError = "Could not find the conversation: ${it.message}" }
                    .getOrNull()
            if (path != null) break
            delay(1_500)
        }
        if (path == null) {
            loading = false
            if (loadError == null) loadError = "This agent has no saved conversation yet."
            return
        }
        val parser = TranscriptSource.parserFor(kind)
        var dirty = false
        // Rebuild the item list at most every 100 ms while a large backlog streams in.
        scope.launch {
            while (true) {
                delay(100)
                if (dirty) {
                    dirty = false
                    items = ThreadItems.build(messages)
                    loading = false
                    reconcileOutgoing()
                }
            }
        }
        runCatching {
            connected.transcripts
                .followRecent(path)
                .map { line -> parser.feed(line.text) }
                .flowOn(Dispatchers.Default)
                .collect { parsed ->
                    for (m in parsed) {
                        val at = index[m.id]
                        if (at == null) {
                            index[m.id] = messages.size
                            messages += m
                        } else {
                            messages[at] = m
                        }
                        dirty = true
                    }
                    if (!dirty) loading = false
                }
        }.onFailure { loadError = "Connection lost: ${it.message}" }
    }

    /** herdr learns a new agent's session from its hooks, usually at the first prompt. */
    private suspend fun waitForSession(): dev.herdroid.core.herdr.AgentSession {
        loading = false
        awaitingFirstMessage = true
        while (true) {
            val pane =
                runCatching { api.request("pane.get", buildJsonObject { put("pane_id", pane.id) }) }
                    .getOrNull()
                    ?.get("pane")
                    ?.let { HerdrApi.json.decodeFromJsonElement(Pane.serializer(), it) }
            pane?.agentSession?.let {
                awaitingFirstMessage = false
                loading = true
                return it
            }
            delay(2_000)
        }
    }

    private suspend fun followStatus() {
        val sub =
            buildJsonObject {
                put("type", "pane.agent_status_changed")
                put("pane_id", pane.id)
            }
        runCatching { api.request("pane.get", buildJsonObject { put("pane_id", pane.id) }) }
            .getOrNull()
            ?.get("pane")
            ?.jsonObject
            ?.get("agent_status")
            ?.jsonPrimitive
            ?.content
            ?.let(::parseStatus)
            ?.let { onStatus(it) }
        runCatching {
            api.subscribe(listOf(sub)).collect { event ->
                val s =
                    event["data"]
                        ?.jsonObject
                        ?.get("agent_status")
                        ?.jsonPrimitive
                        ?.content
                s?.let(::parseStatus)?.let { onStatus(it) }
            }
        }
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
        }.getOrNull()?.let(::promptTail)

    /** Once the transcript shows a sent message, the placeholder bubble can go. */
    private fun reconcileOutgoing() {
        val recentUser =
            messages
                .takeLast(200)
                .filter { it.role == Role.USER && it.kind == MessageKind.TEXT }
                .mapNotNull { it.text?.trim() }
                .toSet()
        outgoing.removeAll { (it.state == Outgoing.State.SUBMITTED || it.state == Outgoing.State.UNCLEAR) && it.text in recentUser }
    }

    private fun update(
        id: String,
        change: (Outgoing) -> Outgoing,
    ) {
        val i = outgoing.indexOfFirst { it.id == id }
        if (i >= 0) outgoing[i] = change(outgoing[i])
    }

    private fun describe(e: Throwable) =
        when ((e as? HerdrApiException)?.code) {
            "agent_blocked" -> "The agent is waiting for an answer first."
            "agent_not_ready" -> "The agent is not ready for input."
            else -> e.message ?: "Could not send"
        }

    private fun parseStatus(s: String) = AgentStatus.entries.firstOrNull { it.name.equals(s, ignoreCase = true) }

    companion object {
        /** The bottom of the screen, where agents draw their question and choices. */
        fun promptTail(screen: String): String =
            screen
                .lines()
                .map { it.trimEnd() }
                .dropLastWhile { it.isBlank() }
                .takeLast(14)
                .dropWhile { it.isBlank() }
                .joinToString("\n")
    }
}
