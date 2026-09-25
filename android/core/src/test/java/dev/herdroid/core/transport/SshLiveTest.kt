package dev.herdroid.core.transport

import dev.herdroid.core.herdr.HerdrApi
import dev.herdroid.core.model.AgentKind
import dev.herdroid.core.transcript.TranscriptSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.io.File
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

/**
 * Runs the herdr client over a real SSH connection. Needs an sshd on localhost:
 * HERDROID_SSHD_PORT and HERDROID_SSHD_AUTHKEYS (the AuthorizedKeysFile it reads).
 */
class SshLiveTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    @Test
    fun herdrOverSsh() {
        val port = System.getenv("HERDROID_SSHD_PORT")?.toIntOrNull()
        val authKeys = System.getenv("HERDROID_SSHD_AUTHKEYS")
        assumeTrue(port != null && authKeys != null)
        SshKeys.installProvider()
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        File(authKeys!!).writeText(SshKeys.authorizedKeysLine(pair.public, "herdroid-test") + "\n")
        runBlocking {
            SshHostTransport
                .connect("127.0.0.1", port!!, System.getProperty("user.name"), SshKeys.keyProvider(pair), PromiscuousVerifier())
                .use { ssh ->
                    val herdr = HerdrApi.locateHerdr(ssh)
                    assertTrue(HerdrApi.checkBridge(ssh, herdr))
                    val api = HerdrApi(ssh, herdr)
                    val snapshot = api.snapshot()
                    assertEquals(22, snapshot.protocol)
                    println("herdr=$herdr panes=${snapshot.panes.size}")
                    // Several channels on one connection, as the app uses them.
                    val again = List(5) { api.snapshot() }
                    assertTrue(again.all { it.protocol == 22 })
                    // The app issues requests concurrently from several screens.
                    val concurrent = coroutineScope { List(8) { async(Dispatchers.IO) { api.snapshot() } }.awaitAll() }
                    assertTrue(concurrent.all { it.protocol == 22 })
                    val pane = snapshot.panes.first { it.agentStatus != null }
                    val sub =
                        buildJsonObject {
                            put("type", "pane.agent_status_changed")
                            put("pane_id", pane.id)
                            put("agent_status", pane.agentStatus!!.name.lowercase())
                        }
                    println("event: ${api.subscribe(listOf(sub)).first()}")
                    val agent = snapshot.panes.first { it.agentSession?.agent == "claude" }
                    val source = TranscriptSource(ssh)
                    val path = source.locate(AgentKind.CLAUDE, agent.agentSession!!.value, agent.cwd)
                    assertTrue("transcript for ${agent.id}", path != null)
                    val lines = source.follow(path!!).take(50).toList()
                    assertEquals(50, lines.size)
                    println("followed ${lines.size} lines of ${agent.id}, offset ${lines.last().endOffset}")
                    val frame =
                        ssh
                            .exec(
                                "$herdr terminal session observe ${agent.terminalId} --cols 80 --rows 24",
                                stopOnEof = true,
                            ).use { it.lines.first() }
                    assertTrue(frame, frame.contains("\"terminal.frame\""))
                    println("terminal frame: ${frame.take(80)}")
                    // Stream the largest transcript to its end while other requests run, like the thread screen.
                    val big =
                        snapshot.panes
                            .filter { it.agentSession?.agent == "claude" }
                            .mapNotNull { p -> source.locate(AgentKind.CLAUDE, p.agentSession!!.value, p.cwd) }
                            .maxBy { ssh.run("wc -c < '$it'").trim().toLong() }
                    val size = source.size(big)
                    var streamed = 0
                    coroutineScope {
                        val polling = launch(Dispatchers.IO) { repeat(20) { api.snapshot() } }
                        source
                            .followRecent(big)
                            .takeWhile {
                                streamed++
                                it.endOffset < size
                            }.collect {}
                        polling.join()
                    }
                    println("streamed the recent end of $size bytes: $streamed lines")
                    val out = ssh.run("echo hi")
                    assertEquals("hi\n", out)
                }
        }
    }

    @Test
    fun ed25519KeyAuthenticates() {
        val port = System.getenv("HERDROID_SSHD_PORT")?.toIntOrNull()
        val authKeys = System.getenv("HERDROID_SSHD_AUTHKEYS")
        assumeTrue(port != null && authKeys != null)
        SshKeys.installProvider()
        val raw = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        // The same wrapper and signer the phone uses with its Keystore key.
        val pair = java.security.KeyPair(Ed25519PublicKey(raw.public), raw.private)
        val line = SshKeys.authorizedKeysLine(pair.public, "herdroid-ed25519-test")
        assertTrue(line, line.startsWith("ssh-ed25519 "))
        File(authKeys!!).writeText(line + "\n")
        runBlocking {
            SshHostTransport
                .connect("127.0.0.1", port!!, System.getProperty("user.name"), SshKeys.keyProvider(pair), PromiscuousVerifier())
                .use { ssh -> assertEquals("ok\n", ssh.run("echo ok")) }
        }
    }
}
