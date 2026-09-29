package dev.herdroid.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit

/** Small preferences the app picks up from use; remembered across launches. */
class UiPrefs(
    context: Context,
) {
    private val prefs = context.getSharedPreferences("ui", Context.MODE_PRIVATE)

    /** Multiplier for chat text, changed by pinching the conversation. */
    var chatTextScale by mutableFloatStateOf(prefs.getFloat("chatTextScale", 1f))
        private set

    /** Terminal font size in sp, changed by pinching the terminal. */
    var terminalTextSize by mutableIntStateOf(prefs.getInt("terminalTextSize", 11))
        private set

    /** The agent kind last started from the phone, offered first next time. */
    var lastAgentKind by mutableStateOf(prefs.getString("lastAgentKind", null))
        private set

    fun updateLastAgentKind(kind: String) {
        lastAgentKind = kind
        prefs.edit { putString("lastAgentKind", kind) }
    }

    fun updateChatTextScale(scale: Float) {
        chatTextScale = scale.coerceIn(MIN_CHAT_SCALE, MAX_CHAT_SCALE)
        prefs.edit { putFloat("chatTextScale", chatTextScale) }
    }

    fun updateTerminalTextSize(size: Int) {
        terminalTextSize = size.coerceIn(6, 28)
        prefs.edit { putInt("terminalTextSize", terminalTextSize) }
    }

    companion object {
        const val MIN_CHAT_SCALE = 0.7f
        const val MAX_CHAT_SCALE = 2f
    }
}
