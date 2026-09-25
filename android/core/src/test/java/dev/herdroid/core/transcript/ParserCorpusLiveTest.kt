package dev.herdroid.core.transcript

import dev.herdroid.core.model.AgentKind
import dev.herdroid.core.model.MessageKind
import dev.herdroid.core.thread.ThreadItems
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Parses the newest real transcripts on this machine. Run with HERDROID_LIVE=1; prints counts only. */
class ParserCorpusLiveTest {
    private val home = File(System.getProperty("user.home"))

    private fun newest(
        root: File,
        match: (File) -> Boolean,
        n: Int = 40,
    ) = root
        .walkTopDown()
        .filter { it.isFile && match(it) }
        .sortedByDescending { it.lastModified() }
        .take(n)
        .toList()

    private fun corpus(): Map<AgentKind, List<File>> =
        mapOf(
            AgentKind.CLAUDE to newest(File(home, ".claude/projects"), { it.extension == "jsonl" && "subagents" !in it.path }),
            AgentKind.CODEX to newest(File(home, ".codex/sessions"), { it.name.startsWith("rollout-") }),
            AgentKind.KIMI to newest(File(home, ".kimi-code/sessions"), { it.name == "wire.jsonl" && it.parentFile.name == "main" }),
        )

    @Test
    fun parsesEveryRecentTranscript() {
        assumeTrue(System.getenv("HERDROID_LIVE") == "1")
        for ((kind, files) in corpus()) {
            var lines = 0
            var messages = 0
            var unknown = 0
            var items = 0
            for (file in files) {
                val text = file.readLines()
                lines += text.size
                val first = parseAll(kind, text)
                val again = parseAll(kind, text)
                assertEquals("${file.name}: ids must be stable across re-parses", first.keys, again.keys)
                // Starting mid-file (history chunks, resumed threads) must reuse the same ids.
                val tail = parseAll(kind, text.drop(text.size / 2))
                val foreign = tail.keys - first.keys
                assertEquals("${file.name}: ids depend on where parsing started: ${foreign.take(3)}", emptySet<String>(), foreign)
                messages += first.size
                unknown += first.values.count { it.kind == MessageKind.UNKNOWN }
                items += ThreadItems.build(first.values.toList()).size
            }
            println("$kind files=${files.size} lines=$lines messages=$messages items=$items unknown=$unknown")
        }
    }

    private fun parseAll(
        kind: AgentKind,
        lines: List<String>,
    ): LinkedHashMap<String, dev.herdroid.core.model.Message> {
        val parser = TranscriptSource.parserFor(kind)
        val out = LinkedHashMap<String, dev.herdroid.core.model.Message>()
        lines.forEach { line -> parser.feed(line).forEach { out[it.id] = it } }
        return out
    }
}
