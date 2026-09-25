package dev.herdroid.data

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.herdroid.core.herdr.Snapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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

/**
 * herdr's workspaces, tabs and panes, kept fresh from events. The last snapshot survives
 * reconnects so the screen does not blank while the link recovers.
 */
class HerdrSession(
    private val scope: CoroutineScope,
) {
    var snapshot by mutableStateOf<Snapshot?>(null)
        private set

    private var job: Job? = null
    private var bound: ConnectionState.Connected? = null
    private val refresh = Channel<Unit>(Channel.CONFLATED)

    /** Follow [connected]; call again after a reconnect. */
    fun bind(connected: ConnectionState.Connected) {
        if (connected === bound && job?.isActive == true) return
        pause()
        bound = connected
        job =
            scope.launch {
                refresh.trySend(Unit)
                launch {
                    val subs = topologyEvents.map { type -> buildJsonObject { put("type", type) } }
                    runCatching { connected.api.subscribe(subs).collect { refresh.trySend(Unit) } }
                        .onFailure { if (it !is CancellationException) Log.w(TAG, "topology events ended", it) }
                }
                launch {
                    // Status changes arrive per pane; poll lightly so the side panel stays current.
                    while (true) {
                        delay(4_000)
                        refresh.trySend(Unit)
                    }
                }
                for (tick in refresh) {
                    runCatching { connected.api.snapshot() }
                        .onSuccess { snapshot = it }
                        .onFailure { if (it !is CancellationException) Log.w(TAG, "snapshot failed", it) }
                }
            }
    }

    /** Stop polling while the link is down; every attempt would fail. */
    fun pause() {
        job?.cancel()
        job = null
    }

    private companion object {
        const val TAG = "HerdrSession"
    }
}
