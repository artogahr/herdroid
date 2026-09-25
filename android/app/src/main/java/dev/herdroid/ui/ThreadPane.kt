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
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownTypography
import dev.herdroid.core.herdr.AgentStatus
import dev.herdroid.core.herdr.Pane
import dev.herdroid.core.model.Message
import dev.herdroid.core.model.MessageKind
import dev.herdroid.core.prompt.Prompt
import dev.herdroid.core.prompt.PromptOption
import dev.herdroid.core.thread.Outgoing
import dev.herdroid.core.thread.ThreadItem
import dev.herdroid.core.thread.ToolSummary
import dev.herdroid.core.thread.ToolVerb
import dev.herdroid.data.ConnectionState
import dev.herdroid.thread.ThreadController
import kotlinx.coroutines.delay

/** One agent conversation: messages, tool activity, and a floating composer. */
@Composable
fun ThreadPane(
    thread: ThreadController,
    onOpenTerminal: () -> Unit,
) {
    val pane = thread.pane

    Box(Modifier.fillMaxSize().imePadding()) {
        Column(Modifier.fillMaxSize()) {
            val prefs = uiPrefs()
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .pinchToScale({ prefs.chatTextScale }, prefs::updateChatTextScale),
            ) {
                ScaledText(prefs.chatTextScale) { Conversation(thread) }
                if (thread.awaitingFirstMessage && thread.items.isEmpty() && thread.outgoing.isEmpty()) {
                    Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        AgentAvatar(pane.agent, size = 56.dp)
                        Spacer(Modifier.size(12.dp))
                        Text("New ${pane.agent} session", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Send a message to start.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (thread.loading && thread.items.isEmpty()) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
                thread.loadError?.takeIf { thread.items.isEmpty() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    )
                }
            }
            val prompt = thread.prompt
            AnimatedVisibility(prompt != null || thread.status == AgentStatus.BLOCKED) {
                if (prompt != null) {
                    PromptCard(thread, prompt, onOpenTerminal)
                } else {
                    BlockedCard(thread.blockedPrompt, onOpenTerminal)
                }
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
private fun Conversation(thread: ThreadController) {
    val state = thread.listState
    // Newest at the bottom: a reversed list starts there and stays there as messages arrive.
    val rows = thread.items.asReversed()
    LazyColumn(
        state = state,
        reverseLayout = true,
        modifier = Modifier.fillMaxSize(),
        // Bottom: a short chat sits above the composer, like a messages app.
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.Bottom),
    ) {
        item(key = "bottom") { Spacer(Modifier.size(8.dp)) }
        if (thread.matchedByFolder) {
            item(key = "folder-note") {
                NoticeRow("herdr did not report this session; showing the latest one from this folder")
            }
        }
        if (thread.status == AgentStatus.WORKING) item(key = "working") { WorkingIndicator() }
        items(thread.outgoing.asReversed(), key = {
            "out-" + it.id
        }) { PendingBubble(it, onDismiss = { thread.dismiss(it.id) }, onResend = { thread.resend(it.id) }) }
        items(rows, key = { it.key }) { item ->
            when (item) {
                is ThreadItem.UserText -> UserBubble(item.text)
                is ThreadItem.AssistantText -> AssistantMessage(item.text)
                is ThreadItem.Activity -> ActivityRow(item)
                is ThreadItem.Notice -> NoticeRow(item.text)
            }
        }
        if (thread.hasOlder) {
            item(key = "older") {
                LaunchedEffect(Unit) { thread.loadOlder() }
                Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
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
                Text(
                    text,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 21.sp),
                )
            }
        }
    }
}

@Composable
private fun PendingBubble(
    out: Outgoing,
    onDismiss: () -> Unit,
    onResend: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(start = 56.dp, end = 12.dp, top = 10.dp), horizontalAlignment = Alignment.End) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp),
            modifier = Modifier.alpha(if (out.state == Outgoing.State.SENDING || out.state == Outgoing.State.SUBMITTED) 0.6f else 1f),
        ) {
            Text(
                out.text,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 21.sp),
            )
        }
        when (out.state) {
            Outgoing.State.SENDING -> {
                Caption("Sending…")
            }

            Outgoing.State.SUBMITTED -> {
                Caption("Sent")
            }

            Outgoing.State.UNCLEAR, Outgoing.State.FAILED -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Caption(
                        if (out.state == Outgoing.State.UNCLEAR) "The agent has not picked this up" else out.error ?: "Not sent",
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = onResend) { Text("Resend") }
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
        Markdown(
            text,
            typography = chatTypography(),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp),
        )
    }
}

