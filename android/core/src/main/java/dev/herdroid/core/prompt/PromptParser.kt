package dev.herdroid.core.prompt

data class PromptOption(
    val label: String,
    val description: String?,
    val number: Int?,
    /** Choosing this asks for text, like "Reject with feedback" or "tell Claude what to do differently". */
    val wantsText: Boolean,
)

/** A question an agent is showing, read from its screen. */
data class Prompt(
    val question: String,
    val options: List<PromptOption>,
    /** Index of the highlighted option, or -1. */
    val selected: Int,
    val hint: String?,
    /** The agent is waiting for typed text (for example feedback after a rejection). */
    val textEntry: Boolean,
) {
    /** Identity for re-validation: the same question with the same options. */
    val signature: String get() = question + "\n" + options.joinToString("\n") { it.label }
}

/**
 * Finds the question and choices at the bottom of an agent's screen. Claude, Codex and Kimi
 * all draw a question, a list with a cursor (numbered or not), and a key hint line; they
 * differ in markers and layout, so this looks for that shape rather than per-agent text.
 */
object PromptParser {
    private val numbered = Regex("""^(\s*)(?:[❯›▶>]\s*)?(\d{1,2})[.)]\s+(\S.*)$""")
    private val cursor = Regex("""^(\s*)([❯›▶])\s+(\S.*)$""")
    private val separator = Regex("""^\s*[╭╰│]?[─━═╌]{12,}[╮╯│]?\s*$|^\s*[─━═]{3,}.*[─━═]{12,}\s*$""")
    private val hintWords =
        Regex("""(?i)(↑|↓|↵|\benter\b|\besc\b|to confirm|to cancel|to select|to continue|navigate|choose|submit|tab to amend)""")
    private val textEntryHint = Regex("""(?i)\btype\b.*(submit|↵|enter)""")
    private val wantsText =
        Regex(
            """(?i)(feedback|what to do differently|tell (claude|codex|kimi|the agent|me)\b|type something|something else|\bother\b|custom answer|explain)""",
        )
    private val agentOutput = Regex("""^\s*[•⏺●✻✦]""")

    fun parse(lines: List<ScreenLine>): Prompt? {
        val bottom = lines.dropLastWhile { it.text.isBlank() }.takeLast(48)
        val block = numberedBlock(bottom) ?: cursorBlock(bottom) ?: return textEntry(bottom)
        val (optionRows, markerRow) = block
        val first = optionRows.first()
        val last = optionRows.last()
        // A list with the agent's input box or more output below it is part of an answer,
        // not a question waiting for a choice.
        if (inputBoxOrOutputBelow(bottom, last)) return null
        val options =
            optionRows.mapIndexed { i, row ->
                val next = optionRows.getOrNull(i + 1) ?: (last + 1 + descriptionLength(bottom, last))
                val label = optionLabel(bottom[row].text)
                val description =
                    ((row + 1) until next)
                        .map { bottom[it] }
                        .filter { it.text.isNotBlank() && !isHint(it) }
                        .joinToString(" ") { it.text.trim() }
                        .ifBlank { null }
                PromptOption(
                    label = label.substringBefore("  ").trim(),
                    description = description,
                    number =
                        numbered
                            .find(bottom[row].text)
                            ?.groupValues
                            ?.get(2)
                            ?.toIntOrNull(),
                    wantsText = wantsText.containsMatchIn(label),
                )
            }
        val after = (last + 1) until bottom.size
        // Most agents put the key hint under the options; Kimi's folder trust puts it above.
        val before = (first - 1) downTo maxOf(0, first - 10)
        val hint =
            (after.map { bottom[it] }.firstOrNull { isHint(it) } ?: before.map { bottom[it] }.firstOrNull { isHint(it) })
                ?.text
                ?.trim()
        return Prompt(
            question = question(bottom, first),
            options = options,
            selected = optionRows.indexOf(markerRow),
            hint = hint,
            textEntry = hint != null && textEntryHint.containsMatchIn(hint),
        )
    }

    /** The lowest run of options numbered 1, 2, 3… with at most a few lines between them. */
    private fun numberedBlock(lines: List<ScreenLine>): Pair<List<Int>, Int>? {
        val hits = lines.indices.mapNotNull { i -> numbered.find(lines[i].text)?.let { i to it.groupValues[2].toInt() } }
        var best: List<Pair<Int, Int>>? = null
        var run = ArrayList<Pair<Int, Int>>()
        for (hit in hits) {
            val prev = run.lastOrNull()
            if (prev != null && hit.second == prev.second + 1 && hit.first - prev.first <= 4) {
                run += hit
            } else {
                if (run.size >= 2 && run.first().second == 1) best = run.toList()
                run = arrayListOf(hit)
            }
        }
        if (run.size >= 2 && run.first().second == 1) best = run.toList()
        val rows = best?.map { it.first } ?: return null
        if (rows.last() < lines.size - 12) return null
        val marker = rows.firstOrNull { cursorMarked(lines[it].text) } ?: -1
        return rows to marker
    }

