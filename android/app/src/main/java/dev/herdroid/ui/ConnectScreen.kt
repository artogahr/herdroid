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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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

@Composable
fun ConnectScreen(
    connection: Connection,
    state: ConnectionState,
) {
    val initial = remember { connection.config }
    var host by remember { mutableStateOf(initial.host) }
    var user by remember { mutableStateOf(initial.user) }
    var port by remember { mutableStateOf(initial.port.toString()) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val pubkey =
        remember {
            connection.authorizedKeysLine.also {
                // Lets adb read the key during development; release builds stay quiet.
                if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                    android.util.Log.i("Herdroid", "public key: $it")
                }
            }
        }

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
                    connection.connectInBackground()
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

                is ConnectionState.HostKeyCheck -> {
                    HostKeyDialog(connection, state)
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

@Composable
private fun HostKeyDialog(
    connection: Connection,
    check: ConnectionState.HostKeyCheck,
) {
    val changed = check.previous != null
    AlertDialog(
        onDismissRequest = { connection.disconnect() },
        title = { Text(if (changed) "Host key changed" else "Trust this host?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (changed) {
                        "The host now presents a different key. This happens after reinstalling the machine, " +
                            "but it can also mean someone is intercepting the connection."
                    } else {
                        "Compare this with the host's key: run `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub` on it."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                check.previous?.let { Text("Was: $it", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                Text(
                    (if (changed) "Now: " else "") + check.fingerprint,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { connection.trustHostKey(check.fingerprint) }) {
                Text(if (changed) "Trust new key" else "Trust")
            }
        },
        dismissButton = { TextButton(onClick = { connection.disconnect() }) { Text("Cancel") } },
    )
}
