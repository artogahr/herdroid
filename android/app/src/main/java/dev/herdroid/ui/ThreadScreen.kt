package dev.herdroid.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.Markdown
import dev.herdroid.core.herdr.AgentStatus
import dev.herdroid.core.herdr.Pane
import dev.herdroid.core.model.Message
import dev.herdroid.core.model.MessageKind
import dev.herdroid.core.thread.ThreadItem
import dev.herdroid.core.thread.ToolSummary
import dev.herdroid.core.thread.ToolVerb
import dev.herdroid.data.ConnectionState
import dev.herdroid.thread.Outgoing
import dev.herdroid.thread.ThreadController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadScreen(
    connected: ConnectionState.Connected,
    pane: Pane,
    onTerminal: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val thread = remember(pane.id) { ThreadController(scope, connected, pane) }
    LaunchedEffect(thread) { thread.start() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                title = {
                    Column {
                        Text(
                            pane.title ?: pane.id,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        StatusLine(pane.agent ?: "shell", thread.status)
                    }
                },
                actions = {
                    IconButton(onClick = onTerminal) { Icon(Icons.Filled.Terminal, "Terminal") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .imePadding(),
        ) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Conversation(thread)
                if (thread.loading && thread.items.isEmpty()) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
                thread.loadError?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    )
                }
            }
            AnimatedVisibility(thread.status == AgentStatus.BLOCKED) {
                BlockedCard(thread.blockedPrompt, onTerminal)
            }
            Composer(
                working = thread.status == AgentStatus.WORKING,
                enabled = thread.kind != null,
                onSend = thread::send,
                onStop = thread::interrupt,
            )
        }
    }
}

@Composable
private fun StatusLine(
    agent: String,
    status: AgentStatus,
) {
    val (label, color) = statusStyle(status)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text("$agent · $label", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun statusStyle(status: AgentStatus): Pair<String, Color> =
    when (status) {
        AgentStatus.BLOCKED -> "needs your input" to Color(0xFFE5484D)
        AgentStatus.WORKING -> "working" to Color(0xFF3E8BFF)
        AgentStatus.DONE -> "done" to Color(0xFF30A46C)
        AgentStatus.IDLE -> "idle" to MaterialTheme.colorScheme.outline
        AgentStatus.UNKNOWN -> "unknown" to MaterialTheme.colorScheme.outline
    }

@Composable
private fun Conversation(thread: ThreadController) {
    val state = rememberLazyListState()
    // Newest at the bottom: a reversed list starts there and stays there as messages arrive.
    val rows = thread.items.asReversed()
    LazyColumn(
        state = state,
        reverseLayout = true,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item(key = "bottom") { Spacer(Modifier.size(8.dp)) }
        if (thread.status == AgentStatus.WORKING) item(key = "working") { WorkingIndicator() }
        items(thread.outgoing.asReversed(), key = { "out-" + it.id }) { PendingBubble(it, onDismiss = { thread.dismiss(it.id) }) }
        items(rows, key = { it.key }) { item ->
            when (item) {
                is ThreadItem.UserText -> UserBubble(item.text)
                is ThreadItem.AssistantText -> AssistantMessage(item.text)
                is ThreadItem.Activity -> ActivityRow(item)
                is ThreadItem.Notice -> NoticeRow(item.text)
            }
        }
        item(key = "top") { Spacer(Modifier.size(8.dp)) }
    }
}

@Composable
private fun UserBubble(text: String) {
    Box(Modifier.fillMaxWidth().padding(start = 56.dp, end = 12.dp, top = 10.dp, bottom = 2.dp), contentAlignment = Alignment.CenterEnd) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp),
        ) {
            SelectionContainer {
                Text(text, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun PendingBubble(
    out: Outgoing,
    onDismiss: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(start = 56.dp, end = 12.dp, top = 10.dp), horizontalAlignment = Alignment.End) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp),
            modifier = Modifier.alpha(if (out.state == Outgoing.State.FAILED) 1f else 0.6f),
        ) {
            Text(out.text, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyLarge)
        }
        when (out.state) {
            Outgoing.State.SENDING -> {
                Caption("Sending…")
            }

            Outgoing.State.SUBMITTED -> {
                Caption("Sent")
            }

            Outgoing.State.FAILED -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Caption(out.error ?: "Not sent", color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onDismiss) { Text("Dismiss") }
                }
            }
        }
    }
}

