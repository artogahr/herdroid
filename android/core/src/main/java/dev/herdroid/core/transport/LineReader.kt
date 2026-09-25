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
 * runs on its own thread and [closeSource] is called when the collector stops, which unblocks it.
 */
fun InputStream.lineFlow(closeSource: () -> Unit): Flow<String> =
    callbackFlow {
        val reader =
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
        awaitClose {
            closeSource()
            reader.interrupt()
        }
    }.buffer(64)
