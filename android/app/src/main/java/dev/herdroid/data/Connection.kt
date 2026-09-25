package dev.herdroid.data

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import dev.herdroid.core.herdr.HerdrApi
import dev.herdroid.core.transcript.TranscriptSource
import dev.herdroid.core.transport.SshHostTransport
import dev.herdroid.core.transport.SshKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.security.PublicKey

data class HostConfig(
    val host: String = "",
    val port: Int = 22,
    val user: String = "",
) {
    val complete get() = host.isNotEmpty() && user.isNotEmpty()
}

sealed interface ConnectionState {
    data object Disconnected : ConnectionState

    data object Connecting : ConnectionState

    data class Connected(
        val api: HerdrApi,
        val transcripts: TranscriptSource,
        val transport: SshHostTransport,
        val herdrPath: String,
    ) : ConnectionState

    /** The link dropped (for example while the phone slept); screens keep showing [last]. */
    data class Reconnecting(
        val last: Connected,
        val attempt: Int,
        val error: String?,
    ) : ConnectionState

    data class Failed(
        val message: String,
    ) : ConnectionState
}

/** The session the UI should render: live, or the last one while reconnecting. */
val ConnectionState.session: ConnectionState.Connected?
    get() =
        when (this) {
            is ConnectionState.Connected -> this
            is ConnectionState.Reconnecting -> last
            else -> null
        }

class Connection(
    context: Context,
) {
    private val prefs = context.getSharedPreferences("connection", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var watchdog: Job? = null
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state

    val keyPair by lazy { DeviceKey.get() }
    val authorizedKeysLine by lazy { SshKeys.authorizedKeysLine(keyPair.public, "herdroid@${Build.MODEL.replace(' ', '-')}") }

    var config: HostConfig
        get() =
            HostConfig(
                prefs.getString("host", "") ?: "",
                prefs.getInt("port", 22),
                prefs.getString("user", "") ?: "",
            )
        set(value) =
            prefs.edit {
                putString("host", value.host)
                putInt("port", value.port)
                putString("user", value.user)
            }

    /** Host key pinned on first successful connect (trust on first use). */
    val pinnedHostKey: String? get() = prefs.getString("hostkey:${config.host}:${config.port}", null)

    /** Connect at launch when this host has worked before. */
    fun autoConnect() {
        if (config.complete && pinnedHostKey != null && _state.value == ConnectionState.Disconnected) {
            scope.launch { connect() }
        }
    }

    suspend fun connect() {
        lock.withLock {
            _state.value = ConnectionState.Connecting
            _state.value = open().fold({ it }, { ConnectionState.Failed(it.message ?: it.javaClass.simpleName) })
        }
        startWatchdog()
    }

    /** Call when the app returns to the foreground: sockets rarely survive a sleeping phone. */
    fun onForeground() {
        val current = _state.value
        if (current is ConnectionState.Connected && !current.transport.isAlive) scope.launch { reconnect(current) }
    }

    fun disconnect() {
        watchdog?.cancel()
        _state.value.session
            ?.transport
            ?.close()
        _state.value = ConnectionState.Disconnected
    }

    private fun startWatchdog() {
        watchdog?.cancel()
        watchdog =
            scope.launch {
                while (isActive) {
                    delay(3_000)
                    val current = _state.value
                    if (current is ConnectionState.Connected && !current.transport.isAlive) reconnect(current)
                }
            }
    }

    private suspend fun reconnect(previous: ConnectionState.Connected) {
        lock.withLock {
            if (_state.value != previous) return
            runCatching { previous.transport.close() }
            var attempt = 1
            var error: String? = null
            while (true) {
                _state.value = ConnectionState.Reconnecting(previous, attempt, error)
                val result = open()
                val next = result.getOrNull()
                if (next != null) {
                    _state.value = next
                    return
                }
                error = result.exceptionOrNull()?.message
                delay((1_000L shl (attempt - 1).coerceAtMost(4)).coerceAtMost(15_000))
                attempt++
                if (_state.value !is ConnectionState.Reconnecting) return
            }
        }
    }

    private suspend fun open(): Result<ConnectionState.Connected> =
        runCatching {
            val cfg = config
            val transport = SshHostTransport.connect(cfg.host, cfg.port, cfg.user, SshKeys.keyProvider(keyPair), verifier(cfg))
            try {
                val herdr = prefs.getString("herdr:${cfg.host}", null) ?: HerdrApi.locateHerdr(transport)
                check(HerdrApi.checkBridge(transport, herdr)) { "herdr at $herdr has no remote-api-bridge" }
                prefs.edit { putString("herdr:${cfg.host}", herdr) }
                ConnectionState.Connected(HerdrApi(transport, herdr), TranscriptSource(transport), transport, herdr)
            } catch (e: Exception) {
                transport.close()
                throw e
            }
        }

    private fun verifier(cfg: HostConfig) =
        object : HostKeyVerifier {
            val prefKey = "hostkey:${cfg.host}:${cfg.port}"

            override fun verify(
                hostname: String,
                port: Int,
                key: PublicKey,
            ): Boolean {
                val fingerprint = SshKeys.fingerprint(key)
                val pinned = prefs.getString(prefKey, null)
                if (pinned == null) {
                    prefs.edit { putString(prefKey, fingerprint) }
                    return true
                }
                return pinned == fingerprint
            }

            override fun findExistingAlgorithms(
                hostname: String,
                port: Int,
            ): List<String> = emptyList()
        }

    /** Last pane the user looked at, to reopen on launch. */
    var lastPaneId: String?
        get() = prefs.getString("lastPane", null)
        set(value) = prefs.edit { putString("lastPane", value) }
}
