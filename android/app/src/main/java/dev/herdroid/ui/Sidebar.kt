package dev.herdroid.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    onNewSpace: () -> Unit = {},
    onRenameSpace: (Workspace) -> Unit = {},
    onRenamePane: (Pane) -> Unit = {},
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
        val agentsBySpace = agents.sortedBy { it.id }.groupBy { it.workspaceId }

        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(workspaces, key = { it.id }) { ws ->
                SidebarRow(
                    icon = null,
                    label = { Text(ws.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    badge = { AgentDots(agentsBySpace[ws.id].orEmpty()) },
                    selected = ws.id == currentPane?.workspaceId,
                    onClick = { onOpenWorkspace(ws) },
                    onLongClick = { onRenameSpace(ws) },
                )
            }
            item(key = "new-space") {
                SidebarRow(
                    icon = { Icon(Icons.Filled.Add, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    label = { Text("New space", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    badge = {},
                    selected = false,
                    onClick = onNewSpace,
                    onLongClick = onNewSpace,
                )
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        SectionLabel("Agents")
        LazyColumn(Modifier.weight(1.3f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(agents, key = { it.id }) { pane ->
                SidebarRow(
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
                    onLongClick = { onRenamePane(pane) },
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

/** One dot per agent in a space, colored by status; idle agents are hollow. */
@Composable
private fun AgentDots(agents: List<Pane>) {
    if (agents.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        agents.take(MAX_DOTS).forEach { pane ->
            val status = pane.agentStatus ?: AgentStatus.UNKNOWN
            val color = statusStyle(status).second
            val idle = status == AgentStatus.IDLE || status == AgentStatus.UNKNOWN
            Box(
                Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .then(if (idle) Modifier.border(1.5.dp, color, CircleShape) else Modifier.background(color)),
            )
        }
        if (agents.size > MAX_DOTS) {
            Text(
                "+${agents.size - MAX_DOTS}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private const val MAX_DOTS = 6

/**
 * A drawer row like Material's NavigationDrawerItem, plus long-press (which that item lacks):
 * tap opens, long-press renames.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SidebarRow(
    icon: (@Composable () -> Unit)?,
    label: @Composable () -> Unit,
    badge: @Composable () -> Unit,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                },
            ).padding(start = 16.dp, end = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            icon()
            Spacer(Modifier.width(12.dp))
        }
        Box(Modifier.weight(1f)) {
            CompositionLocalProvider(
                LocalContentColor provides
                    if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                LocalTextStyle provides MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp),
            ) { label() }
        }
        Spacer(Modifier.width(8.dp))
        badge()
    }
}
