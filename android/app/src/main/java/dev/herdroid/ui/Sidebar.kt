package dev.herdroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.herdroid.core.herdr.AgentStatus
import dev.herdroid.core.herdr.Pane
import dev.herdroid.core.herdr.Snapshot
import dev.herdroid.core.herdr.Workspace

private val attentionOrder =
    mapOf(AgentStatus.BLOCKED to 0, AgentStatus.DONE to 1, AgentStatus.WORKING to 2, AgentStatus.IDLE to 3, AgentStatus.UNKNOWN to 4)

/** Mirrors herdr's sidebar: spaces on top, agents below. */
@Composable
fun Sidebar(
    host: String,
    snapshot: Snapshot?,
    currentPane: Pane?,
    onOpenWorkspace: (Workspace) -> Unit,
    onOpenPane: (Pane) -> Unit,
    onDisconnect: () -> Unit,
) {
    Column(Modifier.fillMaxHeight().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Herdroid", style = MaterialTheme.typography.titleLarge)
                Text(host, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onDisconnect) { Text("Switch server") }
        }
        val workspaces = snapshot?.workspaces?.sortedBy { it.number }.orEmpty()
        val agents =
            snapshot
                ?.panes
                ?.filter { it.agent != null }
                ?.sortedWith(compareBy({ attentionOrder[it.agentStatus] ?: 5 }, { it.workspaceId }))
                .orEmpty()
        val labels = workspaces.associate { it.id to it.label }

        SectionLabel("Spaces")
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(workspaces, key = { it.id }) { ws ->
                NavigationDrawerItem(
                    icon = {
                        Text(
                            "${ws.number}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    label = { Text(ws.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    badge = { StatusDot(ws.agentStatus.takeIf { it != AgentStatus.IDLE && it != AgentStatus.UNKNOWN }) },
                    selected = ws.id == currentPane?.workspaceId,
                    onClick = { onOpenWorkspace(ws) },
                )
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        SectionLabel("Agents")
        LazyColumn(Modifier.weight(1.3f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(agents, key = { it.id }) { pane ->
                NavigationDrawerItem(
                    icon = { AgentAvatar(pane.agent, size = 28.dp) },
                    label = {
                        Column {
                            Text(pane.displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                labels[pane.workspaceId].orEmpty(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    },
                    badge = { StatusDot(pane.agentStatus.takeIf { it != AgentStatus.IDLE && it != AgentStatus.UNKNOWN }, size = 9.dp) },
                    selected = pane.id == currentPane?.id,
                    onClick = { onOpenPane(pane) },
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 6.dp).size(width = 200.dp, height = 20.dp),
    )
}
