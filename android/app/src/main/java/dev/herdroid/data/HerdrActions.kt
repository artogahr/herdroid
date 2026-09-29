package dev.herdroid.data

import dev.herdroid.core.herdr.HerdrApi
import dev.herdroid.core.herdr.HerdrApiException
import dev.herdroid.core.herdr.Launcher
import dev.herdroid.core.herdr.Launchers
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Changes the user makes to herdr's layout from the phone. */
class HerdrActions(
    private val api: HerdrApi,
) {
    /** Creates a space and returns its first pane, which starts as a shell in [folder]. */
    suspend fun createSpace(
        name: String,
        folder: String?,
    ): String? {
        val result =
            api.request(
                "workspace.create",
                buildJsonObject {
                    put("label", name)
                    folder?.takeIf { it.isNotBlank() }?.let { put("cwd", it) }
                    put("focus", false)
                },
            )
        return result["root_pane"]
            ?.jsonObject
            ?.get("pane_id")
            ?.jsonPrimitive
            ?.content
    }

    /** The agent CLIs herdr finds on the server. */
    suspend fun launchers(): List<Launcher> = Launchers.fromIntegrations(api.request("integration.list"))

    data class CreatedTab(
        val tabId: String,
        val paneId: String,
    )

    /** Opens a tab in the space, with a shell in [folder]. */
    suspend fun createTab(
        workspaceId: String,
        folder: String?,
    ): CreatedTab? {
        val result =
            api.request(
                "tab.create",
                buildJsonObject {
                    put("workspace_id", workspaceId)
                    folder?.takeIf { it.isNotBlank() }?.let { put("cwd", it) }
                    put("focus", false)
                },
            )
        val tabId =
            result["tab"]
                ?.jsonObject
                ?.get("tab_id")
                ?.jsonPrimitive
                ?.contentOrNull ?: return null
        val paneId =
            result["root_pane"]
                ?.jsonObject
                ?.get("pane_id")
                ?.jsonPrimitive
                ?.contentOrNull ?: return null
        return CreatedTab(tabId, paneId)
    }

    suspend fun closeTab(tabId: String) {
        api.request("tab.close", buildJsonObject { put("tab_id", tabId) })
    }

    /**
     * Starts an agent in a shell pane under a free name, and returns the name. herdr answers
     * once the agent is ready for input. The snapshot has no agent names, so they come from
     * `agent.list`; another client can still take a name in between, hence the retries.
     */
    suspend fun startAgent(
        paneId: String,
        kind: String,
    ): String {
        val taken =
            api
                .request("agent.list")["agents"]
                ?.jsonArray
                .orEmpty()
                .mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
                .toMutableSet()
        var attempts = 0
        while (true) {
            val name = Launchers.freeName(kind, taken)
            try {
                api.request(
                    "agent.start",
                    buildJsonObject {
                        put("name", name)
                        put("kind", kind)
                        put("pane_id", paneId)
                        put("timeout_ms", AGENT_START_TIMEOUT_MS)
                    },
                    timeoutMs = AGENT_START_TIMEOUT_MS + 10_000,
                )
                return name
            } catch (e: HerdrApiException) {
                if (e.code != "agent_name_taken" || ++attempts >= MAX_NAME_ATTEMPTS) throw e
                taken += name
            }
        }
    }

    suspend fun renameSpace(
        workspaceId: String,
        name: String,
    ) {
        api.request(
            "workspace.rename",
            buildJsonObject {
                put("workspace_id", workspaceId)
                put("label", name)
            },
        )
    }

    /** Sets the pane's display label, or clears it (back to the terminal title) when blank. */
    suspend fun renamePane(
        paneId: String,
        name: String,
    ) {
        api.request(
            "pane.rename",
            buildJsonObject {
                put("pane_id", paneId)
                if (name.isBlank()) put("label", kotlinx.serialization.json.JsonNull) else put("label", name.trim())
            },
        )
    }

    private companion object {
        const val AGENT_START_TIMEOUT_MS = 30_000L
        const val MAX_NAME_ATTEMPTS = 5
    }
}
