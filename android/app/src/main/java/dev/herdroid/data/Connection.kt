package dev.herdroid.data

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import dev.herdroid.core.herdr.HerdrApi
import dev.herdroid.core.transcript.TranscriptSource
import dev.herdroid.core.transport.SshHostTransport
import dev.herdroid.core.transport.SshKeys
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.security.PublicKey

data class HostConfig(
    val host: String = "",
    val port: Int = 22,
    val user: String = "",
)

sealed interface ConnectionState {
    data object Disconnected : ConnectionState

    data object Connecting : ConnectionState

    data class Connected(
        val api: HerdrApi,
        val transcripts: TranscriptSource,
        val transport: SshHostTransport,
        val herdrPath: String,
    ) : ConnectionState

    data class Failed(
        val message: String,
    ) : ConnectionState
}

class Connection(
    context: Context,
) {
    private val prefs = context.getSharedPreferences("connection", Context.MODE_PRIVATE)
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

    suspend fun connect() {
        val cfg = config
        _state.value = ConnectionState.Connecting
        _state.value =
            try {
                val transport = SshHostTransport.connect(cfg.host, cfg.port, cfg.user, SshKeys.keyProvider(keyPair), verifier(cfg))
                val herdr = prefs.getString("herdr:${cfg.host}", null) ?: HerdrApi.locateHerdr(transport)
                check(HerdrApi.checkBridge(transport, herdr)) { "herdr at $herdr has no remote-api-bridge" }
                prefs.edit { putString("herdr:${cfg.host}", herdr) }
                ConnectionState.Connected(HerdrApi(transport, herdr), TranscriptSource(transport), transport, herdr)
            } catch (e: Exception) {
                ConnectionState.Failed(e.message ?: e.javaClass.simpleName)
            }
    }

    fun disconnect() {
        (_state.value as? ConnectionState.Connected)?.transport?.close()
        _state.value = ConnectionState.Disconnected
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
}
