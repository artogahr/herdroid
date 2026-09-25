package dev.herdroid.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.herdroid.data.Connection
import dev.herdroid.data.ConnectionState
import dev.herdroid.data.session

@Composable
fun App(connection: Connection) {
    val state by connection.state.collectAsState()
    val session = state.session
    if (session == null) {
        ConnectScreen(connection, state)
    } else {
        MainScreen(connection, session, state as? ConnectionState.Reconnecting)
    }
}
