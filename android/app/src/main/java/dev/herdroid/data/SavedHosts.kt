package dev.herdroid.data

import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A server the user connects to. Host key pins and herdr paths are stored per host elsewhere. */
data class SavedHost(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val host: String = "",
    val port: Int = 22,
    val user: String = "",
    val lastUsed: Long = 0,
) {
    val complete get() = host.isNotEmpty() && user.isNotEmpty()
    val label get() = name.ifBlank { host }
    val address get() = "$user@$host" + if (port == 22) "" else ":$port"
}

/** Saved servers in shared preferences, as a small JSON list. */
class HostStore(
    private val prefs: SharedPreferences,
) {
    fun all(): List<SavedHost> {
        migrate()
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrElse { return emptyList() }
        return (0 until array.length())
            .map { i ->
                val o = array.getJSONObject(i)
                SavedHost(
                    id = o.getString("id"),
                    name = o.optString("name"),
                    host = o.optString("host"),
                    port = o.optInt("port", 22),
                    user = o.optString("user"),
                    lastUsed = o.optLong("lastUsed"),
                )
            }.sortedByDescending { it.lastUsed }
    }

    fun save(host: SavedHost) = write(all().filterNot { it.id == host.id } + host)

    fun delete(id: String) = write(all().filterNot { it.id == id })

    fun lastUsed(): SavedHost? = all().maxByOrNull { it.lastUsed }?.takeIf { it.lastUsed > 0 }

    private fun write(hosts: List<SavedHost>) {
        val array = JSONArray()
        hosts.forEach { h ->
            array.put(
                JSONObject()
                    .put("id", h.id)
                    .put("name", h.name)
                    .put("host", h.host)
                    .put("port", h.port)
                    .put("user", h.user)
                    .put("lastUsed", h.lastUsed),
            )
        }
        prefs.edit { putString(KEY, array.toString()) }
    }

    /** The first version kept one server as loose keys; turn it into a saved server once. */
    private fun migrate() {
        if (prefs.contains(KEY)) return
        val host = prefs.getString("host", null).orEmpty()
        val user = prefs.getString("user", null).orEmpty()
        val hosts =
            if (host.isNotEmpty() && user.isNotEmpty()) {
                listOf(SavedHost(host = host, port = prefs.getInt("port", 22), user = user, lastUsed = System.currentTimeMillis()))
            } else {
                emptyList()
            }
        write(hosts)
    }

    private companion object {
        const val KEY = "savedHosts"
    }
}
