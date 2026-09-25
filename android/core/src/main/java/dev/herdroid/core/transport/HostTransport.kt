package dev.herdroid.core.transport

import kotlinx.coroutines.flow.Flow

/** A remote command with streaming stdout lines and writable stdin. */
interface ExecChannel : AutoCloseable {
    val lines: Flow<String>

    suspend fun write(text: String)

    suspend fun closeInput()
}

interface HostTransport : AutoCloseable {
    suspend fun exec(command: String): ExecChannel

    /** Runs [command] to completion and returns stdout. Throws on a non-zero exit. */
    suspend fun run(command: String): String
}
