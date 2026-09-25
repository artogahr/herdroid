package dev.herdroid.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.herdroid.data.Connection
import dev.herdroid.data.ConnectionState
import dev.herdroid.data.SavedHost

/** Startup screen: saved servers, the one being connected, and this phone's key. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServersScreen(
    connection: Connection,
    state: ConnectionState,
) {
    val hosts by connection.hosts.collectAsState()
    var editing by remember { mutableStateOf<SavedHost?>(null) }
    val connectingId = if (state == ConnectionState.Connecting) connection.config.id else null
    val failed = state as? ConnectionState.Failed

    Scaffold(
        topBar = { LargeTopAppBar(title = { Text("Servers") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editing = SavedHost() },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("Add server") },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding =
                androidx.compose.foundation.layout
                    .PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (hosts.isEmpty()) {
                item {
                    Text(
                        "Add the machine that runs herdr. Herdroid connects over SSH, for example through Tailscale.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
            items(hosts, key = { it.id }) { host ->
                ServerCard(
                    host = host,
                    connecting = host.id == connectingId,
                    error = failed?.takeIf { it.hostId == host.id }?.message,
                    onConnect = { connection.connectTo(host) },
                    onEdit = { editing = host },
                )
            }
            item { DeviceKeyCard(connection.authorizedKeysLine) }
        }
    }

    editing?.let { host ->
        HostEditor(
            initial = host,
            isNew = hosts.none { it.id == host.id },
            onDismiss = { editing = null },
            onSave = { saved, connect ->
                connection.saveHost(saved)
                editing = null
                if (connect) connection.connectTo(saved)
            },
            onDelete = {
                connection.deleteHost(host.id)
                editing = null
            },
        )
    }
    if (state is ConnectionState.HostKeyCheck) HostKeyDialog(connection, state)
}

@Composable
private fun ServerCard(
    host: SavedHost,
    connecting: Boolean,
    error: String?,
    onConnect: () -> Unit,
    onEdit: () -> Unit,
) {
    ElevatedCard(Modifier.fillMaxWidth().clickable(enabled = !connecting, onClick = onConnect)) {
        Row(Modifier.padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Computer, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(host.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(host.address, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                when {
                    connecting -> {
                        Text(
                            "Connecting…",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    error != null -> {
                        Text(
                            error,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 3,
                        )
                    }
                }
            }
            if (connecting) {
                CircularProgressIndicator(Modifier.padding(12.dp).size(20.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, "Edit ${host.label}") }
            }
        }
    }
}

@Composable
private fun HostEditor(
    initial: SavedHost,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (SavedHost, Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var host by remember { mutableStateOf(initial.host) }
    var user by remember { mutableStateOf(initial.user) }
    var port by remember { mutableStateOf(initial.port.toString()) }
    val edited = initial.copy(name = name.trim(), host = host.trim(), user = user.trim(), port = port.toIntOrNull() ?: 22)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Add server" else "Edit server") },
        text = {
            Column(Modifier.imePadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    name,
                    { name = it },
                    label = { Text("Name (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(host, {
                    host = it
                }, label = { Text("Host (Tailscale name or IP)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(user, { user = it }, label = { Text("User") }, singleLine = true, modifier = Modifier.weight(2f))
                    OutlinedTextField(
                        port,
                        { port = it.filter(Char::isDigit) },
                        label = { Text("Port") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
                if (!isNew) {
                    TextButton(onClick = onDelete) { Text("Delete server", color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = edited.complete, onClick = { onSave(edited, true) }) { Text("Save and connect") }
        },
        dismissButton = {
            TextButton(enabled = edited.complete, onClick = { onSave(edited, false) }) { Text("Save") }
        },
    )
}

@Composable
private fun DeviceKeyCard(pubkey: String) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    ElevatedCard(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Key, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text("This phone's key", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Add it to ~/.ssh/authorized_keys on each server.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (open) {
                SelectionContainer { Text(pubkey, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(pubkey)) }) { Text("Copy") }
                OutlinedButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, pubkey)
                    context.startActivity(Intent.createChooser(send, null))
                }) { Text("Share") }
                TextButton(onClick = { open = !open }) { Text(if (open) "Hide" else "Show") }
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
