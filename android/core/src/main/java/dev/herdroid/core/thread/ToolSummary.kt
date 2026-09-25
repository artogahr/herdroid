package dev.herdroid.core.thread

import dev.herdroid.core.model.ToolInfo
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

enum class ToolVerb(
    val singular: String,
    val plural: String,
    val past: String,
) {
    RAN("command", "commands", "Ran"),
    READ("file", "files", "Read"),
    EDITED("file", "files", "Edited"),
    WROTE("file", "files", "Wrote"),
    SEARCHED("search", "searches", "Ran"),
    DELEGATED("agent", "agents", "Started"),
    WAITED("wait", "waits", "Waited"),
    OTHER("tool", "tools", "Used"),
}

/** One tool call as a person would describe it: "Ran `git status`", "Read Theme.kt". */
data class ToolDescription(
    val verb: ToolVerb,
    val detail: String?,
)

object ToolSummary {
    fun describe(tool: ToolInfo): ToolDescription {
        val name = tool.name.orEmpty()
        val input = tool.input as? JsonObject

        fun field(key: String) = (input?.get(key) as? JsonPrimitive)?.contentOrNull
        val rawInput = (tool.input as? JsonPrimitive)?.contentOrNull
        return when (name) {
            "Bash" -> {
                ToolDescription(ToolVerb.RAN, field("description") ?: field("command"))
            }

            "Read" -> {
                ToolDescription(ToolVerb.READ, field("file_path")?.fileName())
            }

            "Edit", "MultiEdit", "NotebookEdit" -> {
                ToolDescription(
                    ToolVerb.EDITED,
                    (field("file_path") ?: field("notebook_path"))?.fileName(),
                )
            }

            "Write" -> {
                ToolDescription(ToolVerb.WROTE, field("file_path")?.fileName())
            }

            "Grep", "Glob", "WebSearch", "ToolSearch" -> {
                ToolDescription(ToolVerb.SEARCHED, field("pattern") ?: field("query"))
            }

            "WebFetch" -> {
                ToolDescription(ToolVerb.READ, field("url"))
            }

            "Agent", "Task" -> {
                ToolDescription(ToolVerb.DELEGATED, field("description"))
            }

            "exec_command", "shell", "local_shell" -> {
                ToolDescription(ToolVerb.RAN, field("cmd") ?: field("command"))
            }

            "exec" -> {
                ToolDescription(ToolVerb.RAN, rawInput?.let(::codexCommand) ?: rawInput?.firstLine())
            }

            "apply_patch" -> {
                ToolDescription(ToolVerb.EDITED, rawInput?.let(::patchFiles) ?: field("input")?.let(::patchFiles))
            }

            "sleep", "wait" -> {
                ToolDescription(ToolVerb.WAITED, null)
            }

            else -> {
                ToolDescription(ToolVerb.OTHER, name.ifEmpty { null })
            }
        }
    }

    /** "Ran 3 commands, read 2 files" for a run of tool calls, in first-seen order. */
    fun summarize(tools: List<ToolInfo>): String {
        val counts = LinkedHashMap<ToolVerb, Int>()
        tools.forEach { counts.merge(describe(it).verb, 1, Int::plus) }
        return counts.entries
            .mapIndexed { i, (verb, n) ->
                val past = if (i == 0) verb.past else verb.past.lowercase()
                "$past $n ${if (n == 1) verb.singular else verb.plural}"
            }.joinToString(", ")
    }

    private val execCmd = Regex("""exec_command\(\{\s*cmd\s*:\s*"((?:[^"\\]|\\.)*)"""")

    /** Codex's `exec` tool takes JavaScript; pull out the shell command it runs. */
    private fun codexCommand(js: String): String? =
        execCmd
            .find(js)
            ?.groupValues
            ?.get(1)
            ?.replace("\\\"", "\"")
            ?.replace("\\\\", "\\")

    private fun patchFiles(patch: String): String? =
        Regex("""\*\*\* (?:Update|Add|Delete) File: (.+)""")
            .findAll(patch)
            .map { it.groupValues[1].trim().fileName() }
            .toList()
            .takeIf { it.isNotEmpty() }
            ?.joinToString(", ")

    private fun String.fileName() = substringAfterLast('/')

    private fun String.firstLine() = lineSequence().first().take(120)
}
