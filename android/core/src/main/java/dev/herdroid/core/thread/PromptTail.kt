package dev.herdroid.core.thread

/** The bottom of an agent's screen, where it draws its question and choices. */
fun promptTail(
    screen: String,
    maxLines: Int = 14,
): String =
    screen
        .lines()
        .map { it.trimEnd() }
        .dropLastWhile { it.isBlank() }
        .takeLast(maxLines)
        .dropWhile { it.isBlank() }
        .joinToString("\n")
