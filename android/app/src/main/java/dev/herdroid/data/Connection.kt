package dev.herdroid.data

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import dev.herdroid.core.herdr.HerdrApi
import dev.herdroid.core.transcript.TranscriptSource
import dev.herdroid.core.transport.SshHostTransport
import dev.herdroid.core.transport.SshKeys
import kotlinx.coroutines.CancellationException
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

sealed interface ConnectionState {
    data object Disconnected : ConnectionState

    data object Connecting : ConnectionState

    data class Connected(
        val transport: SshHostTransport,
        val herdrPath: String,
    ) : ConnectionState {
        val api = HerdrApi(transport, herdrPath)
        val transcripts = TranscriptSource(transport)
    }

    /** The link dropped (for example while the phone slept); screens keep showing [last]. */
    data class Reconnecting(
        val last: Connected,
        val attempt: Int,
        val error: String?,
    ) : ConnectionState

    data class Failed(
        val message: String,
        /** Which saved server failed, so the list can show it on that card. */
        val hostId: String? = null,
    ) : ConnectionState

    /** The host presented a key we have not trusted: first contact, or a changed key. */
    data class HostKeyCheck(
        val fingerprint: String,
        val previous: String?,
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
    private var reconnectJob: Job? = null
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state

    val keyPair by lazy { DeviceKey.get() }

    /** Where the private key is kept, for the key card. */
    val keyStorage by lazy { DeviceKey.storage(keyPair.private) }

    val authorizedKeysLine by lazy { SshKeys.authorizedKeysLine(keyPair.public, "herdroid@${Build.MODEL.replace(' ', '-')}") }

    private val store = HostStore(prefs)
    private val _hosts = MutableStateFlow(store.all())

    /** Saved servers, most recently used first. */
    val hosts: StateFlow<List<SavedHost>> = _hosts

    /** The server being connected to, or last connected to. */
    var config: SavedHost = store.lastUsed() ?: SavedHost()
        private set

    private val SavedHost.hostKeyPref get() = "hostkey:$host:$port"
    private val SavedHost.herdrPref get() = "herdr:$user@$host:$port"

    val pinnedHostKey: String? get() = prefs.getString(config.hostKeyPref, null)

    fun pinnedHostKey(host: SavedHost): String? = prefs.getString(host.hostKeyPref, null)

    fun saveHost(host: SavedHost) {
        store.save(host)
        _hosts.value = store.all()
    }

    fun deleteHost(id: String) {
        if (config.id == id && _state.value.session != null) disconnect()
        store.delete(id)
        _hosts.value = store.all()
    }

    /** Connect to [host] and remember it as the last used one. */
    fun connectTo(host: SavedHost) {
        if (_state.value.session != null && config.id != host.id) disconnect()
        config = host.copy(lastUsed = System.currentTimeMillis())
        saveHost(config)
        connectInBackground()
    }

    /**
     * At launch, reconnect to the last used server if it has worked before (its key is
     * pinned). If it does not answer, the server list shows the error on its card.
     */
    fun autoConnect() {
        if (config.complete && pinnedHostKey != null && _state.value == ConnectionState.Disconnected) {
            connectInBackground()
        }
    }

    suspend fun connect() {
        reconnectJob?.cancel()
        lock.withLock {
            _state.value.session?.let { old -> scope.launch { runCatching { old.transport.close() } } }
            _state.value = ConnectionState.Connecting
            _state.value = open().toState()
        }
        startWatchdog()
    }

    /**
     * Starts connecting in this object's scope. UI scopes must not own the attempt: the
     * dialog or screen that asked goes away as soon as the state changes.
     */
    fun connectInBackground() {
        scope.launch { connect() }
    }

    /** The user checked the fingerprint; pin it and connect. */
    fun trustHostKey(fingerprint: String) {
        prefs.edit { putString(config.hostKeyPref, fingerprint) }
        connectInBackground()
    }

    /** Call when the app returns to the foreground: sockets rarely survive a sleeping phone. */
    fun onForeground() {
        when (val current = _state.value) {
            is ConnectionState.Connected -> {
                if (!current.transport.isAlive) reconnect(current)
            }

            is ConnectionState.Failed -> {
                if (config.complete && pinnedHostKey != null) scope.launch { connect() }
            }

            else -> {}
        }
    }

    fun disconnect() {
        reconnectJob?.cancel()
        watchdog?.cancel()
        val old = _state.value.session
        _state.value = ConnectionState.Disconnected
        // Closing blocks on the network; never on the main thread.
        old?.let { scope.launch { runCatching { it.transport.close() } } }
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

    private fun reconnect(previous: ConnectionState.Connected) {
        if (reconnectJob?.isActive == true) return
        reconnectJob =
            scope.launch {
                runCatching { previous.transport.close() }
                var error: String? = null
                for (attempt in 1..MAX_RECONNECTS) {
                    // The lock guards one attempt, so a manual connect() is never starved.
                    val result =
                        lock.withLock {
                            val now = _state.value
                            if (now != previous && now !is ConnectionState.Reconnecting) return@launch
                            _state.value = ConnectionState.Reconnecting(previous, attempt, error)
                            open()
                        }
                    result.onSuccess {
                        _state.value = it
                        return@launch
                    }
                    val failure = result.exceptionOrNull()
                    if (failure is HostKeyMismatch) {
                        _state.value = failure.state
                        return@launch
                    }
                    error = failure?.message
                    delay((1_000L shl (attempt - 1).coerceAtMost(4)).coerceAtMost(15_000))
                }
                // Stop draining the battery; returning to the app tries again.
                _state.value = ConnectionState.Failed("Could not reach ${config.label}: ${error ?: "no answer"}", config.id)
            }
    }

    private fun Result<ConnectionState.Connected>.toState(): ConnectionState =
        fold(
            { it },
            { e -> (e as? HostKeyMismatch)?.state ?: ConnectionState.Failed(describe(e), config.id) },
        )

    private suspend fun open(): Result<ConnectionState.Connected> {
        val cfg = config
        val seen = arrayOfNulls<String>(1)
        return try {
            val transport =
                try {
                    SshHostTransport.connect(cfg.host, cfg.port, cfg.user, SshKeys.keyProvider(keyPair), verifier(cfg, seen))
                } catch (e: Exception) {
                    val presented = seen[0]
                    if (presented !=
                        null
                    ) {
                        throw HostKeyMismatch(ConnectionState.HostKeyCheck(presented, prefs.getString(cfg.hostKeyPref, null)))
                    }
                    throw e
                }
            try {
                Result.success(ConnectionState.Connected(transport = transport, herdrPath = herdrPath(cfg, transport)))
            } catch (e: Exception) {
                transport.close()
                throw e
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** The cached herdr path, located again once if it stopped working (e.g. after a nix gc). */
    private suspend fun herdrPath(
        cfg: SavedHost,
        transport: SshHostTransport,
    ): String {
        prefs.getString(cfg.herdrPref, null)?.let { cached ->
            if (runCatching { HerdrApi.checkBridge(transport, cached) }.getOrDefault(false)) return cached
        }
        val found = HerdrApi.locateHerdr(transport)
        check(HerdrApi.checkBridge(transport, found)) { "herdr at $found has no remote-api-bridge" }
        prefs.edit { putString(cfg.herdrPref, found) }
        return found
    }

    /** Accepts only the pinned key; records any other key so the user can decide. */
    private fun verifier(
        cfg: SavedHost,
        seen: Array<String?>,
    ) = object : HostKeyVerifier {
        override fun verify(
            hostname: String,
            port: Int,
            key: PublicKey,
        ): Boolean {
            val fingerprint = SshKeys.fingerprint(key)
            if (prefs.getString(cfg.hostKeyPref, null) == fingerprint) return true
            seen[0] = fingerprint
            return false
        }

        override fun findExistingAlgorithms(
            hostname: String,
            port: Int,
        ): List<String> = emptyList()
    }

    /** What went wrong, in terms of what the user can check. */
    private fun describe(e: Throwable): String {
        val causes = generateSequence(e) { it.cause }.toList()
        return when {
            causes.any {
                it is java.net.SocketTimeoutException || it is java.net.ConnectException || it is java.net.NoRouteToHostException
            } -> {
                "Not reachable. Check that the machine is on and Tailscale is connected."
            }

            causes.any { it is java.net.UnknownHostException } -> {
                "Unknown host name."
            }

            causes.any { it is net.schmizz.sshj.userauth.UserAuthException } -> {
                "The server rejected this phone's key. Add it to ~/.ssh/authorized_keys."
            }

            causes.any { it.message?.contains("herdr") == true } -> {
                e.message ?: "herdr is not available on this server."
            }

            else -> {
                e.message ?: e.javaClass.simpleName
            }
        }
    }

    /** Last pane the user looked at, to reopen on launch. */
    var lastPaneId: String?
        get() = prefs.getString("lastPane", null)
        set(value) = prefs.edit { putString("lastPane", value) }

    private class HostKeyMismatch(
        val state: ConnectionState.HostKeyCheck,
    ) : Exception("host key not trusted")

    private companion object {
        const val MAX_RECONNECTS = 12
    }
}
