package dev.herdroid.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.herdroid.core.herdr.AgentStatus
import dev.herdroid.core.herdr.Pane
import dev.herdroid.core.herdr.Snapshot
import dev.herdroid.data.Connection
import dev.herdroid.data.ConnectionState
import dev.herdroid.data.HerdrSession
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    connection: Connection,
    connected: ConnectionState.Connected,
    reconnecting: ConnectionState.Reconnecting?,
) {
    val scope = rememberCoroutineScope()
    val session = remember(connected) { HerdrSession(scope, connected) }
    LaunchedEffect(session) { session.start() }
    val snapshot = session.snapshot
    val drawer = rememberDrawerState(DrawerValue.Closed)
    var selectedId by remember { mutableStateOf(connection.lastPaneId) }
    // Per pane: show the terminal instead of the chat.
    val terminalMode = remember { mutableStateMapOf<String, Boolean>() }

    val selected = snapshot?.let { snap -> snap.panes.firstOrNull { it.id == selectedId } ?: defaultPane(snap) }
    val pages = selected?.let { session.swipeOrder(it.workspaceId) }.orEmpty()
    val pager = rememberPagerState(pageCount = { pages.size })

    // Opening a pane from the side panel scrolls the pager to it.
    LaunchedEffect(selected?.id, pages.size) {
        val index = pages.indexOfFirst { it.id == selected?.id }
        if (index >= 0 && index != pager.currentPage) pager.scrollToPage(index)
    }
    // Swiping selects the pane that settles on screen.
    LaunchedEffect(pages) {
        snapshotFlow { pager.settledPage }.collect { page ->
            pages.getOrNull(page)?.let {
                selectedId = it.id
                connection.lastPaneId = it.id
            }
        }
    }

    val current = pages.getOrNull(pager.currentPage) ?: selected
    val showTerminal = current != null && (terminalMode[current.id] == true || !current.hasChat)

    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = drawer.isOpen,
        drawerContent = {
            ModalDrawerSheet {
                Sidebar(
                    host = connection.config.host,
                    snapshot = snapshot,
                    currentPane = current,
                    onOpenWorkspace = { ws ->
                        val target =
                            snapshot?.panes?.firstOrNull { it.tabId == ws.activeTabId && it.agent != null }
                                ?: snapshot?.panes?.firstOrNull { it.tabId == ws.activeTabId }
                                ?: snapshot?.panes?.firstOrNull { it.workspaceId == ws.id }
                        target?.let { selectedId = it.id }
                        scope.launch { drawer.close() }
                    },
                    onOpenPane = { pane ->
                        selectedId = pane.id
                        scope.launch { drawer.close() }
                    },
                    onDisconnect = { connection.disconnect() },
                )
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Filled.Menu, "Spaces and agents") }
                    },
                    title = { current?.let { PaneTitle(it, snapshot) } },
                    actions = {
                        if (current?.hasChat == true) {
                            IconButton(onClick = { terminalMode[current.id] = !showTerminal }) {
                                Icon(
                                    if (showTerminal) Icons.AutoMirrored.Filled.Chat else Icons.Filled.Terminal,
                                    if (showTerminal) "Chat" else "Terminal",
                                )
                            }
                        }
                    },
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
                AnimatedVisibility(reconnecting != null) { ReconnectBanner(reconnecting) }
                if (pages.size > 1) PageDots(pages, pager.currentPage)
                when {
                    snapshot == null -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    }

                    pages.isEmpty() -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No panes yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    else -> {
                        HorizontalPager(
                            state = pager,
                            key = { pages[it].id },
                            // Terminals handle their own touch gestures.
                            userScrollEnabled = !showTerminal,
                            modifier = Modifier.fillMaxSize(),
                        ) { page ->
                            val pane = pages[page]
                            if (terminalMode[pane.id] == true || !pane.hasChat) {
                                TerminalPane(connected, pane)
                            } else {
                                ThreadPane(connected, pane, onOpenTerminal = { terminalMode[pane.id] = true })
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Something that needs the user, else what herdr has focused, else anything. */
private fun defaultPane(snap: Snapshot): Pane? =
    snap.panes.firstOrNull { it.agentStatus == AgentStatus.BLOCKED }
        ?: snap.panes.firstOrNull { it.agentStatus == AgentStatus.DONE }
        ?: snap.panes.firstOrNull { it.id == snap.focusedPaneId }
        ?: snap.panes.firstOrNull()

@Composable
private fun PaneTitle(
    pane: Pane,
    snapshot: Snapshot?,
) {
    val space = snapshot?.workspaces?.firstOrNull { it.id == pane.workspaceId }?.label
    val tab = snapshot?.tabs?.firstOrNull { it.id == pane.tabId }?.label
    Row(verticalAlignment = Alignment.CenterVertically) {
        AgentAvatar(pane.agent, size = 34.dp)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(pane.displayTitle, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                pane.agentStatus?.let {
                    StatusDot(it, size = 7.dp)
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    listOfNotNull(space, tab, pane.agentStatus?.let { statusStyle(it).first }).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Where you are in the space: one dot per pane, with a gap between tabs. */
@Composable
private fun PageDots(
    pages: List<Pane>,
    current: Int,
) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        pages.forEachIndexed { i, pane ->
            if (i > 0 && pages[i - 1].tabId != pane.tabId) Spacer(Modifier.width(8.dp))
            val color = if (i == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
            Box(
                Modifier
                    .padding(horizontal = 3.dp)
                    .size(if (i == current) 8.dp else 6.dp)
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}

@Composable
private fun ReconnectBanner(state: ConnectionState.Reconnecting?) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                "Reconnecting…" + (state?.error?.let { " ($it)" } ?: ""),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