    /** A cursor line plus siblings at the same text column, skipping muted descriptions. */
    private fun cursorBlock(lines: List<ScreenLine>): Pair<List<Int>, Int>? {
        val markerRow =
            lines.indices.reversed().firstOrNull { i ->
                !lines[i].muted && cursor.find(lines[i].text) != null && i >= lines.size - 16
            } ?: return null
        val match = cursor.find(lines[markerRow].text)!!
        val column = match.groups[3]!!.range.first
        val top = (markerRow downTo 0).firstOrNull { separator.matches(lines[it].text) } ?: -1
        val bottomEdge =
            ((markerRow + 1) until lines.size).firstOrNull { separator.matches(lines[it].text) || isHint(lines[it]) } ?: lines.size

        fun isSibling(i: Int): Boolean {
            val l = lines[i]
            if (l.muted || l.text.isBlank() || isHint(l) || separator.matches(l.text)) return false
            val col = l.text.indexOfFirst { !it.isWhitespace() }
            return col == column || (
                cursor
                    .find(l.text)
                    ?.groups
                    ?.get(3)
                    ?.range
                    ?.first == column
            )
        }
        val rows = ((top + 1) until bottomEdge).filter { it == markerRow || isSibling(it) }
        // Siblings must sit together around the cursor, not anywhere in the region.
        val near = rows.filter { kotlin.math.abs(it - markerRow) <= 2 * rows.size + 2 }
        if (near.size < 2) return null
        return near to markerRow
    }

    private val inputLine = Regex("""^\s*(?:[❯›>]|│\s*>)(?:\s.*)?$""")

    private fun inputBoxOrOutputBelow(
        lines: List<ScreenLine>,
        lastOption: Int,
    ): Boolean {
        var sawSeparator = false
        for (i in (lastOption + 1) until lines.size) {
            val t = lines[i].text
            if (separator.matches(t)) {
                sawSeparator = true
                continue
            }
            if (agentOutput.containsMatchIn(t)) return true
            if (sawSeparator && inputLine.matches(t)) return true
        }
        return false
    }

    /** No options, but a hint says the agent wants typed text. */
    private fun textEntry(lines: List<ScreenLine>): Prompt? {
        val hintRow =
            lines.indices
                .reversed()
                .take(8)
                .firstOrNull { isHint(lines[it]) && textEntryHint.containsMatchIn(lines[it].text) }
                ?: return null
        return Prompt(question(lines, hintRow), emptyList(), -1, lines[hintRow].text.trim(), textEntry = true)
    }

    private fun descriptionLength(
        lines: List<ScreenLine>,
        lastOption: Int,
    ): Int =
        ((lastOption + 1) until lines.size)
            .takeWhile { lines[it].text.isNotBlank() && !isHint(lines[it]) && !separator.matches(lines[it].text) && lines[it].muted }
            .count()

    private fun question(
        lines: List<ScreenLine>,
        firstOption: Int,
    ): String {
        val picked = ArrayList<String>()
        var blanks = 0
        for (i in (firstOption - 1) downTo maxOf(0, firstOption - 14)) {
            val l = lines[i]
            if (separator.matches(l.text) || agentOutput.containsMatchIn(l.text)) break
            if (l.text.isBlank()) {
                if (++blanks >= 2 && picked.isNotEmpty()) break
                continue
            }
            blanks = 0
            if (isHint(l)) continue
            picked +=
                l.text
                    .trim()
                    .removePrefix("▶")
                    .removePrefix("❯")
                    .removePrefix("›")
                    .removePrefix(">")
                    .trim()
            if (picked.size >= 8) break
        }
        return picked.reversed().joinToString("\n")
    }

    private fun optionLabel(text: String): String =
        numbered.find(text)?.groupValues?.get(3)
            ?: cursor.find(text)?.groupValues?.get(3)
            ?: text.trim()

    private fun cursorMarked(text: String) = text.trimStart().firstOrNull()?.let { it in "❯›▶>" } == true

    /** Key hints are short or dot-separated ("↑↓ navigate · Enter select"); prose that merely says "choose" is not. */
    private fun isHint(l: ScreenLine): Boolean {
        val t = l.text.trim()
        if (t.isEmpty() || !hintWords.containsMatchIn(t)) return false
        return t.contains('·') || t.startsWith("Press") || t.any { it in "↑↓↵" } || t.length < 45
    }
}
