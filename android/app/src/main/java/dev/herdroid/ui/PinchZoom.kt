package dev.herdroid.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import dev.herdroid.HerdroidApp
import dev.herdroid.data.UiPrefs
import kotlin.math.abs

val LocalUiPrefs = staticCompositionLocalOf<UiPrefs?> { null }

@Composable
fun uiPrefs(): UiPrefs = LocalUiPrefs.current ?: (LocalContext.current.applicationContext as HerdroidApp).ui

/**
 * Pinch with two fingers to scale text, like Google Messages: the text re-flows live while
 * the fingers move. Re-laying out a Markdown chat is too slow to do on every touch event, so
 * [onLive] gets the scale in [STEP] increments (at most once per event, i.e. per frame), and
 * [onResidual] gets the small zoom left between steps, to apply as a graphics layer so the
 * pinch still tracks the fingers smoothly. [onCommit] runs once on release (persist there).
 * One-finger drags pass through; multi-touch is consumed in the Initial pass so the list and
 * pager do not also react.
 */
fun Modifier.pinchToScale(
    current: () -> Float,
    onStart: (focus: Offset) -> Unit = {},
    onLive: (scale: Float) -> Unit,
    onResidual: (zoom: Float, focus: Offset) -> Unit = { _, _ -> },
    onCommit: (scale: Float) -> Unit,
): Modifier =
    pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var target = current()
            var applied = target
            var pinched = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pressed = event.changes.count { it.pressed }
                if (pressed == 0) break
                if (pressed >= 2) {
                    if (!pinched) onStart(event.calculateCentroid(useCurrent = true))
                    pinched = true
                    val step = event.calculateZoom()
                    if (step != 1f) {
                        target = (target * step).coerceIn(UiPrefs.MIN_CHAT_SCALE, UiPrefs.MAX_CHAT_SCALE)
                        if (abs(target / applied - 1f) >= STEP) {
                            applied = target
                            onLive(applied)
                        }
                        onResidual(target / applied, event.calculateCentroid(useCurrent = true))
                    }
                    event.changes.forEach { it.consume() }
                }
            }
            if (pinched) {
                onLive(target)
                onResidual(1f, Offset.Zero)
                onCommit(target)
            }
        }
    }

/** How much the scale moves between live re-layouts while pinching. */
const val STEP = 0.04f

/** Scales every sp inside [content] by [scale] on top of the system font size. */
@Composable
fun ScaledText(
    scale: Float,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * scale), content = content)
}

/** Pinch state for a chat list: the live text scale and the small zoom between re-flows. */
@Stable
class ChatPinch {
    var liveScale by mutableStateOf<Float?>(null)
        internal set
    internal var zoom by mutableFloatStateOf(1f)
}

@Composable
fun rememberChatPinch() = remember { ChatPinch() }

/**
 * Live pinch-to-scale for a reversed chat list. The text re-flows in steps while the fingers
 * move and a graphics-layer zoom fills in between steps. Both grow from the bottom edge: the
 * list keeps its newest message pinned there as heights change, so a zoom around any other
 * point would disagree with the next re-flow and the text would jump at every step. (Keeping
 * the pinch point fixed instead needs scroll corrections that overshoot as lines re-wrap,
 * which showed as flashes of other parts of the chat.) [onHold] is true while pinching (pause
 * anything that would insert items); [onCommit] gets the final scale once.
 */
fun Modifier.chatPinch(
    pinch: ChatPinch,
    current: () -> Float,
    onHold: (Boolean) -> Unit = {},
    onCommit: (Float) -> Unit,
): Modifier =
    this
        .clipToBounds()
        .pinchToScale(
            current = current,
            onStart = { onHold(true) },
            onLive = { scale -> pinch.liveScale = scale },
            onResidual = { z, _ -> pinch.zoom = z },
            onCommit = { scale ->
                onCommit(scale)
                // The committed scale takes over from the live one in the same frame.
                pinch.liveScale = null
                onHold(false)
            },
        ).graphicsLayer {
            scaleX = pinch.zoom
            scaleY = pinch.zoom
            transformOrigin = TransformOrigin(0.5f, 1f)
        }
