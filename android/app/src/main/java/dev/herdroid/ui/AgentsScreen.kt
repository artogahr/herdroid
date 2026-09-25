package dev.herdroid.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.herdroid.core.herdr.AgentStatus
import dev.herdroid.core.herdr.Pane
import dev.herdroid.core.herdr.Snapshot
import dev.herdroid.data.ConnectionState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val globalEvents =
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentsScreen(
    connected: ConnectionState.Connected,
    onOpen: (Pane) -> Unit,
    onDisconnect: () -> Unit,
) {
    var snapshot by remember { mutableStateOf<Snapshot?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(connected) {
        val refresh = Channel<Unit>(Channel.CONFLATED)
        refresh.trySend(Unit)
        launch {
            val subs = globalEvents.map { type -> buildJsonObject { put("type", type) } }
            runCatching { connected.api.subscribe(subs).collect { refresh.trySend(Unit) } }
                .onFailure { error = "events: ${it.message}" }
        }
        launch {
            // Status changes need per-pane subscriptions; poll until the inbox tracks them.
            while (true) {
                delay(5_000)
                refresh.trySend(Unit)
            }
        }
        for (tick in refresh) {
            runCatching { connected.api.snapshot() }
                .onSuccess {
                    snapshot = it
                    error = null
                }.onFailure { error = it.message }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Herdroid") },
                actions = { TextButton(onClick = onDisconnect) { Text("Disconnect") } },
            )
        },
    ) { padding ->
        val snap = snapshot
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) } }
            if (snap == null) return@LazyColumn
            for (workspace in snap.workspaces.sortedBy { it.number }) {
                val panes = snap.panes.filter { it.workspaceId == workspace.id }
                item(key = workspace.id) {
                    Text(
                        workspace.label,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                    )
                }
                items(panes, key = { it.id }) { pane ->
                    val tab = snap.tabs.firstOrNull { it.id == pane.tabId }
                    ListItem(
                        headlineContent = { Text(pane.title ?: pane.name ?: pane.id, maxLines = 1) },
                        supportingContent = { Text("${pane.agent ?: "shell"} · ${tab?.label ?: pane.tabId}", maxLines = 1) },
                        trailingContent = { pane.agentStatus?.let { StatusBadge(it) } },
                        modifier = Modifier.clickable { onOpen(pane) },
                    )
                }
            }
        }
    }
}

@Composable
fun StatusBadge(status: AgentStatus) {
    val (label, color) =
        when (status) {
            AgentStatus.BLOCKED -> "needs input" to Color(0xFFE5484D)
            AgentStatus.WORKING -> "working" to Color(0xFF3E63DD)
            AgentStatus.DONE -> "done" to Color(0xFF30A46C)
            AgentStatus.IDLE -> "idle" to MaterialTheme.colorScheme.outline
            AgentStatus.UNKNOWN -> "?" to MaterialTheme.colorScheme.outline
        }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = color, style = MaterialTheme.typography.labelMedium)
    }
}
