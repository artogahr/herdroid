package dev.herdroid.core.prompt

/** One screen row as text, and whether all of it is drawn muted (grey or faint). */
data class ScreenLine(
    val text: String,
    val muted: Boolean,
)

/**
 * Reads the ANSI text of `pane.read --format ansi`. Only styling matters here: agents draw
 * option descriptions and key hints in grey or faint text, which is how they are told apart
 * from the options themselves.
 */
object AnsiScreen {
    private val escape = Regex("""\u001B(?:\[([0-9;?]*)([A-Za-z])|\][^\u0007\u001B]*(?:\u0007|\u001B\\)|[()][A-Za-z0-9]|[=>])""")

    fun parse(screen: String): List<ScreenLine> {
        var faint = false
        var grey = false
        return screen.replace("\r", "").split('\n').map { raw ->
            val text = StringBuilder()
            var visible = 0
            var mutedChars = 0
            var at = 0
            for (m in escape.findAll(raw)) {
                val plain = raw.substring(at, m.range.first)
                text.append(plain)
                val shown = plain.count { !it.isWhitespace() }
                visible += shown
                if (faint || grey) mutedChars += shown
                if (m.groupValues[2] == "m") {
                    val codes = m.groupValues[1].split(';').map { it.toIntOrNull() ?: 0 }
                    var i = 0
                    while (i < codes.size) {
                        when (val c = codes[i]) {
                            0 -> {
                                faint = false
                                grey = false
                            }

                            2 -> {
                                faint = true
                            }

                            22 -> {
                                faint = false
                            }

                            39 -> {
                                grey = false
                            }

                            90 -> {
                                grey = true
                            }

                            in 30..37, in 91..97 -> {
                                grey = false
                            }

                            38 -> {
                                if (codes.getOrNull(i + 1) == 2 && i + 4 < codes.size) {
                                    grey = isGrey(codes[i + 2], codes[i + 3], codes[i + 4])
                                    i += 4
                                } else if (codes.getOrNull(i + 1) == 5 && i + 2 < codes.size) {
                                    val n = codes[i + 2]
                                    grey = n == 8 || n in 232..247
                                    i += 2
                                }
                            }

                            else -> {
                                if (c == 1) Unit
                            }
                        }
                        i++
                    }
                }
                at = m.range.last + 1
            }
            val rest = raw.substring(at)
            text.append(rest)
            val shown = rest.count { !it.isWhitespace() }
            visible += shown
            if (faint || grey) mutedChars += shown
            ScreenLine(text.toString().trimEnd(), visible > 0 && mutedChars == visible)
        }
    }

    /** Low-saturation, not bright: the greys agents use for secondary text. */
    private fun isGrey(
        r: Int,
        g: Int,
        b: Int,
    ): Boolean = maxOf(r, g, b) - minOf(r, g, b) < 24 && maxOf(r, g, b) <= 170

    /** Plain text (no styling) as screen lines, for fixtures and tests. */
    fun plain(screen: String): List<ScreenLine> = screen.replace("\r", "").split('\n').map { ScreenLine(it.trimEnd(), false) }
}
