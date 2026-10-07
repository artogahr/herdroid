package dev.herdroid.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
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
import dev.herdroid.data.Discovery
import dev.herdroid.data.FoundServer
import dev.herdroid.data.SavedHost
import kotlinx.coroutines.flow.Flow

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
            item { DeviceKeyCard(connection.authorizedKeysLine, connection.keyStorage) }
        }
    }

    editing?.let { host ->
        val isNew = hosts.none { it.id == host.id }
        HostEditor(
            initial = if (isNew && host.user.isEmpty()) host.copy(user = hosts.firstOrNull()?.user.orEmpty()) else host,
            isNew = isNew,
            discover = { Discovery(connection.context).scan(connection.tailnetPeers) },
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
    discover: () -> Flow<FoundServer>,
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
            Column(Modifier.imePadding().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                if (isNew) {
                    NearbyServers(discover) { found ->
                        host = found.host
                        if (name.isBlank()) found.name?.let { name = it }
                    }
                } else {
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
private fun DeviceKeyCard(
    pubkey: String,
    storage: String,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    ElevatedCard(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Key, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text("This phone's public key", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Add it to ~/.ssh/authorized_keys on each server.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "The private key never leaves the phone and cannot be exported. It is stored $storage.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
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

/** SSH servers found on Tailscale, Bonjour and the Wi-Fi subnet; tapping one fills the form. */
@Composable
private fun NearbyServers(
    discover: () -> Flow<FoundServer>,
    onPick: (FoundServer) -> Unit,
) {
    val found = remember { mutableStateListOf<FoundServer>() }
    var scanning by remember { mutableStateOf(true) }
    var round by remember { mutableIntStateOf(0) }
    LaunchedEffect(round) {
        found.clear()
        scanning = true
        runCatching { discover().collect { f -> found += f } }
            .onFailure { if (it !is kotlinx.coroutines.CancellationException) android.util.Log.w("Discovery", "scan failed", it) }
        scanning = false
    }
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Nearby servers", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (scanning) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                TextButton(onClick = { round++ }) { Text("Scan again") }
            }
        }
        if (!scanning && found.isEmpty()) {
            Text(
                "None found. Servers need SSH on port 22; Tailscale devices appear after connecting to any server once.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        found.sortedBy { it.source.ordinal }.forEach { f ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().clickable { onPick(f) },
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(f.name ?: f.host, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        listOfNotNull(f.host.takeIf { f.name != null }, f.source.label, f.software).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
