package dev.herdroid.core.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptParserTest {
    private fun fixture(name: String): List<ScreenLine> {
        val text = javaClass.getResource("/fixtures/prompts/$name")!!.readText()
        return if (name.endsWith(".ansi")) AnsiScreen.parse(text) else AnsiScreen.plain(text)
    }

    private fun parse(name: String) = PromptParser.parse(fixture(name))

    @Test
    fun kimiApproval() {
        val p = assertNotNull(parse("kimi-approval.ansi")).let { parse("kimi-approval.ansi")!! }
        assertEquals(listOf("Approve once", "Approve for this session", "Reject", "Reject with feedback"), p.options.map { it.label })
        assertEquals(0, p.selected)
        assertTrue(p.question, p.question.contains("Run this command?"))
        assertTrue(p.question, p.question.contains("echo hi"))
        assertEquals(listOf(false, false, false, true), p.options.map { it.wantsText })
        assertEquals(false, p.textEntry)
    }

    @Test
    fun kimiFeedbackIsTextEntry() {
        val p = parse("kimi-feedback.ansi")!!
        assertEquals(3, p.selected)
        assertTrue(p.hint, p.textEntry)
    }

    @Test
    fun kimiTrustSeparatesDescriptionsByColor() {
        val p = parse("kimi-trust.ansi")!!
        assertEquals(listOf("Trust this folder", "Don't trust"), p.options.map { it.label })
        assertEquals("Enable project MCP servers. Remembered for this folder.", p.options[0].description)
        assertEquals(0, p.selected)
        assertTrue(p.question, p.question.startsWith("Trust this folder?"))
        // herdr reports this screen as idle, so the hint is what marks it as a prompt.
        assertNotNull(p.hint)
        assertTrue(p.question, p.question.contains("Project-level MCP servers are disabled"))
    }

    @Test
    fun codexTrust() {
        val p = parse("codex-trust.ansi")!!
        assertEquals(listOf("Yes, continue", "No, quit"), p.options.map { it.label })
        assertEquals(0, p.selected)
        assertTrue(p.question, p.question.contains("Do you trust the contents of this directory?"))
        assertEquals("Press enter to continue", p.hint)
    }

    @Test
    fun claudeTrust() {
        val p = parse("claude-trust.txt")!!
        assertEquals(listOf("No, exit", "Yes, I trust this folder"), p.options.map { it.label })
        assertEquals(0, p.selected)
        assertTrue(p.question, p.question.contains("Quick safety check"))
    }

    @Test
    fun claudePermission() {
        val p = parse("claude-permission.txt")!!
        assertEquals(
            listOf("Yes", "No, and let auto mode decide", "No, and tell Claude what to do differently (esc)"),
            p.options.map { it.label },
        )
        assertEquals(listOf(false, false, true), p.options.map { it.wantsText })
        assertTrue(p.question, p.question.contains("git push origin HEAD:main"))
        assertTrue(p.question, p.question.endsWith("Do you want to proceed?"))
        assertEquals("Esc to cancel · Tab to amend · ctrl+e to explain", p.hint)
    }

    @Test
    fun idleInputIsNotAPrompt() {
        assertNull(parse("claude-idle.ansi"))
    }

    @Test
    fun numberedListInAnAnswerIsNotAPrompt() {
        // The list sits above the input box, so it is not the bottom of the screen.
        assertNull(parse("claude-question.txt"))
    }
}
