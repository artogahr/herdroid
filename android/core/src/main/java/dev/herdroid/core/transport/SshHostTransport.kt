package dev.herdroid.core.transport

import com.hierynomus.sshj.key.KeyAlgorithms
import dev.herdroid.core.herdr.shellQuote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class SshHostTransport private constructor(
    private val client: SSHClient,
) : HostTransport {
    override suspend fun exec(
        command: String,
        stopOnEof: Boolean,
    ): ExecChannel =
        withContext(Dispatchers.IO) {
            val session = client.startSession()
            SshExecChannel(session, session.exec(posix(if (stopOnEof) stopOnEofScript(command) else command)))
        }

    override suspend fun run(command: String): String =
        withContext(Dispatchers.IO) {
            val session = client.startSession()
            // Never interrupt a thread inside sshj (it may be writing a packet); closing the
            // session from a watchdog is what unblocks a read stuck on a hung command.
            val timedOut = AtomicBoolean(false)
            val watchdog =
                launch {
                    delay(RUN_TIMEOUT_MS)
                    timedOut.set(true)
                    runCatching { session.close() }
                }
            try {
                val cmd = session.exec(posix(command))
                val out = cmd.inputStream.readBytes().decodeToString()
                val err = cmd.errorStream.readBytes().decodeToString()
                cmd.join(10, TimeUnit.SECONDS)
                if (timedOut.get()) throw IOException("`$command` timed out")
                val status = cmd.exitStatus ?: throw IOException("`$command` did not report an exit status")
                if (status != 0) throw IOException("`$command` exited $status: ${err.trim()}")
                out
            } finally {
                watchdog.cancel()
                runCatching { session.close() }
            }
        }

    override fun close() = client.close()

    val isAlive: Boolean get() = client.isConnected && client.isAuthenticated

    /**
     * sshd runs commands through the account's login shell, which may be fish or another
     * non-POSIX shell. Every command here is POSIX sh, so hand it to sh explicitly.
     */
    private fun posix(command: String) = "sh -c ${shellQuote(command)}"

    companion object {
        const val RUN_TIMEOUT_MS = 60_000L

        suspend fun connect(
            host: String,
            port: Int,
            user: String,
            key: KeyProvider,
            hostKeyVerifier: HostKeyVerifier,
        ): SshHostTransport =
            withContext(Dispatchers.IO) {
                val config =
                    DefaultConfig().apply {
                        // sshj's chacha20-poly1305 corrupts packets on Android when several
                        // channels write at once ("Bad packet length" on the server).
                        cipherFactories =
                            cipherFactories
                                .filterNot { it.name.startsWith("chacha20") }
                                .sortedBy { if (it.name.contains("gcm")) 0 else 1 }
                        val ecdsa256 = KeyType.ECDSA256.toString()
                        val ed25519 = KeyType.ED25519.toString()
                        keyAlgorithms =
                            keyAlgorithms.map {
                                when (it.name) {
                                    ecdsa256 -> KeyAlgorithms.Factory(ecdsa256, KeystoreEcdsaSignature.Factory256(), KeyType.ECDSA256)
                                    ed25519 -> KeyAlgorithms.Factory(ed25519, KeystoreEd25519Signature.Factory256(), KeyType.ED25519)
                                    else -> it
                                }
                            }
                    }
                val client = SSHClient(config)
                client.addHostKeyVerifier(hostKeyVerifier)
                client.connectTimeout = 10_000
                client.connect(host, port)
                try {
                    client.connection.keepAlive.keepAliveInterval = 15
                    client.connection.timeoutMs = 5_000
                    client.authPublickey(user, key)
                } catch (e: Exception) {
                    client.close()
                    throw e
                }
                SshHostTransport(client)
            }
    }
}

private class SshExecChannel(
    private val session: Session,
    private val cmd: Session.Command,
) : ExecChannel {
    private val closed = AtomicBoolean(false)

    override val lines: Flow<String> = cmd.inputStream.lineFlow { close() }

    override suspend fun write(text: String) =
        withContext(Dispatchers.IO) {
            cmd.outputStream.write(text.encodeToByteArray())
            cmd.outputStream.flush()
        }

    override suspend fun closeInput() = withContext(Dispatchers.IO) { cmd.outputStream.close() }

    /**
     * sshd confirms a channel close only after the remote command exits. Send EOF so the
     * command stops (see [HostTransport.exec]), and finish the close off-thread so callers
     * never block on the server.
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        closer.execute {
            runCatching { cmd.outputStream.close() }
            runCatching { session.close() }
        }
    }

    private companion object {
        val closer: ExecutorService =
            Executors.newCachedThreadPool { r -> Thread(r, "ssh-close").apply { isDaemon = true } }
    }
}