@Composable
private fun Caption(
    text: String,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = color, modifier = Modifier.padding(top = 3.dp, end = 4.dp))
}

@Composable
private fun AssistantMessage(text: String) {
    SelectionContainer {
        Markdown(text, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
    }
}

@Composable
private fun ActivityRow(item: ThreadItem.Activity) {
    var open by rememberSaveable(item.key) { mutableStateOf(false) }
    val tools = item.tools
    val summary = if (tools.isEmpty()) "Thought" else ToolSummary.summarize(tools)
    val icon = if (tools.isEmpty()) Icons.Filled.Psychology else verbIcon(ToolSummary.describe(tools.first()).verb)
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).animateContentSize()) {
        Row(
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable { open = !open }
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text(summary, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (item.failed > 0) {
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Filled.ErrorOutline, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
                Text(" ${item.failed} failed", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            }
            Icon(
                if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                null,
                Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.outline,
            )
        }
        if (open) {
            Column(
                Modifier.padding(start = 14.dp, bottom = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                item.steps.forEach { StepRow(it) }
            }
        }
    }
}

@Composable
private fun StepRow(step: Message) {
    var open by rememberSaveable(step.id) { mutableStateOf(false) }
    val tool = step.tool
    val (icon, title) =
        if (step.kind == MessageKind.THINKING || tool == null) {
            Icons.Filled.Psychology to "Thinking"
        } else {
            val d = ToolSummary.describe(tool)
            verbIcon(d.verb) to (d.detail ?: tool.name ?: "tool")
        }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { open = !open }
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon,
                null,
                Modifier.size(14.dp),
                tint =
                    if (tool?.isError ==
                        true
                    ) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
            )
            Spacer(Modifier.width(8.dp))
            Text(
                title,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = if (tool != null) FontFamily.Monospace else null,
                maxLines = if (open) Int.MAX_VALUE else 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (open) {
            val body = if (tool == null) step.text.orEmpty() else tool.output ?: "(no output yet)"
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.padding(top = 4.dp).fillMaxWidth(),
            ) {
                SelectionContainer {
                    Text(
                        body.take(8_000),
                        fontFamily = if (tool != null) FontFamily.Monospace else null,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier.heightIn(max = 360.dp).horizontalScroll(rememberScrollState()).padding(10.dp),
                    )
                }
            }
        }
    }
}

private fun verbIcon(verb: ToolVerb): ImageVector =
    when (verb) {
        ToolVerb.RAN -> Icons.Filled.Terminal
        ToolVerb.READ -> Icons.Filled.Description
        ToolVerb.EDITED, ToolVerb.WROTE -> Icons.Filled.Edit
        ToolVerb.SEARCHED -> Icons.Filled.Search
        else -> Icons.Filled.Build
    }

@Composable
private fun NoticeRow(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}

@Composable
private fun WorkingIndicator() {
    val t = rememberInfiniteTransition(label = "working")
    Row(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        repeat(3) { i ->
            val a by t.animateFloat(
                0.25f,
                1f,
                infiniteRepeatable(tween(600, delayMillis = i * 180), RepeatMode.Reverse),
                label = "dot$i",
            )
            Box(
                Modifier
                    .size(8.dp)
                    .alpha(a)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

@Composable
private fun BlockedCard(
    prompt: String?,
    onTerminal: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "The agent needs your answer",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            prompt?.let {
                Text(
                    it,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                )
            }
            Button(onClick = onTerminal) {
                Icon(Icons.Filled.Terminal, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Answer in terminal")
            }
        }
    }
}

@Composable
private fun Composer(
    working: Boolean,
    enabled: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    Surface(tonalElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            TextField(
                value = text,
                onValueChange = { text = it },
                enabled = enabled,
                placeholder = { Text(if (working) "Queue a message…" else "Message") },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp, max = 180.dp),
                shape = RoundedCornerShape(24.dp),
                colors =
                    TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
            )
            Spacer(Modifier.width(6.dp))
            if (working && text.isBlank()) {
                FilledIconButton(
                    onClick = onStop,
                    modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                ) { Icon(Icons.Filled.Stop, "Stop", tint = MaterialTheme.colorScheme.onSecondaryContainer) }
            } else {
                FilledIconButton(
                    onClick = {
                        onSend(text)
                        text = ""
                    },
                    enabled = enabled && text.isNotBlank(),
                    modifier = Modifier.size(48.dp),
                ) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
            }
        }
    }
}
