package dev.herdroid.ui

import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.view.TerminalView
import dev.herdroid.core.herdr.Pane
import dev.herdroid.data.ConnectionState
import dev.herdroid.terminal.RemoteTerminal
import dev.herdroid.terminal.SessionClient
import dev.herdroid.terminal.ViewClient

private val extraKeys =
    listOf(
        "esc" to "\u001b",
        "tab" to "\t",
        "↑" to "\u001b[A",
        "↓" to "\u001b[B",
        "←" to "\u001b[D",
        "→" to "\u001b[C",
        "enter" to "\r",
    )

@Composable
fun TerminalPane(
    connected: ConnectionState.Connected,
    pane: Pane,
    swiping: Boolean = false,
    /** Start typing right away, for a tab the phone just opened: nobody else is using it. */
    startInControl: Boolean = false,
    onControlChange: (Boolean) -> Unit = {},
    /** Opens the agent picker for this pane; null when an agent already runs in it. */
    onStartAgent: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var closedReason by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    var control by remember { mutableStateOf(false) }
    var confirmControl by remember { mutableStateOf(false) }
    var ctrl by remember { mutableStateOf(false) }
    val viewRef = remember { arrayOfNulls<TerminalView>(1) }
    // Typing only makes sense in control mode; an observing terminal never takes focus.
    val controlState = rememberUpdatedState(control)
    val prefs = uiPrefs()
    val viewClient =
        remember {
            ViewClient(
                onTap = { if (controlState.value) viewRef[0]?.let { showKeyboard(context, it) } },
                onFontStep = { step ->
                    prefs.updateTerminalTextSize(prefs.terminalTextSize + step)
                    viewRef[0]?.setTextSize((prefs.terminalTextSize * context.resources.displayMetrics.scaledDensity).toInt())
                },
            )
        }
    val terminal =
        remember(pane.id, connected, attempt) {
            RemoteTerminal(
                scope,
                connected.transport,
                connected.herdrPath,
                pane.terminalId ?: pane.id,
                SessionClient { viewRef[0] },
                onClosed = { reason ->
                    closedReason = reason
                    // The shell exited: let go of the keyboard now, before the pane is removed
                    // and Android hands focus (and the keyboard) to the next input field.
                    viewRef[0]?.let { dropFocus(context, it) }
                },
            )
        }
    // Removing a focused Android view makes Android search for new focus, which re-enters
    // Compose and re-lays out the pager mid-removal (the pager crash). Let go first.
    LaunchedEffect(swiping) {
        if (swiping) viewRef[0]?.let { dropFocus(context, it) }
    }
    val controlChange by rememberUpdatedState(onControlChange)
    LaunchedEffect(control) { controlChange(control) }
    DisposableEffect(Unit) { onDispose { controlChange(false) } }
    // The flag can arrive after the first composition: herdr's event for the new pane may
    // come before the call that created it returns.
    LaunchedEffect(terminal, startInControl) {
        if (startInControl && !control) {
            control = true
            terminal.setControl(true)
        }
    }
    DisposableEffect(terminal) {
        closedReason = null
        onDispose {
            // When the pane goes away (the shell exited), put the keyboard away with it
            // instead of leaving it attached to whatever takes focus next.
            viewRef[0]?.let { dropFocus(context, it) }
            terminal.close()
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                closedReason ?: if (control) "Typing goes to the pane" else "Watching. Take control to type.",
                style = MaterialTheme.typography.labelMedium,
                color = if (closedReason != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (closedReason != null) {
                TextButton(onClick = { attempt++ }) { Text("Retry") }
            }
            if (onStartAgent != null && closedReason == null) {
                AssistChip(
                    onClick = {
                        // The chat replaces this view once the agent starts; a focused view
                        // must not be removed (see dropFocus).
                        viewRef[0]?.let { dropFocus(context, it) }
                        onStartAgent()
                    },
                    label = { Text("Agent") },
                    leadingIcon = { Icon(Icons.Filled.Add, null, Modifier.size(AssistChipDefaults.IconSize)) },
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            FilterChip(
                selected = control,
                onClick = {
                    if (control) {
                        control = false
                        terminal.setControl(false)
                    } else {
                        confirmControl = true
                    }
                },
                label = { Text(if (control) "Controlling" else "Take control") },
            )
        }
        AndroidView(
            factory = { ctx ->
                TerminalView(ctx, null).apply {
                    setTerminalViewClient(viewClient)
                    // The renderer leaves the default background unpainted, and agents draw
                    // for a dark terminal: without this, default white text vanishes on the
                    // light theme.
                    setBackgroundColor(android.graphics.Color.BLACK)
                    setTextSize((prefs.terminalTextSize * ctx.resources.displayMetrics.scaledDensity).toInt())
                    isFocusable = false
                    isFocusableInTouchMode = false
                    attachSession(terminal.session)
                    viewRef[0] = this
                }
            },
            // A retry creates a new session; the view stays and is pointed at it.
            update = { view ->
                view.attachSession(terminal.session)
                view.isFocusable = control
                view.isFocusableInTouchMode = control
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        if (control) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(4.dp),
            ) {
                FilterChip(
                    selected = ctrl,
                    onClick = {
                        ctrl = !ctrl
                        viewClient.ctrlLatched = ctrl
                    },
                    label = { Text("ctrl") },
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
                for ((label, seq) in extraKeys) {
                    TextButton(onClick = { terminal.sendText(seq) }) { Text(label) }
                }
            }
        }
    }

    if (confirmControl) {
        AlertDialog(
            onDismissRequest = { confirmControl = false },
            title = { Text("Take control?") },
            text = { Text("Typing goes to the real pane, and the pane resizes to this screen for every client, including your desktop.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmControl = false
                    control = true
                    terminal.setControl(true)
                }) { Text("Take control") }
            },
            dismissButton = { TextButton(onClick = { confirmControl = false }) { Text("Cancel") } },
        )
    }
}

private fun dropFocus(
    context: Context,
    view: TerminalView,
) {
    (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(view.windowToken, 0)
    view.clearFocus()
}

private fun showKeyboard(
    context: Context,
    view: TerminalView,
) {
    view.requestFocus()
    (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(view, 0)
}
