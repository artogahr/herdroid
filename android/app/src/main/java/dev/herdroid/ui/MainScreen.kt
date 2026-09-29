package dev.herdroid.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.herdroid.core.herdr.AgentStatus
import dev.herdroid.core.herdr.Launcher
import dev.herdroid.core.herdr.Launchers
import dev.herdroid.core.herdr.Pane
import dev.herdroid.core.herdr.Snapshot
import dev.herdroid.core.herdr.Workspace
import dev.herdroid.core.model.AgentKind
import dev.herdroid.data.Connection
import dev.herdroid.data.ConnectionState
import dev.herdroid.data.HerdrActions
import dev.herdroid.data.HerdrSession
import dev.herdroid.thread.ThreadController
import kotlinx.coroutines.CancellationException
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
    val snackbar = remember { SnackbarHostState() }
    val prefs = uiPrefs()
    var newTab by remember { mutableStateOf<NewTab?>(null) }
    // Loaded when the sheet opens; the last list shows while it refreshes.
    var launchers by remember(connected) { mutableStateOf<List<Launcher>?>(null) }
    var launchersError by remember { mutableStateOf<String?>(null) }
    var launching by remember { mutableStateOf<String?>(null) }
    // Terminal tabs opened from the phone start in control: nobody else is using them yet.
    // Keyed by pane, true once it has been on screen; cleared when you swipe away.
    val autoControl = remember { mutableStateMapOf<String, Boolean>() }

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

    // Two ways the selected pane can be missing from the snapshot. Never seen: it was just
    // created from the phone and the next snapshot will bring it, so keep it pending and
    // keep showing the last pane. Seen before: it closed (an agent or shell exited), so move
    // to its neighbour in the same space rather than jumping somewhere unrelated.
    var shownId by remember { mutableStateOf<String?>(null) }
    val knownIds = remember { HashSet<String>() }
    snapshot?.panes?.forEach { knownIds += it.id }
    var lastPages by remember { mutableStateOf<List<Pane>>(emptyList()) }
    val pending = snapshot != null && selectedId != null && selectedId !in knownIds
    val selected =
        snapshot?.let { snap ->
            snap.panes.firstOrNull { it.id == selectedId }
                ?: (if (pending) snap.panes.firstOrNull { it.id == shownId } else null)
                ?: neighbourOf(shownId ?: selectedId, lastPages, snap)
                ?: defaultPane(snap)
        }
    LaunchedEffect(selected?.id) { shownId = selected?.id }
    val stillPending by rememberUpdatedState(pending)
    val pages = selected?.let { snapshot.swipeOrder(it.workspaceId) }.orEmpty()
    LaunchedEffect(pages) { if (pages.isNotEmpty()) lastPages = pages }
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
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(stablePages.map { it.id }) {
        focus.clearFocus(force = true)
        keyboard?.hide()
    }

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
    // A tab started from the phone settles as a shell and gains its chat once herdr detects
    // the agent, without the settled page changing.
    val settledHasChat = stablePages.getOrNull(pager.settledPage)?.hasChat == true
    LaunchedEffect(settledId, settledHasChat, live) {
        threads.forEach { (id, thread) -> if (id != settledId) thread.deactivate() }
        if (live) stablePages.getOrNull(pager.settledPage)?.takeIf { it.hasChat }?.let { threadFor(it).activate() }
    }
    LaunchedEffect(settledId) {
        autoControl.keys.filter { it != settledId && autoControl[it] == true }.forEach { autoControl.remove(it) }
        if (settledId != null && settledId in autoControl) autoControl[settledId] = true
    }
    val showTerminal = current != null && (terminalMode[current.id] == true || !current.hasChat)

    /** Opens a tab in the space and starts [launcher] in it, or leaves a shell when null. */
    fun launch(
        workspaceId: String,
        folder: String?,
        launcher: Launcher?,
    ) {
        if (launching != null) return
        launching = launcher?.kind ?: TERMINAL
        scope.launch {
            try {
                val paneId = actions.createTab(workspaceId, folder) ?: error("herdr did not return the new tab")
                if (launcher == null) autoControl[paneId] = false
                selectedId = paneId
                connection.lastPaneId = paneId
                newTab = null
                session.refreshNow()
                if (launcher != null) {
                    prefs.updateLastAgentKind(launcher.kind)
                    val taken =
                        latestSnapshot
                            ?.panes
                            ?.mapNotNull { it.name }
                            ?.toSet()
                            .orEmpty()
                    actions.startAgent(paneId, launcher.kind, Launchers.freeName(launcher.kind, taken))
                    session.refreshNow()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val what = launcher?.label ?: "a terminal"
                snackbar.showSnackbar("Could not start $what: ${e.message ?: e::class.simpleName}")
            } finally {
                launching = null
            }
        }
    }

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
            snackbarHost = { SnackbarHost(snackbar) },
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
                if (current != null) {
                    PageDots(stablePages, pager.currentPage, onNewTab = {
                        focus.clearFocus(force = true)
                        newTab = NewTab(current.workspaceId, current.foregroundCwd ?: current.cwd)
                    })
                }
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
                                    TerminalPane(
                                        connected,
                                        pane,
                                        swiping = pager.isScrollInProgress,
                                        startInControl = pane.id in autoControl,
                                    )
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
    newTab?.let { request ->
        LaunchedEffect(request) {
            launchersError = null
            runCatching { actions.launchers() }
                .onSuccess { launchers = it }
                .onFailure { if (launchers == null) launchersError = it.message ?: "Could not list agents" }
        }
        NewTabSheet(
            space =
                snapshot
                    ?.workspaces
                    ?.firstOrNull { it.id == request.workspaceId }
                    ?.label
                    .orEmpty(),
            folder = request.folder.orEmpty(),
            launchers = launchers?.let { Launchers.sorted(it, prefs.lastAgentKind, chatKinds) },
            error = launchersError,
            launching = launching,
            onLaunch = { launcher, folder -> launch(request.workspaceId, folder.trim().ifBlank { null }, launcher) },
            onDismiss = { if (launching == null) newTab = null },
        )
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

/** The pane before (or else after) [closedId] in its space, or any pane left in that space. */
private fun neighbourOf(
    closedId: String?,
    lastPages: List<Pane>,
    snap: Snapshot,
): Pane? {
    val at = lastPages.indexOfFirst { it.id == closedId }
    if (at < 0) return null
    val alive = snap.panes.associateBy { it.id }
    val before = lastPages.take(at).asReversed()
    val after = lastPages.drop(at + 1)
    return (before + after).firstNotNullOfOrNull { alive[it.id] }
        ?: snap.panes.firstOrNull { it.workspaceId == lastPages[at].workspaceId }
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

/** Where you are in the space: one dot per pane, with a gap between tabs, and a new tab button. */
@Composable
private fun PageDots(
    pages: List<Pane>,
    current: Int,
    onNewTab: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (pages.size > 1) {
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
            Spacer(Modifier.width(6.dp))
        }
        Box(
            Modifier.size(28.dp).clip(CircleShape).clickable(onClick = onNewTab),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Add, "New tab", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private const val TERMINAL = "terminal"

private val chatKinds = AgentKind.entries.map { it.name.lowercase() }.toSet()

private data class NewTab(
    val workspaceId: String,
    val folder: String?,
)

/** Pick what the new tab runs: an agent herdr found on the server, or a plain terminal. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewTabSheet(
    space: String,
    folder: String,
    launchers: List<Launcher>?,
    error: String?,
    launching: String?,
    onLaunch: (Launcher?, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var path by remember { mutableStateOf(folder) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.padding(horizontal = 24.dp).padding(bottom = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(if (space.isBlank()) "New tab" else "New tab in $space", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                path,
                { path = it },
                label = { Text("Folder on the server") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            when {
                launchers == null && error == null -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Looking for agents…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                launchers.isNullOrEmpty() -> {
                    Text(
                        error ?: "herdr found no agent CLIs on this server.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        launchers.chunked(2).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                row.forEach { launcher ->
                                    LauncherButton(
                                        launcher,
                                        busy = launching == launcher.kind,
                                        enabled = launching == null,
                                        onClick = { onLaunch(launcher, path) },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
            OutlinedButton(
                onClick = { onLaunch(null, path) },
                enabled = launching == null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (launching == TERMINAL) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Terminal, null, Modifier.size(18.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text("Terminal")
            }
        }
    }
}

@Composable
private fun LauncherButton(
    launcher: Launcher,
    busy: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier,
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (busy) {
                Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                }
            } else {
                AgentAvatar(launcher.kind, size = 32.dp)
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(launcher.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (launcher.kind in chatKinds) "Chat" else "Terminal",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