/** Chat-sized Markdown. The library defaults headings to display styles (57sp and down). */
@Composable
private fun chatTypography(): MarkdownTypography {
    val t = MaterialTheme.typography
    val body = t.bodyLarge.copy(fontSize = 15.sp, lineHeight = 22.sp)
    val heading = t.titleMedium.copy(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
    return markdownTypography(
        h1 = t.titleLarge.copy(fontSize = 19.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
        h2 = heading.copy(fontSize = 17.sp),
        h3 = heading,
        h4 = heading.copy(fontSize = 15.sp),
        h5 = heading.copy(fontSize = 15.sp),
        h6 = heading.copy(fontSize = 15.sp),
        text = body,
        paragraph = body,
        ordered = body,
        bullet = body,
        list = body,
        code = t.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, lineHeight = 18.sp),
        inlineCode = body.copy(fontFamily = FontFamily.Monospace, fontSize = 13.5.sp),
        quote = body.copy(fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant),
        table = body.copy(fontSize = 13.sp),
        textLink =
            TextLinkStyles(
                style = SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline),
            ),
    )
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

/** A floating pill above the conversation, like modern chat apps. */
@Composable
private fun Composer(
    working: Boolean,
    enabled: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 10.dp),
    ) {
        Row(Modifier.padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
            TextField(
                value = text,
                onValueChange = { text = it },
                enabled = enabled,
                placeholder = {
                    Text(
                        if (working) "Queue a message…" else "Message",
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                    )
                },
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 21.sp),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp, max = 180.dp),
                colors =
                    TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
            )
            if (working && text.isBlank()) {
                FilledIconButton(
                    onClick = onStop,
                    modifier = Modifier.padding(bottom = 2.dp).size(44.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                ) { Icon(Icons.Filled.Stop, "Stop", tint = MaterialTheme.colorScheme.onSecondaryContainer) }
            } else {
                FilledIconButton(
                    onClick = {
                        onSend(text)
                        text = ""
                    },
                    enabled = enabled && text.isNotBlank(),
                    modifier = Modifier.padding(bottom = 2.dp).size(44.dp),
                ) { Icon(Icons.AutoMirrored.Filled.Send, "Send", Modifier.size(20.dp)) }
            }
        }
    }
}

/** A native answer card for the question the agent shows in its terminal. */
@Composable
private fun PromptCard(
    thread: ThreadController,
    prompt: Prompt,
    onOpenTerminal: () -> Unit,
) {
    var typing by remember(prompt.signature) { mutableStateOf<Int?>(if (prompt.textEntry && prompt.options.isEmpty()) -1 else null) }
    var text by remember(prompt.signature) { mutableStateOf("") }
    // A new question needs the whole card: put away the keyboard left over from the composer.
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(prompt.signature) {
        focus.clearFocus(force = true)
        keyboard?.hide()
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(24.dp),
        shadowElevation = 4.dp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Column(
            Modifier.padding(16.dp).heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AgentAvatar(thread.pane.agent, size = 24.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    "${thread.pane.agent?.replaceFirstChar { it.uppercase() } ?: "The agent"} is asking",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (prompt.question.isNotBlank()) QuestionText(prompt.question)
            prompt.options.forEachIndexed { i, option ->
                OptionButton(
                    option = option,
                    highlighted = i == prompt.selected,
                    enabled = !thread.answering,
                    onClick = {
                        if (option.wantsText) typing = if (typing == i) null else i else thread.answer(prompt, i)
                    },
                )
                if (typing == i) {
                    AnswerField(text, { text = it }, enabled = !thread.answering) { thread.answer(prompt, i, text) }
                }
            }
            if (typing == -1) {
                AnswerField(text, { text = it }, enabled = !thread.answering) { thread.answerText(text) }
            }
            thread.answerError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (thread.answering) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onOpenTerminal) { Text("Terminal") }
                TextButton(onClick = thread::cancelPrompt, enabled = !thread.answering) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun QuestionText(question: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        question.lines().filter { it.isNotBlank() }.forEach { line ->
            // Commands and paths read better in monospace; prose stays in the body font.
            val code = line.startsWith("$") || line.startsWith("cd ") || line.contains(" && ") || line.startsWith("/")
            if (code) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(8.dp)) {
                    Text(
                        line,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.5.sp,
                        lineHeight = 17.sp,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            } else {
                Text(line, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 21.sp))
            }
        }
    }
}

@Composable
private fun OptionButton(
    option: PromptOption,
    highlighted: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val container = if (highlighted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest
    Surface(
        color = container,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(enabled = enabled, onClick = onClick),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
            Text(
                option.label.removeSuffix("(esc)").trim(),
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                color = if (highlighted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            )
            option.description?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun AnswerField(
    text: String,
    onText: (String) -> Unit,
    enabled: Boolean,
    onSend: () -> Unit,
) {
    val requester = remember { FocusRequester() }
    val bring = remember { BringIntoViewRequester() }
    LaunchedEffect(Unit) {
        requester.requestFocus()
        delay(300)
        bring.bringIntoView()
    }
    Row(Modifier.bringIntoViewRequester(bring), verticalAlignment = Alignment.CenterVertically) {
        TextField(
            value = text,
            onValueChange = onText,
            enabled = enabled,
            placeholder = { Text("Your answer") },
            shape = RoundedCornerShape(16.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (text.isNotBlank()) onSend() }),
            colors =
                TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
            modifier = Modifier.weight(1f).focusRequester(requester),
        )
        Spacer(Modifier.width(8.dp))
        FilledIconButton(onClick = onSend, enabled = enabled && text.isNotBlank()) { Icon(Icons.AutoMirrored.Filled.Send, "Send answer") }
    }
}
