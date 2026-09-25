package dev.herdroid.data

import dev.herdroid.core.herdr.HerdrApi
import kotlinx.serialization.json.buildJsonObject
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
}
