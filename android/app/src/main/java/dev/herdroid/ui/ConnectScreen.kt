package dev.herdroid.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.herdroid.data.Connection
import dev.herdroid.data.ConnectionState
import dev.herdroid.data.HostConfig
import kotlinx.coroutines.launch

@Composable
fun ConnectScreen(
    connection: Connection,
    state: ConnectionState,
) {
    val initial = remember { connection.config }
    var host by remember { mutableStateOf(initial.host) }
    var user by remember { mutableStateOf(initial.user) }
    var port by remember { mutableStateOf(initial.port.toString()) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val pubkey = remember { connection.authorizedKeysLine }

    Scaffold { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Connect to herdr", style = MaterialTheme.typography.headlineMedium)
            OutlinedTextField(host, {
                host = it.trim()
            }, label = { Text("Host (Tailscale name or IP)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(user, { user = it.trim() }, label = { Text("User") }, singleLine = true, modifier = Modifier.weight(2f))
                OutlinedTextField(
                    port,
                    { port = it.filter(Char::isDigit) },
                    label = { Text("Port") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }
            Button(
                enabled = host.isNotEmpty() && user.isNotEmpty() && state !is ConnectionState.Connecting,
                onClick = {
                    connection.config = HostConfig(host, port.toIntOrNull() ?: 22, user)
                    scope.launch { connection.connect() }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Connect") }
            when (state) {
                ConnectionState.Connecting -> {
                    CircularProgressIndicator()
                }

                is ConnectionState.Failed -> {
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                }

                else -> {}
            }
            connection.pinnedHostKey?.let {
                Text("Pinned host key: $it", style = MaterialTheme.typography.bodySmall)
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("This phone's public key", style = MaterialTheme.typography.titleMedium)
                    Text("Add it to ~/.ssh/authorized_keys on the host.", style = MaterialTheme.typography.bodyMedium)
                    SelectionContainer {
                        Text(pubkey, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { clipboard.setText(AnnotatedString(pubkey)) }) { Text("Copy") }
                        OutlinedButton(onClick = {
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, pubkey)
                            context.startActivity(Intent.createChooser(send, null))
                        }) { Text("Share") }
                    }
                }
            }
        }
    }
}
