package dev.herdroid.core.transport

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import java.io.IOException
import java.io.InputStream
import kotlin.concurrent.thread

/**
 * Lines from a blocking stream. Blocking reads ignore coroutine cancellation, so the reader
 * runs on its own thread and [closeSource] is called when the collector stops, which unblocks it with EOF.
 */
fun InputStream.lineFlow(closeSource: () -> Unit): Flow<String> =
    callbackFlow {
        // Never interrupt this thread: it also sends SSH window adjustments, and an interrupted
        // socket write can leave a partial packet on the connection.
        thread(isDaemon = true, name = "line-reader") {
            try {
                bufferedReader().use { r ->
                    while (true) {
                        val line = r.readLine() ?: break
                        if (trySendBlocking(line).isFailure) break
                    }
                }
                channel.close()
            } catch (e: IOException) {
                channel.close(e)
            }
        }
        awaitClose { closeSource() }
    }.buffer(64)
