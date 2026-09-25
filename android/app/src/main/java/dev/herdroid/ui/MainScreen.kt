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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import dev.herdroid.thread.ThreadController
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    connection: Connection,
    connected: ConnectionState.Connected,
    reconnecting: ConnectionState.Reconnecting?,
) {
    val scope = rememberCoroutineScope()
    // Both outlive reconnects: they keep what they loaded and re-bind to the new link.
    val session = remember { HerdrSession(scope) }
    val threads = remember { HashMap<String, ThreadController>() }
    val live = reconnecting == null
    LaunchedEffect(connected, live) {
        if (live) {
            session.bind(connected)
            threads.values.forEach { it.rebind(connected) }
        } else {
            session.pause()
            threads.values.forEach { it.deactivate() }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            session.pause()
            threads.values.forEach { it.deactivate() }
        }
    }
    val snapshot = session.snapshot
    val drawer = rememberDrawerState(DrawerValue.Closed)
    var selectedId by remember { mutableStateOf(connection.lastPaneId) }
    // Per pane: show the terminal instead of the chat.
    val terminalMode = remember { mutableStateMapOf<String, Boolean>() }

    fun threadFor(pane: Pane) = threads.getOrPut(pane.id) { ThreadController(scope, connected, pane) }

    // Forget conversations whose pane closed.
    LaunchedEffect(snapshot) {
        val open = snapshot?.panes?.map { it.id }?.toSet() ?: return@LaunchedEffect
        threads.keys.filter { it !in open }.forEach { threads.remove(it)?.deactivate() }
    }

    val selected = snapshot?.let { snap -> snap.panes.firstOrNull { it.id == selectedId } ?: defaultPane(snap) }
    val pages = selected?.let { snapshot.swipeOrder(it.workspaceId) }.orEmpty()
    val latestPages by rememberUpdatedState(pages)
    val pager = rememberPagerState(pageCount = { latestPages.size })

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

    // Only the pane on screen streams; the rest keep what they already loaded.
    val settledId = pages.getOrNull(pager.settledPage)?.id
    LaunchedEffect(settledId, live) {
        threads.forEach { (id, thread) -> if (id != settledId) thread.deactivate() }
        if (live) pages.getOrNull(pager.settledPage)?.takeIf { it.hasChat }?.let { threadFor(it).activate() }
    }
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
                    title = { current?.let { PaneTitle(it, snapshot, threads[it.id]?.title) } },
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
                            // The list can shrink under the pager when a pane closes mid-swipe.
                            key = { latestPages.getOrNull(it)?.id ?: "gone-$it" },
                            modifier = Modifier.fillMaxSize(),
                        ) { page ->
                            val pane = latestPages.getOrNull(page) ?: return@HorizontalPager
                            if (terminalMode[pane.id] == true || !pane.hasChat) {
                                // The terminal is an Android View; the pager crashes placing one
                                // that scrolls in or out, so only the settled page gets a live one.
                                if (page == pager.settledPage && !pager.isScrollInProgress) {
                                    TerminalPane(connected, pane)
                                } else {
                                    TerminalPlaceholder(pane)
                                }
                            } else {
                                ThreadPane(threadFor(pane), onOpenTerminal = { terminalMode[pane.id] = true })
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
    conversationTitle: String?,
) {
    val space = snapshot?.workspaces?.firstOrNull { it.id == pane.workspaceId }?.label
    val tab = snapshot?.tabs?.firstOrNull { it.id == pane.tabId }?.label
    Row(verticalAlignment = Alignment.CenterVertically) {
        AgentAvatar(pane.agent, size = 34.dp)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                conversationTitle ?: pane.displayTitle,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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

@Composable
private fun TerminalPlaceholder(pane: Pane) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.Terminal, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.size(8.dp))
            Text(pane.displayTitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
