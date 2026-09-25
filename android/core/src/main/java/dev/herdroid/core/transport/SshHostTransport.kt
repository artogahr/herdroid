package dev.herdroid.core.transport

import dev.herdroid.core.herdr.shellQuote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import java.io.IOException
import java.util.concurrent.TimeUnit

class SshHostTransport private constructor(
    private val client: SSHClient,
) : HostTransport {
    override suspend fun exec(command: String): ExecChannel =
        withContext(Dispatchers.IO) {
            val session = client.startSession()
            SshExecChannel(session, session.exec(posix(command)))
        }

    override suspend fun run(command: String): String =
        withContext(Dispatchers.IO) {
            client.startSession().use { session ->
                val cmd = session.exec(posix(command))
                val out = cmd.inputStream.readBytes().decodeToString()
                val err = cmd.errorStream.readBytes().decodeToString()
                cmd.join(30, TimeUnit.SECONDS)
                val status = cmd.exitStatus
                if (status != null && status != 0) throw IOException("`$command` exited $status: ${err.trim()}")
                out
            }
        }

    override fun close() = client.close()

    /**
     * sshd runs commands through the account's login shell, which may be fish or another
     * non-POSIX shell. Every command here is POSIX sh, so hand it to sh explicitly.
     */
    private fun posix(command: String) = "sh -c ${shellQuote(command)}"

    companion object {
        suspend fun connect(
            host: String,
            port: Int,
            user: String,
            key: KeyProvider,
            hostKeyVerifier: HostKeyVerifier,
        ): SshHostTransport =
            withContext(Dispatchers.IO) {
                val client = SSHClient()
                client.addHostKeyVerifier(hostKeyVerifier)
                client.connectTimeout = 10_000
                client.connect(host, port)
                try {
                    client.connection.keepAlive.keepAliveInterval = 15
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
    override val lines: Flow<String> = cmd.inputStream.lineFlow { runCatching { session.close() } }

    override suspend fun write(text: String) =
        withContext(Dispatchers.IO) {
            cmd.outputStream.write(text.encodeToByteArray())
            cmd.outputStream.flush()
        }

    override suspend fun closeInput() = withContext(Dispatchers.IO) { cmd.outputStream.close() }

    override fun close() = session.close()
}
