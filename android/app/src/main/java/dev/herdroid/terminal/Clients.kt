package dev.herdroid.terminal

import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

/** Termux callbacks we do not need, with logging routed to logcat. */
open class SessionClient(
    private val view: () -> TerminalView?,
) : TerminalSessionClient {
    override fun onTextChanged(changedSession: TerminalSession) {
        view()?.onScreenUpdated()
    }

    override fun onTitleChanged(changedSession: TerminalSession) {}

    override fun onSessionFinished(finishedSession: TerminalSession) {}

    override fun onCopyTextToClipboard(
        session: TerminalSession,
        text: String?,
    ) {}

    override fun onPasteTextFromClipboard(session: TerminalSession?) {}

    override fun onBell(session: TerminalSession) {}

    override fun onColorsChanged(session: TerminalSession) {}

    override fun onTerminalCursorStateChange(state: Boolean) {}

    override fun getTerminalCursorStyle(): Int? = null

    override fun logError(
        tag: String?,
        message: String?,
    ) {
        Log.e(tag, message ?: "")
    }

    override fun logWarn(
        tag: String?,
        message: String?,
    ) {
        Log.w(tag, message ?: "")
    }

    override fun logInfo(
        tag: String?,
        message: String?,
    ) {}

    override fun logDebug(
        tag: String?,
        message: String?,
    ) {}

    override fun logVerbose(
        tag: String?,
        message: String?,
    ) {}

    override fun logStackTraceWithMessage(
        tag: String?,
        message: String?,
        e: Exception?,
    ) {
        Log.e(tag, message, e)
    }

    override fun logStackTrace(
        tag: String?,
        e: Exception?,
    ) {
        Log.e(tag, "", e)
    }
}

/** Holds latched modifier state for the extra-keys row. */
class ViewClient(
    private val onTap: () -> Unit,
) : TerminalViewClient {
    var ctrlLatched = false
    var altLatched = false

    override fun onScale(scale: Float): Float = scale.coerceIn(0.5f, 2f)

    override fun onSingleTapUp(e: MotionEvent) = onTap()

    override fun shouldBackButtonBeMappedToEscape() = false

    override fun shouldEnforceCharBasedInput() = true

    override fun shouldUseCtrlSpaceWorkaround() = false

    override fun isTerminalViewSelected() = true

    override fun copyModeChanged(copyMode: Boolean) {}

    override fun onKeyDown(
        keyCode: Int,
        e: KeyEvent,
        session: TerminalSession,
    ) = false

    override fun onKeyUp(
        keyCode: Int,
        e: KeyEvent,
    ) = false

    override fun onLongPress(event: MotionEvent) = false

    override fun readControlKey() = ctrlLatched.also { if (it) ctrlLatched = false }

    override fun readAltKey() = altLatched.also { if (it) altLatched = false }

    override fun readShiftKey() = false

    override fun readFnKey() = false

    override fun onCodePoint(
        codePoint: Int,
        ctrlDown: Boolean,
        session: TerminalSession,
    ) = false

    override fun onEmulatorSet() {}

    override fun logError(
        tag: String?,
        message: String?,
    ) {
        Log.e(tag, message ?: "")
    }

    override fun logWarn(
        tag: String?,
        message: String?,
    ) {}

    override fun logInfo(
        tag: String?,
        message: String?,
    ) {}

    override fun logDebug(
        tag: String?,
        message: String?,
    ) {}

    override fun logVerbose(
        tag: String?,
        message: String?,
    ) {}

    override fun logStackTraceWithMessage(
        tag: String?,
        message: String?,
        e: Exception?,
    ) {}

    override fun logStackTrace(
        tag: String?,
        e: Exception?,
    ) {}
}
