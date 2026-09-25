package dev.herdroid.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.herdroid.core.herdr.Pane
import dev.herdroid.core.herdr.Snapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val topologyEvents =
    listOf(
        "pane.created",
        "pane.closed",
        "pane.updated",
        "pane.agent_detected",
        "tab.created",
        "tab.closed",
        "tab.renamed",
        "workspace.created",
        "workspace.closed",
        "workspace.renamed",
    )

/** herdr's workspaces, tabs and panes, kept fresh from events. */
class HerdrSession(
    private val scope: CoroutineScope,
    private val connected: ConnectionState.Connected,
) {
    var snapshot by mutableStateOf<Snapshot?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private val refresh = Channel<Unit>(Channel.CONFLATED)

    fun start() {
        refresh.trySend(Unit)
        scope.launch {
            val subs = topologyEvents.map { type -> buildJsonObject { put("type", type) } }
            runCatching { connected.api.subscribe(subs).collect { refresh.trySend(Unit) } }
        }
        scope.launch {
            // Status changes arrive per pane; poll lightly so the side panel stays current.
            while (true) {
                delay(4_000)
                refresh.trySend(Unit)
            }
        }
        scope.launch {
            for (tick in refresh) {
                runCatching { connected.api.snapshot() }
                    .onSuccess {
                        snapshot = it
                        error = null
                    }.onFailure { error = it.message }
            }
        }
    }

    fun requestRefresh() {
        refresh.trySend(Unit)
    }

    /**
     * Panes in swipe order for a workspace: tabs in order, and panes within a tab in reading
     * order. Swiping past a tab's last pane continues into the next tab.
     */
    fun swipeOrder(workspaceId: String): List<Pane> {
        val snap = snapshot ?: return emptyList()
        val panes = snap.panes.filter { it.workspaceId == workspaceId }.associateBy { it.id }
        return snap.tabs
            .filter { it.workspaceId == workspaceId }
            .sortedBy { it.number }
            .flatMap { tab ->
                val ordered =
                    snap.layouts
                        .firstOrNull { it.tabId == tab.id }
                        ?.paneOrder()
                        .orEmpty()
                val inTab = panes.values.filter { it.tabId == tab.id }
                ordered.mapNotNull { panes[it] } + inTab.filter { it.id !in ordered }
            }
    }
}
