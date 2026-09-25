package dev.herdroid.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import dev.herdroid.core.herdr.Pane
import dev.herdroid.data.Connection
import dev.herdroid.data.ConnectionState

sealed interface Screen {
    data object Agents : Screen

    data class Thread(
        val pane: Pane,
    ) : Screen

    data class Terminal(
        val pane: Pane,
    ) : Screen
}

@Composable
fun App(connection: Connection) {
    val state by connection.state.collectAsState()
    val stack = remember { mutableStateListOf<Screen>(Screen.Agents) }
    val connected = state as? ConnectionState.Connected
    if (connected == null) {
        ConnectScreen(connection, state)
        return
    }
    BackHandler(enabled = stack.size > 1) { stack.removeAt(stack.lastIndex) }
    when (val screen = stack.last()) {
        Screen.Agents -> {
            AgentsScreen(
                connected,
                onOpen = { pane -> stack.add(if (pane.agentSession != null) Screen.Thread(pane) else Screen.Terminal(pane)) },
                onDisconnect = { connection.disconnect() },
            )
        }

        is Screen.Thread -> {
            ThreadScreen(
                connected,
                screen.pane,
                onTerminal = { stack.add(Screen.Terminal(screen.pane)) },
                onBack = { stack.removeAt(stack.lastIndex) },
            )
        }

        is Screen.Terminal -> {
            TerminalScreen(connected, screen.pane, onBack = { stack.removeAt(stack.lastIndex) })
        }
    }
}
