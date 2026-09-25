package dev.herdroid.core.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.IOException

/** Runs commands on this machine through `sh -c`; stands in for SSH in live tests. */
class LocalHostTransport : HostTransport {
    override suspend fun exec(command: String): ExecChannel =
        withContext(Dispatchers.IO) {
            val process = ProcessBuilder("sh", "-c", command).redirectError(java.io.File("/dev/null")).start()
            object : ExecChannel {
                override val lines: Flow<String> = process.inputStream.lineFlow { process.destroy() }

                override suspend fun write(text: String) =
                    withContext(Dispatchers.IO) {
                        process.outputStream.write(text.encodeToByteArray())
                        process.outputStream.flush()
                    }

                override suspend fun closeInput() = withContext(Dispatchers.IO) { process.outputStream.close() }

                override fun close() {
                    process.destroy()
                }
            }
        }

    override suspend fun run(command: String): String =
        withContext(Dispatchers.IO) {
            val process = ProcessBuilder("sh", "-c", command).start()
            val out = process.inputStream.readBytes().decodeToString()
            val status = process.waitFor()
            if (status != 0) throw IOException("`$command` exited $status")
            out
        }

    override fun close() {}
}
