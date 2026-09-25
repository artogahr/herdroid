package dev.herdroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
            LargeTopAppBar(
                title = { Text("Herdroid") },
                actions = { TextButton(onClick = onDisconnect) { Text("Disconnect") } },
            )
        },
    ) { padding ->
        val snap = snapshot
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) } }
            if (snap == null) {
                item { Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                return@LazyColumn
            }
            val workspaces = snap.workspaces.associateBy { it.id }
            val order = snap.workspaces.associate { it.id to it.number }
            val agents = snap.panes.filter { it.agent != null }.sortedWith(compareBy({ order[it.workspaceId] }, { it.id }))
            val shells = snap.panes.filter { it.agent == null }
            for ((title, statuses) in inboxSections) {
                val rows = agents.filter { (it.agentStatus ?: AgentStatus.UNKNOWN) in statuses }
                if (rows.isEmpty()) continue
                item(key = "h-$title") { SectionHeader(title) }
                items(rows, key = { it.id }) { pane ->
                    ConversationRow(pane, workspaces[pane.workspaceId]?.label, onClick = { onOpen(pane) })
                }
            }
            if (shells.isNotEmpty()) {
                item(key = "h-terminals") { SectionHeader("Terminals") }
                items(shells, key = { it.id }) { pane ->
                    ConversationRow(pane, workspaces[pane.workspaceId]?.label, onClick = { onOpen(pane) })
                }
            }
            item { Spacer(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) }
        }
    }
}

private val inboxSections =
    listOf(
        "Needs you" to setOf(AgentStatus.BLOCKED),
        "Finished" to setOf(AgentStatus.DONE),
        "Working" to setOf(AgentStatus.WORKING),
        "Idle" to setOf(AgentStatus.IDLE, AgentStatus.UNKNOWN),
    )

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 6.dp),
    )
}

@Composable
private fun ConversationRow(
    pane: Pane,
    project: String?,
    onClick: () -> Unit,
) {
    val status = pane.agentStatus
    val emphasized = status == AgentStatus.BLOCKED || status == AgentStatus.DONE
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AgentAvatar(pane.agent)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                pane.title?.substringBefore(" | ") ?: pane.name ?: pane.id,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(project, pane.agent ?: "terminal").joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (status != null && pane.agent != null) {
            Spacer(Modifier.width(8.dp))
            val (label, color) = statusStyle(status)
            if (status == AgentStatus.WORKING) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = color)
            } else if (emphasized) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(color))
            } else {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@Composable
fun AgentAvatar(agent: String?) {
    val (bg, fg, letter) =
        when (agent) {
            "claude" -> {
                Triple(Color(0xFFD97757), Color.White, "C")
            }

            "codex" -> {
                Triple(Color(0xFF10A37F), Color.White, "X")
            }

            null -> {
                Triple(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurfaceVariant, ">")
            }

            else -> {
                Triple(
                    MaterialTheme.colorScheme.tertiaryContainer,
                    MaterialTheme.colorScheme.onTertiaryContainer,
                    agent.take(1).uppercase(),
                )
            }
        }
    Box(Modifier.size(44.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Text(letter, color = fg, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}
