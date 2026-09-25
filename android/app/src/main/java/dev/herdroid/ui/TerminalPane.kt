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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var closedReason by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    var control by remember { mutableStateOf(false) }
    var confirmControl by remember { mutableStateOf(false) }
    var ctrl by remember { mutableStateOf(false) }
    val viewRef = remember { arrayOfNulls<TerminalView>(1) }
    val viewClient = remember { ViewClient(onTap = { viewRef[0]?.let { showKeyboard(context, it) } }) }
    val terminal =
        remember(pane.id, connected, attempt) {
            closedReason = null
            RemoteTerminal(
                scope,
                connected.transport,
                connected.herdrPath,
                pane.terminalId ?: pane.id,
                SessionClient { viewRef[0] },
                onClosed = { closedReason = it },
            )
        }
    DisposableEffect(terminal) { onDispose { terminal.close() } }

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
                    setTextSize((11 * ctx.resources.displayMetrics.scaledDensity).toInt())
                    isFocusable = true
                    isFocusableInTouchMode = true
                    attachSession(terminal.session)
                    viewRef[0] = this
                }
            },
            // A retry creates a new session; the view stays and is pointed at it.
            update = { it.attachSession(terminal.session) },
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

private fun showKeyboard(
    context: Context,
    view: TerminalView,
) {
    view.requestFocus()
    (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(view, 0)
}
