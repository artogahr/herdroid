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
    enum class State { SENDING, SUBMITTED, FAILED }
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
        pane.agentSession?.agent?.let { a -> AgentKind.entries.firstOrNull { it.name.equals(a, ignoreCase = true) } }

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
        }
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
        val session = pane.agentSession
        val kind = kind
        if (session == null || kind == null) {
            loading = false
            loadError = "No chat view for this agent yet. Open the terminal instead."
            return
        }
        val path =
            runCatching { connected.transcripts.locate(kind, session.value, pane.cwd) }
                .onFailure { loadError = "Could not find the conversation: ${it.message}" }
                .getOrNull()
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
        outgoing.removeAll { it.state == Outgoing.State.SUBMITTED && it.text in recentUser }
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
