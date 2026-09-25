package dev.herdroid.core.herdr

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class AgentStatus {
    @SerialName("idle")
    IDLE,

    @SerialName("working")
    WORKING,

    @SerialName("blocked")
    BLOCKED,

    @SerialName("done")
    DONE,

    @SerialName("unknown")
    UNKNOWN,
}

@Serializable
data class AgentSession(
    val agent: String,
    val kind: String,
    val value: String,
)

@Serializable
data class Workspace(
    @SerialName("workspace_id") val id: String,
    val label: String,
    val number: Int,
    @SerialName("active_tab_id") val activeTabId: String? = null,
    @SerialName("agent_status") val agentStatus: AgentStatus? = null,
    val focused: Boolean = false,
)

@Serializable
data class Tab(
    @SerialName("tab_id") val id: String,
    @SerialName("workspace_id") val workspaceId: String,
    val label: String,
    val number: Int,
    @SerialName("agent_status") val agentStatus: AgentStatus? = null,
    val focused: Boolean = false,
)

@Serializable
data class Pane(
    @SerialName("pane_id") val id: String,
    @SerialName("tab_id") val tabId: String,
    @SerialName("workspace_id") val workspaceId: String,
    val agent: String? = null,
    val name: String? = null,
    @SerialName("agent_session") val agentSession: AgentSession? = null,
    @SerialName("agent_status") val agentStatus: AgentStatus? = null,
    val cwd: String? = null,
    @SerialName("foreground_cwd") val foregroundCwd: String? = null,
    @SerialName("terminal_title_stripped") val title: String? = null,
    @SerialName("terminal_id") val terminalId: String? = null,
    val focused: Boolean = false,
    val revision: Long = 0,
)

@Serializable
data class Rect(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

@Serializable
data class LayoutPane(
    @SerialName("pane_id") val paneId: String,
    val rect: Rect,
)

@Serializable
data class Layout(
    @SerialName("tab_id") val tabId: String,
    val panes: List<LayoutPane>,
) {
    /** Panes in reading order (top-to-bottom rows, then left-to-right), for swipe navigation. */
    fun paneOrder(): List<String> = panes.sortedWith(compareBy({ it.rect.y }, { it.rect.x })).map { it.paneId }
}

@Serializable
data class Snapshot(
    val version: String,
    val protocol: Int,
    val workspaces: List<Workspace>,
    val tabs: List<Tab>,
    val panes: List<Pane>,
    val layouts: List<Layout> = emptyList(),
    @SerialName("focused_workspace_id") val focusedWorkspaceId: String? = null,
    @SerialName("focused_tab_id") val focusedTabId: String? = null,
    @SerialName("focused_pane_id") val focusedPaneId: String? = null,
)
