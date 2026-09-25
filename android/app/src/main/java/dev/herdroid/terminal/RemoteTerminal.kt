package dev.herdroid.terminal

import android.util.Base64
import android.util.Log
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import dev.herdroid.core.herdr.shellQuote
import dev.herdroid.core.transport.ExecChannel
import dev.herdroid.core.transport.HostTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Connects a Termux [TerminalSession] to `herdr terminal session observe|control`.
 * herdr renders the pane at the size we ask for and sends ANSI frames, so the emulator
 * only needs to replay them. Control mode resizes the real PTY for every client.
 */
class RemoteTerminal(
    private val scope: CoroutineScope,
    private val transport: HostTransport,
    private val herdrPath: String,
    private val terminalId: String,
    client: TerminalSessionClient,
    private val onClosed: (String) -> Unit,
) {
    var control: Boolean = false
        private set

    private var size: Pair<Int, Int>? = null
    private var channel: ExecChannel? = null
    private var job: Job? = null
    private val outgoing = Channel<String>(Channel.UNLIMITED)

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
                    if (!control) return
                    val b64 = Base64.encodeToString(data, offset, count, Base64.NO_WRAP)
                    outgoing.trySend(message("terminal.input") { put("bytes", b64) })
                }

                override fun onResize(
                    columns: Int,
                    rows: Int,
                ) {
                    if (size == columns to rows) return
                    size = columns to rows
                    if (control && channel != null) {
                        outgoing.trySend(
                            message("terminal.resize") {
                                put("cols", columns)
                                put("rows", rows)
                            },
                        )
                    } else {
                        restart()
                    }
                }
            },
        )

    fun setControl(enabled: Boolean) {
        if (enabled == control) return
        control = enabled
        restart()
    }

    fun sendText(text: String) {
        if (control) outgoing.trySend(message("terminal.input") { put("text", text) })
    }

    fun close() {
        job?.cancel()
        channel?.close()
    }

    private fun restart() {
        val (cols, rows) = size ?: return
        job?.cancel()
        channel?.close()
        val mode = if (control) "control" else "observe"
        val command = "${shellQuote(herdrPath)} terminal session $mode ${shellQuote(terminalId)} --cols $cols --rows $rows"
        job =
            scope.launch {
                try {
                    val ch = transport.exec(command)
                    channel = ch
                    val writer = launch { for (line in outgoing) ch.write(line + "\n") }
                    ch.lines.collect { line -> handle(line) }
                    writer.cancel()
                } catch (e: Exception) {
                    Log.w("RemoteTerminal", "terminal stream failed", e)
                    onClosed(e.message ?: "terminal stream failed")
                }
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
                onClosed(obj["reason"]?.jsonPrimitive?.content ?: "closed")
            }
        }
    }

    private fun message(
        type: String,
        body: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ) = buildJsonObject {
        put("type", type)
        body()
    }.toString()

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
