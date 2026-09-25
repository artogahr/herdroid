package dev.herdroid.core.transport

import kotlinx.coroutines.flow.Flow

/** A remote command with streaming stdout lines and writable stdin. */
interface ExecChannel : AutoCloseable {
    val lines: Flow<String>

    suspend fun write(text: String)

    suspend fun closeInput()
}

interface HostTransport : AutoCloseable {
    /**
     * Starts [command]. With [stopOnEof], the command does not read stdin and is killed when
     * stdin reaches EOF, which closing the channel causes. Use it for commands like `tail -F`
     * that would otherwise outlive the channel.
     */
    suspend fun exec(
        command: String,
        stopOnEof: Boolean = false,
    ): ExecChannel

    /** Runs [command] to completion and returns stdout. Throws on a non-zero exit. */
    suspend fun run(command: String): String
}

/**
 * POSIX sh wrapper that runs [command] until it exits or stdin reaches EOF. Job control
 * (`set -m`) puts the command in its own process group so the whole group can be killed.
 * Background jobs would otherwise get /dev/null as stdin, so the watcher reads fd 3.
 */
fun stopOnEofScript(command: String): String =
    "set -m; exec 3<&0; sh -c ${dev.herdroid.core.herdr.shellQuote(command)} </dev/null & p=\$!; " +
        "{ cat <&3 >/dev/null; kill -TERM -\$p 2>/dev/null; } & w=\$!; exec 3<&-; " +
        "wait \$p; s=\$?; kill -TERM -\$w 2>/dev/null; exit \$s"
