package dev.herdroid.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** How far the side panel is open, in pixels from closed (0) to its width. */
@Stable
class SideDrawerState {
    internal val offset = Animatable(0f)
    internal var width = 1f

    val isOpen: Boolean get() = offset.targetValue > 0f

    /** 0 closed .. 1 open, following the finger while dragging. */
    val fraction: Float get() = (offset.value / width).coerceIn(0f, 1f)

    suspend fun open() = offset.animateTo(width, spring(stiffness = 700f))

    suspend fun close() = offset.animateTo(0f, spring(stiffness = 700f))

    internal suspend fun dragBy(delta: Float) = offset.snapTo((offset.value + delta).coerceIn(0f, width))

    /** Settle after a drag: a quick flick decides, otherwise whichever half the panel is in. */
    internal suspend fun settle(velocity: Float) {
        val openIt =
            when {
                velocity > FLING_VELOCITY -> true
                velocity < -FLING_VELOCITY -> false
                else -> offset.value > width / 2
            }
        offset.animateTo(if (openIt) width else 0f, spring(stiffness = 700f), initialVelocity = velocity)
    }

    private companion object {
        const val FLING_VELOCITY = 800f
    }
}

@Composable
fun rememberSideDrawerState() = remember { SideDrawerState() }

/**
 * A modal side panel that follows the finger, like modern Android apps. Material's drawer
 * can drag too, but its drag fights the pane pager and cannot be driven from our own
 * gesture, which is needed to pull the panel out from the first pane.
 */
@Composable
fun SideDrawer(
    state: SideDrawerState,
    drawerContent: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    BackHandler(enabled = state.isOpen) { scope.launch { state.close() } }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val sheetWidth = min(maxWidth * 0.85f, 360.dp)
        val widthPx = with(LocalDensity.current) { sheetWidth.toPx() }
        state.width = widthPx
        content()
        val fraction = state.fraction
        if (fraction > 0f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f * fraction))
                    .pointerInput(state) { detectTapGestures { scope.launch { state.close() } } }
                    .closeDrag(state, scope),
            )
            Box(
                Modifier
                    .offset { IntOffset((state.offset.value - widthPx).roundToInt(), 0) }
                    .width(sheetWidth)
                    .fillMaxHeight()
                    .closeDrag(state, scope),
            ) {
                ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow) { drawerContent() }
            }
        }
    }
}

/** Dragging the open panel (or the dimmed area) moves it with the finger. */
private fun Modifier.closeDrag(
    state: SideDrawerState,
    scope: CoroutineScope,
): Modifier =
    pointerInput(state) {
        val tracker = VelocityTracker()
        detectHorizontalDragGestures(
            onDragStart = { tracker.resetTracking() },
            onDragEnd = { scope.launch { state.settle(tracker.calculateVelocity().x) } },
            onDragCancel = { scope.launch { state.settle(0f) } },
        ) { change, delta ->
            tracker.addPosition(change.uptimeMillis, change.position)
            change.consume()
            scope.launch { state.dragBy(delta) }
        }
    }

/**
 * On the first pane, where the pager cannot go back, a rightward drag pulls the panel out
 * under the finger. Watches the Initial pass so terminals (Android views) cannot swallow it;
 * vertical scrolls and leftward swipes are left alone for the chat and the pager.
 */
fun Modifier.pullDrawerAtStart(
    pager: PagerState,
    state: SideDrawerState,
    scope: CoroutineScope,
    onStart: () -> Unit,
): Modifier =
    pointerInput(pager, state) {
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (pager.canScrollBackward || state.isOpen) return@awaitEachGesture
            val tracker = VelocityTracker()
            tracker.addPosition(down.uptimeMillis, down.position)
            var dx = 0f
            var dy = 0f
            var pulling = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                tracker.addPosition(change.uptimeMillis, change.position)
                if (!change.pressed) break
                val delta = change.positionChange()
                if (!pulling) {
                    dx += delta.x
                    dy += delta.y
                    if (abs(dy) > slop && abs(dy) > abs(dx)) return@awaitEachGesture
                    if (dx < -slop) return@awaitEachGesture
                    if (dx > slop && dx > 2 * abs(dy)) {
                        pulling = true
                        onStart()
                        scope.launch { state.dragBy(dx) }
                    }
                } else {
                    scope.launch { state.dragBy(delta.x) }
                }
                if (pulling) change.consume()
            }
            if (pulling) scope.launch { state.settle(tracker.calculateVelocity().x) }
        }
    }
