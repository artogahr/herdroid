package dev.herdroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.herdroid.core.herdr.AgentStatus
import dev.herdroid.core.herdr.Pane
import dev.herdroid.core.model.AgentKind

@Composable
fun statusStyle(status: AgentStatus): Pair<String, Color> =
    when (status) {
        AgentStatus.BLOCKED -> "needs you" to Color(0xFFE5484D)
        AgentStatus.WORKING -> "working" to Color(0xFF3E8BFF)
        AgentStatus.DONE -> "done" to Color(0xFF30A46C)
        AgentStatus.IDLE -> "idle" to MaterialTheme.colorScheme.outline
        AgentStatus.UNKNOWN -> "unknown" to MaterialTheme.colorScheme.outline
    }

@Composable
fun StatusDot(
    status: AgentStatus?,
    size: Dp = 8.dp,
) {
    if (status == null) return
    Box(Modifier.size(size).clip(CircleShape).background(statusStyle(status).second))
}

@Composable
fun AgentAvatar(
    agent: String?,
    size: Dp = 40.dp,
) {
    val (bg, fg, letter) =
        when (agent) {
            "claude" -> {
                Triple(Color(0xFFD97757), Color.White, "C")
            }

            "codex" -> {
                Triple(Color(0xFF10A37F), Color.White, "X")
            }

            null -> {
                Triple(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurfaceVariant, ">_")
            }

            else -> {
                Triple(
                    MaterialTheme.colorScheme.tertiaryContainer,
                    MaterialTheme.colorScheme.onTertiaryContainer,
                    agent.take(1).uppercase(),
                )
            }
        }
    Box(Modifier.size(size).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Text(letter, color = fg, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.4f).sp)
    }
}

/** The task title herdr shows, without the trailing "| project" part. */
val Pane.displayTitle: String get() = title?.substringBefore(" | ")?.ifBlank { null } ?: name ?: agent ?: "Terminal"

/** Panes with a chat adapter open as a conversation; everything else opens as a terminal. */
val Pane.hasChat: Boolean
    get() = (agentSession?.agent ?: agent)?.let { a -> AgentKind.entries.any { it.name.equals(a, ignoreCase = true) } } == true
