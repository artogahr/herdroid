package dev.herdroid.core.herdr

import org.junit.Assert.assertEquals
import org.junit.Test

class SwipeOrderTest {
    private fun pane(
        id: String,
        tab: String,
        ws: String = "w1",
    ) = Pane(id = id, tabId = tab, workspaceId = ws)

    @Test
    fun walksPanesInReadingOrderThenIntoTheNextTab() {
        val snap =
            Snapshot(
                version = "0",
                protocol = 22,
                workspaces = listOf(Workspace("w1", "one", 1), Workspace("w2", "two", 2)),
                tabs = listOf(Tab("t2", "w1", "second", 2), Tab("t1", "w1", "first", 1), Tab("t9", "w2", "other", 1)),
                panes = listOf(pane("b", "t1"), pane("a", "t1"), pane("c", "t2"), pane("x", "t9", "w2"), pane("orphan", "t2")),
                layouts =
                    listOf(
                        Layout("t1", listOf(LayoutPane("b", Rect(50, 0, 50, 10)), LayoutPane("a", Rect(0, 0, 50, 10)))),
                        Layout("t2", listOf(LayoutPane("c", Rect(0, 0, 100, 10)))),
                    ),
            )
        // Panes missing from the layout (just created) still appear, after the laid-out ones.
        assertEquals(listOf("a", "b", "c", "orphan"), snap.swipeOrder("w1").map { it.id })
        assertEquals(listOf("x"), snap.swipeOrder("w2").map { it.id })
    }
}
