package dev.herdroid.terminal

import android.util.Base64
import android.util.Log
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import dev.herdroid.core.herdr.shellQuote
import dev.herdroid.core.transport.ExecChannel
import dev.herdroid.core.transport.HostTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Connects a Termux [TerminalSession] to `herdr terminal session observe|control`.
 * herdr renders the pane at the size we ask for and sends ANSI frames, so the emulator
 * only replays them. Control mode resizes the real PTY for every client.
 */
@OptIn(FlowPreview::class)
class RemoteTerminal(
    scope: CoroutineScope,
    private val transport: HostTransport,
    private val herdrPath: String,
    private val terminalId: String,
    client: TerminalSessionClient,
    private val onClosed: (String) -> Unit,
) {
    private val control = MutableStateFlow(false)
    private val size = MutableStateFlow<Pair<Int, Int>?>(null)
    private val outgoing = Channel<String>(Channel.UNLIMITED)
    private var channel: ExecChannel? = null

    @Volatile private var closedByServer = false

    val session =
        TerminalSession(
            2000,
            client,
            object : TerminalSession.RemoteIO {
                override fun onInput(
                    data: ByteArray,
                    offset: Int,
                    count: Int,
                ) {
                    if (!control.value) return
                    val b64 = Base64.encodeToString(data, offset, count, Base64.NO_WRAP)
                    outgoing.trySend(message("terminal.input") { put("bytes", b64) })
                }

                override fun onResize(
                    columns: Int,
                    rows: Int,
                ) {
                    size.value = columns to rows
                }
            },
        )

    private val job =
        scope.launch {
            control.collectLatest { controlling ->
                if (controlling) {
                    val (cols, rows) = size.filterNotNull().first()
                    coroutineScope {
                        launch {
                            size.filterNotNull().drop(1).debounce(150).collect { (c, r) ->
                                outgoing.trySend(
                                    message("terminal.resize") {
                                        put("cols", c)
                                        put("rows", r)
                                    },
                                )
                            }
                        }
                        // Takeover also replaces our own observe stream, which may still be
                        // closing. The user confirmed taking control before this runs.
                        stream("control", cols, rows, stopOnEof = false, extra = "--takeover")
                    }
                } else {
                    // Observe cannot resize the pane, so each new size needs a new stream.
                    size.filterNotNull().debounce(150).collectLatest { (cols, rows) ->
                        stream("observe", cols, rows, stopOnEof = true)
                    }
                }
            }
        }

    val controlling: Boolean get() = control.value

    fun setControl(enabled: Boolean) {
        control.value = enabled
    }

    fun sendText(text: String) {
        if (control.value) outgoing.trySend(message("terminal.input") { put("text", text) })
    }

    /** Releases control so the pane returns to its desktop size, then closes the stream. */
    fun close() {
        job.cancel()
        val ch = channel ?: return
        if (!control.value) return ch.close()
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { ch.write(message("terminal.release") {} + "\n") }
            ch.close()
        }
    }

    private suspend fun stream(
        mode: String,
        cols: Int,
        rows: Int,
        stopOnEof: Boolean,
        extra: String = "",
    ) {
        val command = "${shellQuote(herdrPath)} terminal session $mode ${shellQuote(terminalId)} $extra --cols $cols --rows $rows"
        try {
            transport.exec(command, stopOnEof).use { ch ->
                channel = ch
                coroutineScope {
                    val writer = launch { for (line in outgoing) ch.write(line + "\n") }
                    ch.lines.collect { handle(it) }
                    writer.cancel()
                }
            }
            if (!closedByServer) onClosed("terminal stream ended")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("RemoteTerminal", "terminal stream failed", e)
            onClosed(e.message ?: "terminal stream failed")
        }
    }

    private fun handle(line: String) {
        val obj = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return
        when (obj["type"]?.jsonPrimitive?.content) {
            "terminal.frame" -> {
                val bytes = Base64.decode(obj["bytes"]?.jsonPrimitive?.content ?: return, Base64.DEFAULT)
                session.appendRemoteOutput(bytes, 0, bytes.size)
            }

            "terminal.closed" -> {
                closedByServer = true
                onClosed(obj["reason"]?.jsonPrimitive?.content ?: "closed")
            }
        }
    }

    private fun message(
        type: String,
        body: JsonObjectBuilder.() -> Unit,
    ) = buildJsonObject {
        put("type", type)
        body()
    }.toString()

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
