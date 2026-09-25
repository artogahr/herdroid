package dev.herdroid.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
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
 * Pinch with two fingers to scale text, like Google Messages. One-finger drags pass through
 * untouched so the list still scrolls and the pager still swipes; only multi-touch events
 * are consumed, in the Initial pass so the list does not also react to them.
 */
fun Modifier.pinchToScale(
    current: () -> Float,
    onScale: (Float) -> Unit,
): Modifier =
    pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var scale = current()
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pressed = event.changes.count { it.pressed }
                if (pressed == 0) break
                if (pressed >= 2) {
                    val zoom = event.calculateZoom()
                    if (zoom != 1f) {
                        scale = (scale * zoom).coerceIn(UiPrefs.MIN_CHAT_SCALE, UiPrefs.MAX_CHAT_SCALE)
                        onScale(scale)
                    }
                    event.changes.forEach { it.consume() }
                }
            }
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
