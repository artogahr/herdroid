package dev.herdroid.core.herdr

import dev.herdroid.core.transport.HostTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicLong

class HerdrApiException(
    val code: String,
    message: String,
) : Exception("$code: $message")

/**
 * herdr socket API over `herdr remote-api-bridge`. The server answers one request per
 * connection, so each call runs its own bridge process on its own SSH channel.
 */
class HerdrApi(
    private val transport: HostTransport,
    private val herdrPath: String,
) {
    private val ids = AtomicLong()
    private val bridge get() = "${shellQuote(herdrPath)} remote-api-bridge"

    suspend fun request(
        method: String,
        params: JsonObject = JsonObject(emptyMap()),
    ): JsonObject {
        val id = "h${ids.incrementAndGet()}"
        transport.exec(bridge).use { channel ->
            channel.write(requestLine(id, method, params))
            val response = json.parseToJsonElement(channel.lines.first()).jsonObject
            return unwrap(response)
        }
    }

    suspend fun snapshot(): Snapshot = json.decodeFromJsonElement(request("session.snapshot").getValue("snapshot"))

    /**
     * Opens one `events.subscribe` stream. herdr cannot change a subscription after it
     * starts, so callers open a new stream when the set of watched panes changes.
     */
    fun subscribe(subscriptions: List<JsonObject>): Flow<JsonObject> =
        flow {
            val id = "s${ids.incrementAndGet()}"
            transport.exec(bridge).use { channel ->
                val params = buildJsonObject { put("subscriptions", JsonArray(subscriptions)) }
                channel.write(requestLine(id, "events.subscribe", params))
                var started = false
                channel.lines.collect { line ->
                    val obj = json.parseToJsonElement(line).jsonObject
                    if (!started) {
                        unwrap(obj)
                        started = true
                    } else {
                        emit(obj)
                    }
                }
            }
        }

    private fun requestLine(
        id: String,
        method: String,
        params: JsonObject,
    ): String =
        buildJsonObject {
            put("id", id)
            put("method", method)
            put("params", params)
        }.toString() + "\n"

    private fun unwrap(response: JsonObject): JsonObject {
        response["error"]?.jsonObject?.let { error ->
            throw HerdrApiException(
                error["code"]?.jsonPrimitive?.content ?: "unknown",
                error["message"]?.jsonPrimitive?.content ?: "",
            )
        }
        return response["result"]?.jsonObject ?: throw HerdrApiException("bad_response", response.toString())
    }

    companion object {
        val json = Json { ignoreUnknownKeys = true }

        /**
         * Non-interactive SSH sessions often lack the user's PATH (for example
         * ~/.nix-profile/bin), so ask a login shell once and remember the result.
         */
        suspend fun locateHerdr(transport: HostTransport): String =
            transport
                .run("\"\${SHELL:-/bin/sh}\" -lc 'command -v herdr'")
                .lineSequence()
                .map { it.trim() }
                .lastOrNull { it.startsWith("/") }
                ?: throw HerdrApiException("herdr_not_found", "herdr is not on the login shell PATH")

        suspend fun checkBridge(
            transport: HostTransport,
            herdrPath: String,
        ): Boolean = transport.run("${shellQuote(herdrPath)} remote-api-bridge --check").trim() == "herdr-api-bridge-v1"
    }
}

fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
