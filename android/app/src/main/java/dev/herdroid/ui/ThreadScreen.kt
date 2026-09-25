package dev.herdroid.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.herdroid.core.herdr.Pane
import dev.herdroid.core.model.AgentKind
import dev.herdroid.core.model.Message
import dev.herdroid.core.model.MessageKind
import dev.herdroid.core.model.Role
import dev.herdroid.core.transcript.TranscriptSource
import dev.herdroid.data.ConnectionState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadScreen(
    connected: ConnectionState.Connected,
    pane: Pane,
    onTerminal: () -> Unit,
    onBack: () -> Unit,
) {
    val messages = remember(pane.id) { mutableStateListOf<Message>() }
    var status by remember { mutableStateOf("Loading transcript…") }
    var unknown by remember { mutableStateOf(0) }
    val listState = rememberLazyListState()

    LaunchedEffect(pane.id) {
        val session = pane.agentSession ?: return@LaunchedEffect
        val kind = AgentKind.entries.firstOrNull { it.name.equals(session.agent, ignoreCase = true) }
        if (kind == null) {
            status = "No chat view for ${session.agent}; use the terminal."
            return@LaunchedEffect
        }
        val located = runCatching { connected.transcripts.locate(kind, session.value, pane.cwd) }
        val path = located.getOrNull()
        if (path == null) {
            status = located.exceptionOrNull()?.let { "Could not look up the transcript: ${it.message}" } ?: "Transcript not found."
            return@LaunchedEffect
        }
        status = ""
        val parser = TranscriptSource.parserFor(kind)
        val index = HashMap<String, Int>()
        runCatching {
            connected.transcripts.followRecent(path).collect { line ->
                for (m in parser.feed(line.text)) {
                    if (m.meta) continue
                    if (m.kind == MessageKind.UNKNOWN) unknown++
                    val at = index[m.id]
                    if (at == null) {
                        index[m.id] = messages.size
                        messages.add(m)
                    } else {
                        messages[at] = m
                    }
                }
            }
        }.onFailure { status = "Stream ended: ${it.message}" }
    }

    // Follow new output while the user is at the bottom.
    LaunchedEffect(messages.size) {
        val last =
            listState.layoutInfo.visibleItemsInfo
                .lastOrNull()
                ?.index ?: 0
        if (messages.isNotEmpty() && last >= messages.size - 3) listState.scrollToItem(messages.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(pane.title ?: pane.id, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = { TextButton(onClick = onTerminal) { Text("Terminal") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (status.isNotEmpty()) Text(status, modifier = Modifier.padding(16.dp))
            if (unknown > 0) {
                Text(
                    "History may be incomplete: $unknown records were not understood.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(messages, key = { it.id }) { MessageRow(it) }
            }
        }
    }
}

@Composable
private fun MessageRow(m: Message) {
    when (m.kind) {
        MessageKind.TEXT -> {
            if (m.role == Role.USER) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp), contentAlignment = Alignment.CenterEnd) {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(16.dp)) {
                        Text(m.text.orEmpty(), modifier = Modifier.padding(12.dp))
                    }
                }
            } else {
                Text(m.text.orEmpty(), modifier = Modifier.padding(horizontal = 16.dp))
            }
        }

        MessageKind.THINKING -> {
            Collapsible("Thinking", m.text.orEmpty())
        }

        MessageKind.TOOL_CALL -> {
            val tool = m.tool
            val title = (tool?.name ?: "tool") + if (tool?.isError == true) " · failed" else ""
            Collapsible(title, listOfNotNull(tool?.input?.toString(), tool?.output).joinToString("\n\n"), monospace = true)
        }

        MessageKind.TOOL_RESULT -> {
            Collapsible("Result", m.text.orEmpty(), monospace = true)
        }

        MessageKind.COMPACTION, MessageKind.STATUS -> {
            Text(
                m.text ?: m.kind.name.lowercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        MessageKind.UNKNOWN -> {
            Collapsible("Unrecognized record", m.raw.orEmpty(), monospace = true)
        }
    }
}

@Composable
private fun Collapsible(
    title: String,
    body: String,
    monospace: Boolean = false,
) {
    var open by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { open = !open }
            .animateContentSize()
            .padding(horizontal = 16.dp, vertical = 2.dp),
    ) {
        Text(
            (if (open) "▾ " else "▸ ") + title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
        )
        if (open) {
            Text(
                body.take(20_000),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = if (monospace) FontFamily.Monospace else null,
            )
        }
    }
}
