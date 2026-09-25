package dev.herdroid.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.herdroid.core.herdr.AgentStatus
import dev.herdroid.core.herdr.Pane
import dev.herdroid.core.herdr.Snapshot
import dev.herdroid.core.herdr.Workspace
import dev.herdroid.data.Connection
import dev.herdroid.data.ConnectionState
import dev.herdroid.data.HerdrActions
import dev.herdroid.data.HerdrSession
import dev.herdroid.thread.ThreadController
import kotlinx.coroutines.launch
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
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
    val drawer = rememberSideDrawerState()
    var dialog by remember { mutableStateOf<NameDialog?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    val actions = remember(connected) { HerdrActions(connected.api) }
    var selectedId by remember { mutableStateOf(connection.lastPaneId) }
    // Per pane: show the terminal instead of the chat.
    val terminalMode = remember { mutableStateMapOf<String, Boolean>() }

    val latestSnapshot by rememberUpdatedState(snapshot)

    fun threadFor(pane: Pane) =
        threads.getOrPut(pane.id) {
            ThreadController(scope, connected, pane) {
                latestSnapshot
                    ?.panes
                    ?.filter { it.id != pane.id }
                    ?.mapNotNull { it.agentSession?.value }
                    ?.toSet()
                    .orEmpty()
            }
        }

    // Forget conversations whose pane closed.
    LaunchedEffect(snapshot) {
        val open = snapshot?.panes?.map { it.id }?.toSet() ?: return@LaunchedEffect
        threads.keys.filter { it !in open }.forEach { threads.remove(it)?.deactivate() }
    }

    // A selection the snapshot does not know yet (a pane just created from the phone) is
    // pending: keep showing the last pane rather than jumping elsewhere, and let nothing
    // overwrite it until the next snapshot brings the pane.
    var shownId by remember { mutableStateOf<String?>(null) }
    val pending = snapshot != null && selectedId != null && snapshot.panes.none { it.id == selectedId }
    val selected =
        snapshot?.let { snap ->
            snap.panes.firstOrNull { it.id == selectedId }
                ?: snap.panes.firstOrNull { it.id == shownId }
                ?: defaultPane(snap)
        }
    LaunchedEffect(selected?.id) { shownId = selected?.id }
    val stillPending by rememberUpdatedState(pending)
    val pages = selected?.let { snapshot.swipeOrder(it.workspaceId) }.orEmpty()
    // Crash family "LayoutCoordinate operations are only valid when isAttached" / "LayoutNode
    // should be attached to an owner": the pager was force-remeasured while its pages were
    // changing. Two triggers: removing a focused view (the terminal) makes Android search for
    // focus, which re-enters Compose and re-lays out the pager mid-removal; and scrollToPage
    // during page-list churn. So: no focus inside pages while they change, navigate only on
    // a new selection, and freeze the page list while a swipe is in progress.
    val workspaceId = selected?.workspaceId
    val stablePagesState = remember(workspaceId) { mutableStateOf(pages) }
    var stablePages by stablePagesState
    val pager =
        remember(workspaceId) {
            PagerState(currentPage = pages.indexOfFirst { it.id == selected?.id }.coerceAtLeast(0)) { stablePagesState.value.size }
        }
    LaunchedEffect(pages, pager.isScrollInProgress) {
        if (!pager.isScrollInProgress) stablePages = pages
    }
    val focus = LocalFocusManager.current
    LaunchedEffect(pager.isScrollInProgress) {
        if (pager.isScrollInProgress) focus.clearFocus(force = true)
    }
    LaunchedEffect(stablePages.map { it.id }) { focus.clearFocus(force = true) }

    // Opening a pane from the side panel moves the pager to it.
    LaunchedEffect(selected?.id) {
        val index = stablePages.indexOfFirst { it.id == selected?.id }
        if (index >= 0 && index != pager.currentPage && !pager.isScrollInProgress) pager.scrollToPage(index)
    }
    // Swiping selects the pane that settles on screen.
    LaunchedEffect(stablePages) {
        snapshotFlow { pager.settledPage }.collect { page ->
            if (stillPending) return@collect
            stablePages.getOrNull(page)?.let {
                selectedId = it.id
                connection.lastPaneId = it.id
            }
        }
    }

    val current = stablePages.getOrNull(pager.currentPage) ?: selected

    // Only the pane on screen streams; the rest keep what they already loaded.
    val settledId = stablePages.getOrNull(pager.settledPage)?.id
    LaunchedEffect(settledId, live) {
        threads.forEach { (id, thread) -> if (id != settledId) thread.deactivate() }
        if (live) stablePages.getOrNull(pager.settledPage)?.takeIf { it.hasChat }?.let { threadFor(it).activate() }
    }
    val showTerminal = current != null && (terminalMode[current.id] == true || !current.hasChat)

    SideDrawer(
        state = drawer,
        drawerContent = {
            Sidebar(
                host = connection.config.label,
                snapshot = snapshot,
                currentPane = current,
                onOpenWorkspace = { ws ->
                    focus.clearFocus(force = true)
                    val target =
                        snapshot?.panes?.firstOrNull { it.tabId == ws.activeTabId && it.agent != null }
                            ?: snapshot?.panes?.firstOrNull { it.tabId == ws.activeTabId }
                            ?: snapshot?.panes?.firstOrNull { it.workspaceId == ws.id }
                    target?.let { selectedId = it.id }
                    scope.launch { drawer.close() }
                },
                onOpenPane = { pane ->
                    focus.clearFocus(force = true)
                    selectedId = pane.id
                    scope.launch { drawer.close() }
                },
                onDisconnect = { connection.disconnect() },
                onNewSpace = { dialog = NameDialog.NewSpace(current?.foregroundCwd ?: current?.cwd) },
                onRenameSpace = { dialog = NameDialog.RenameSpace(it) },
                onRenamePane = { dialog = NameDialog.RenamePane(it) },
            )
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = {
                            focus.clearFocus(force = true)
                            scope.launch { drawer.open() }
                        }) { Icon(Icons.Filled.Menu, "Spaces and agents") }
                    },
                    title = {
                        current?.let { pane ->
                            Box(
                                Modifier.combinedClickable(
                                    onClick = {},
                                    onLongClick = { dialog = NameDialog.RenamePane(pane) },
                                ),
                            ) { PaneTitle(pane, snapshot, threads[pane.id]?.title) }
                        }
                    },
                    actions = {
                        if (current?.hasChat == true) {
                            IconButton(onClick = {
                                // The toggle swaps the composer for a focusable terminal view.
                                focus.clearFocus(force = true)
                                terminalMode[current.id] = !showTerminal
                            }) {
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
                if (stablePages.size > 1) PageDots(stablePages, pager.currentPage)
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
                            key = { stablePages.getOrNull(it)?.id ?: "gone-$it" },
                            modifier =
                                Modifier.fillMaxSize().pullDrawerAtStart(pager, drawer, scope) {
                                    focus.clearFocus(force = true)
                                },
                        ) { page ->
                            val pane = stablePages.getOrNull(page) ?: return@HorizontalPager
                            if (terminalMode[pane.id] == true || !pane.hasChat) {
                                // Swapped out only once the page stops being current: by then
                                // the swipe start has already made the terminal drop focus.
                                if (page == pager.currentPage) {
                                    TerminalPane(connected, pane, swiping = pager.isScrollInProgress)
                                } else {
                                    TerminalPlaceholder(pane)
                                }
                            } else {
                                ThreadPane(threadFor(pane), onOpenTerminal = {
                                    focus.clearFocus(force = true)
                                    terminalMode[pane.id] = true
                                })
                            }
                        }
                    }
                }
            }
        }
    }
    dialog?.let { d ->
        NameDialogView(
            dialog = d,
            error = actionError,
            onDismiss = {
                dialog = null
                actionError = null
            },
            onConfirm = { name, folder ->
                scope.launch {
                    runCatching {
                        when (d) {
                            is NameDialog.NewSpace -> actions.createSpace(name, folder)?.let { selectedId = it }
                            is NameDialog.RenameSpace -> actions.renameSpace(d.workspace.id, name)
                            is NameDialog.RenamePane -> actions.renamePane(d.pane.id, name)
                        }
                    }.onSuccess {
                        dialog = null
                        actionError = null
                        session.refreshNow()
                        if (d is NameDialog.NewSpace) drawer.close()
                    }.onFailure { actionError = it.message }
                }
            },
        )
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

private sealed interface NameDialog {
    data class NewSpace(
        val folder: String?,
    ) : NameDialog

    data class RenameSpace(
        val workspace: Workspace,
    ) : NameDialog

    data class RenamePane(
        val pane: Pane,
    ) : NameDialog
}

@Composable
private fun NameDialogView(
    dialog: NameDialog,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: (String, String?) -> Unit,
) {
    val (title, initial) =
        when (dialog) {
            is NameDialog.NewSpace -> "New space" to ""
            is NameDialog.RenameSpace -> "Rename space" to dialog.workspace.label
            is NameDialog.RenamePane -> "Rename" to (dialog.pane.label ?: dialog.pane.displayTitle)
        }
    // Start with the old name selected, so typing replaces it as in Android's rename dialogs.
    var field by remember(dialog) { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val name = field.text
    var folder by remember(dialog) { mutableStateOf((dialog as? NameDialog.NewSpace)?.folder.orEmpty()) }
    val requester = remember { FocusRequester() }
    LaunchedEffect(dialog) { requester.requestFocus() }
    // An agent's label may be cleared to fall back to its terminal title; spaces need a name.
    val valid = name.isNotBlank() || dialog is NameDialog.RenamePane
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    field,
                    { field = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(requester),
                )
                if (dialog is NameDialog.NewSpace) {
                    OutlinedTextField(
                        folder,
                        { folder = it },
                        label = { Text("Folder on the server") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "The space starts with a shell in this folder.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (dialog is NameDialog.RenamePane) {
                    Text(
                        "Leave empty to show the terminal title again.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onConfirm(name.trim(), folder.trim().ifBlank { null }) }) {
                Text(if (dialog is NameDialog.NewSpace) "Create" else "Rename")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
