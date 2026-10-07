package dev.herdroid.data

import dev.herdroid.core.herdr.shellQuote
import dev.herdroid.core.transport.HostTransport
import kotlinx.coroutines.CancellationException

/** Names of the folders inside [dir] on the server, sorted; empty if it cannot be read. */
suspend fun listFolders(
    transport: HostTransport,
    dir: String,
): List<String> {
    // A quoted ~ is not expanded, so put $HOME in front of the rest of the path.
    val target =
        if (dir == "~" || dir.startsWith("~/")) "\"\$HOME\"" + shellQuote(dir.removePrefix("~")) else shellQuote(dir)
    val script =
        "cd -- $target 2>/dev/null || exit 0; " +
            "for d in */ .[!.]*/; do [ -d \"\$d\" ] && printf '%s\\n' \"\${d%/}\"; done | head -n 1000"
    return try {
        transport
            .run(script)
            .lines()
            .filter { it.isNotBlank() }
            .sortedBy { it.lowercase() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }
}
