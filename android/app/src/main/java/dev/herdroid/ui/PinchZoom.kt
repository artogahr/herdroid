package dev.herdroid.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import dev.herdroid.HerdroidApp
import dev.herdroid.data.UiPrefs

val LocalUiPrefs = staticCompositionLocalOf<UiPrefs?> { null }

@Composable
fun uiPrefs(): UiPrefs = LocalUiPrefs.current ?: (LocalContext.current.applicationContext as HerdroidApp).ui

/**
 * Pinch with two fingers to scale text, like Google Messages. Re-laying out a whole chat of
 * Markdown on every finger move is what made pinching janky, so while fingers are down this
 * only reports a visual zoom around the pinch point ([onPreview]; apply it as a
 * graphicsLayer), and the text scale changes once, on release ([onCommit]). One-finger drags
 * pass through untouched; multi-touch events are consumed in the Initial pass so the list
 * and pager do not also react.
 */
fun Modifier.pinchToScale(
    current: () -> Float,
    onPreview: (zoom: Float, focus: Offset) -> Unit,
    onCommit: (scale: Float) -> Unit,
): Modifier =
    pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val start = current()
            var zoom = 1f
            var pinched = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pressed = event.changes.count { it.pressed }
                if (pressed == 0) break
                if (pressed >= 2) {
                    pinched = true
                    val step = event.calculateZoom()
                    if (step != 1f) {
                        // Keep the preview inside the limits the committed scale will have.
                        zoom = (zoom * step).coerceIn(UiPrefs.MIN_CHAT_SCALE / start, UiPrefs.MAX_CHAT_SCALE / start)
                        onPreview(zoom, event.calculateCentroid(useCurrent = true))
                    }
                    event.changes.forEach { it.consume() }
                }
            }
            if (pinched) onCommit(start * zoom)
        }
    }

/** Scales every sp inside [content] by [scale] on top of the system font size. */
@Composable
fun ScaledText(
    scale: Float,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * scale), content = content)
}
