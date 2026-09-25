package dev.herdroid.core.transcript

import dev.herdroid.core.herdr.shellQuote
import dev.herdroid.core.model.AgentKind
import dev.herdroid.core.transport.HostTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** A transcript line and the byte offset just past it, to resume from after a reconnect. */
data class TranscriptLine(
    val text: String,
    val endOffset: Long,
)

class TranscriptSource(
    private val transport: HostTransport,
) {
    /** Absolute path of the session's transcript on the host, or null if it does not exist yet. */
    suspend fun locate(
        kind: AgentKind,
        sessionId: String,
        cwd: String?,
    ): String? {
        val id = shellQuote(sessionId)
        val script =
            when (kind) {
                AgentKind.CLAUDE -> {
                    val direct =
                        cwd?.let {
                            "f=\"\$HOME/.claude/projects/\"${shellQuote(
                                claudeProjectDir(it),
                            )}/$id.jsonl; [ -f \"\$f\" ] && echo \"\$f\" && exit 0;"
                        }
                            ?: ""
                    "$direct for f in \"\$HOME\"/.claude/projects/*/$id.jsonl; do [ -f \"\$f\" ] && echo \"\$f\" && exit 0; done; true"
                }

                AgentKind.CODEX -> {
                    "find \"\$HOME/.codex/sessions\" -name \"*\"$id.jsonl -type f 2>/dev/null | head -n 1"
                }
            }
        return transport.run(script).trim().ifEmpty { null }
    }

    /**
     * Streams lines from [offset] onwards and keeps following the file as it grows. Offsets
     * count bytes, so they must always sit on a line boundary returned by this function.
     */
    fun follow(
        path: String,
        offset: Long = 0,
        skipPartialFirstLine: Boolean = false,
    ): Flow<TranscriptLine> =
        flow {
            transport.exec("tail -c +${offset + 1} -F ${shellQuote(path)} 2>/dev/null", stopOnEof = true).use { channel ->
                var position = offset
                var skip = skipPartialFirstLine
                channel.lines.collect { line ->
                    position += line.encodeToByteArray().size + 1
                    if (skip) {
                        skip = false
                    } else {
                        emit(TranscriptLine(line, position))
                    }
                }
            }
        }

    suspend fun size(path: String): Long = transport.run("wc -c < ${shellQuote(path)}").trim().toLong()

    /**
     * Follows only the last [maxBytes] of the file. Transcripts reach tens of megabytes,
     * mostly tool output, so a thread opens from its recent end.
     */
    suspend fun followRecent(
        path: String,
        maxBytes: Long = 512 * 1024,
    ): Flow<TranscriptLine> {
        val start = (size(path) - maxBytes).coerceAtLeast(0)
        return follow(path, start, skipPartialFirstLine = start > 0)
    }

    companion object {
        fun parserFor(kind: AgentKind): TranscriptParser =
            when (kind) {
                AgentKind.CLAUDE -> ClaudeTranscriptParser()
                AgentKind.CODEX -> CodexTranscriptParser()
            }

        /** Claude Code stores projects under the cwd with `/` and `.` replaced by `-`. */
        fun claudeProjectDir(cwd: String): String = cwd.replace('/', '-').replace('.', '-')
    }
}
