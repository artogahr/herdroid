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

                AgentKind.KIMI -> {
                    "find \"\$HOME/.kimi-code/sessions\" -path \"*/\"$id\"/agents/main/wire.jsonl\" -type f 2>/dev/null | head -n 1"
                }
            }
        return transport.run(script).trim().ifEmpty { null }
    }

    /**
     * For an agent herdr knows no session for (for example after `codex resume`): the newest
     * transcript of [kind] started in [cwd] and written since the agent process [pid] started.
     * Written-since rules out showing an older conversation for a brand-new agent. Sessions
     * whose ids are in [exclude] belong to other panes.
     */
    suspend fun locateByFolder(
        kind: AgentKind,
        cwd: String,
        pid: Int,
        exclude: Set<String>,
    ): String? {
        val jsonCwd = cwd.replace("\\", "\\\\").replace("\"", "\\\"")
        val candidates =
            when (kind) {
                AgentKind.CLAUDE -> {
                    "ls \"\$HOME/.claude/projects/\"${shellQuote(claudeProjectDir(cwd))}/*.jsonl 2>/dev/null"
                }

                AgentKind.CODEX -> {
                    "find \"\$HOME/.codex/sessions\" -name 'rollout-*.jsonl' -mtime -30 2>/dev/null | " +
                        "while read -r f; do head -c 8192 \"\$f\" | grep -qF ${shellQuote("\"cwd\":\"$jsonCwd\"")} && echo \"\$f\"; done"
                }

                AgentKind.KIMI -> {
                    "grep -F ${shellQuote("\"workDir\":\"$jsonCwd\"")} \"\$HOME/.kimi-code/session_index.jsonl\" 2>/dev/null | " +
                        "sed -n 's/.*\"sessionId\":\"\\([^\"]*\\)\".*\"sessionDir\":\"\\([^\"]*\\)\".*/\\2\\/\\1\\/agents\\/main\\/wire.jsonl/p'"
                }
            }
        val script =
            "echo \"now \$(date +%s)\"; echo \"etime \$(ps -o etime= -p $pid | tr -d ' ')\"; " +
                "($candidates) | while read -r f; do [ -f \"\$f\" ] || continue; " +
                "m=\$(stat -c %Y \"\$f\" 2>/dev/null || stat -f %m \"\$f\"); echo \"file \$m \$f\"; done"
        val out = runCatching { transport.run(script) }.getOrNull() ?: return null
        val now =
            out
                .lineSequence()
                .firstOrNull { it.startsWith("now ") }
                ?.substringAfter(' ')
                ?.trim()
                ?.toLongOrNull() ?: return null
        val elapsed =
            out
                .lineSequence()
                .firstOrNull { it.startsWith("etime ") }
                ?.substringAfter(' ')
                ?.let(::parseEtime) ?: return null
        val started = now - elapsed
        return out
            .lineSequence()
            .filter { it.startsWith("file ") }
            .mapNotNull { line ->
                val parts = line.split(' ', limit = 3)
                val mtime = parts.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
                val path = parts.getOrNull(2) ?: return@mapNotNull null
                mtime to path
            }.filter { (mtime, path) -> mtime >= started && exclude.none { it in path } }
            .maxByOrNull { it.first }
            ?.second
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

    /**
     * Whole lines from the byte range just before [end], which must be a line boundary.
     * Returns the lines and the offset where the first returned line starts; a partial line
     * at the start of the range is dropped unless the range reaches the start of the file.
     */
    suspend fun readBefore(
        path: String,
        end: Long,
        maxBytes: Long,
    ): Pair<List<String>, Long> {
        val start = (end - maxBytes).coerceAtLeast(0)
        if (end <= start) return emptyList<String>() to start
        val chunk = transport.run("tail -c +${start + 1} ${shellQuote(path)} | head -c ${end - start}")
        var lines = chunk.split('\n')
        if (lines.lastOrNull()?.isEmpty() == true) lines = lines.dropLast(1)
        if (start > 0L) lines = lines.drop(1)
        // Count back from the aligned end over whole lines only: the dropped partial line may
        // start mid-character, so its decoded length is not its byte length.
        return lines to end - lines.sumOf { it.encodeToByteArray().size + 1L }
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
        /** `ps -o etime` format: [[dd-]hh:]mm:ss, in seconds. */
        fun parseEtime(etime: String): Long? {
            val trimmed = etime.trim().ifEmpty { return null }
            val days = trimmed.substringBefore('-', "0").takeIf { '-' in trimmed }?.toLongOrNull() ?: 0
            val clock = trimmed.substringAfter('-').split(':').map { it.toLongOrNull() ?: return null }
            val seconds = clock.foldIndexed(0L) { i, acc, v -> acc + v * listOf(1L, 60L, 3600L)[clock.size - 1 - i] }
            return days * 86_400 + seconds
        }

        fun parserFor(kind: AgentKind): TranscriptParser =
            when (kind) {
                AgentKind.CLAUDE -> ClaudeTranscriptParser()
                AgentKind.CODEX -> CodexTranscriptParser()
                AgentKind.KIMI -> KimiTranscriptParser()
            }

        /** Claude Code stores projects under the cwd with `/` and `.` replaced by `-`. */
        fun claudeProjectDir(cwd: String): String = cwd.replace('/', '-').replace('.', '-')
    }
}
