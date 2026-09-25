package dev.herdroid.ui

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.FrameMetrics
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mikepenz.markdown.m3.Markdown
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/** Frame times while slowly pinching a long Markdown chat: live relayout vs preview-then-commit. */
@RunWith(AndroidJUnit4::class)
class PinchJankTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val message =
        "## Heading\\n\\nSome **bold** text with `inline code` and a list:\\n\\n- one item\\n- two items\\n- three items\\n\\n" +
            "A longer paragraph that wraps across several lines on a phone screen so layout has real work to do."

    private fun frameTimesDuring(block: () -> Unit): List<Double> {
        val times = CopyOnWriteArrayList<Double>()
        val window: Window = compose.activity.window
        val listener =
            Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
                times += metrics.getMetric(FrameMetrics.TOTAL_DURATION) / 1_000_000.0
            }
        compose.runOnUiThread { window.addOnFrameMetricsAvailableListener(listener, Handler(Looper.getMainLooper())) }
        block()
        compose.waitForIdle()
        Thread.sleep(300)
        compose.runOnUiThread { window.removeOnFrameMetricsAvailableListener(listener) }
        return times.toList()
    }

    /**
     * A real two-finger spread over two real seconds. Compose test gestures run on a virtual
     * clock and arrive at once, which says nothing about frame times.
     */
    private fun slowPinch() {
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        val bounds = compose.onNodeWithTag("chat").fetchSemanticsNode().boundsInWindow
        val cx = bounds.center.x
        val cy = bounds.center.y
        val down = SystemClock.uptimeMillis()

        fun props(id: Int) =
            MotionEvent.PointerProperties().apply {
                this.id = id
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }

        fun coords(x: Float) =
            MotionEvent.PointerCoords().apply {
                this.x = x
                y = cy
                pressure = 1f
                size = 1f
            }

        fun send(
            action: Int,
            count: Int,
            spread: Float,
        ) {
            val e =
                MotionEvent.obtain(
                    down,
                    SystemClock.uptimeMillis(),
                    action,
                    count,
                    Array(count) { props(it) },
                    Array(count) { coords(if (it == 0) cx - spread else cx + spread) },
                    0,
                    0,
                    1f,
                    1f,
                    0,
                    0,
                    InputDevice.SOURCE_TOUCHSCREEN,
                    0,
                )
            ui.injectInputEvent(e, true)
            e.recycle()
        }
        send(MotionEvent.ACTION_DOWN, 1, 60f)
        send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, 60f)
        val steps = 120
        for (i in 1..steps) {
            send(MotionEvent.ACTION_MOVE, 2, 60f + 260f * i / steps)
            Thread.sleep(16)
        }
        send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, 320f)
        send(MotionEvent.ACTION_UP, 1, 320f)
    }

    private fun report(
        name: String,
        times: List<Double>,
    ): Double {
        val janky = times.count { it > 16.7 }
        val p90 = times.sorted().let { it[(it.size * 0.9).toInt().coerceAtMost(it.size - 1)] }
        Log.i("PinchJank", "$name: frames=${times.size} janky=$janky p90=${"%.1f".format(p90)}ms max=${"%.1f".format(times.max())}ms")
        return p90
    }

    @Test
    fun previewKeepsPinchingSmooth() {
        var liveScale by mutableFloatStateOf(1f)
        var mode by mutableFloatStateOf(0f) // 0: live relayout (old), 1: preview then commit (new)
        var zoom by mutableFloatStateOf(1f)
        compose.setContent {
            Box(
                Modifier
                    .fillMaxSize()
                    .testTag("chat")
                    .pinchToScale(
                        current = { liveScale },
                        onPreview = { z, _ -> if (mode == 0f) liveScale = z else zoom = z },
                        onCommit = { s ->
                            liveScale = if (mode == 0f) liveScale else s
                            zoom = 1f
                        },
                    ).graphicsLayer {
                        scaleX = zoom
                        scaleY = zoom
                    },
            ) {
                ScaledText(liveScale) {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(List(40) { it }) { Markdown(message, modifier = Modifier.fillMaxWidth().padding(16.dp)) }
                    }
                }
            }
        }
        compose.waitForIdle()
        val live = report("live relayout", frameTimesDuring { slowPinch() })
        liveScale = 1f
        mode = 1f
        compose.waitForIdle()
        val preview = report("preview", frameTimesDuring { slowPinch() })
        assertTrue("preview p90 $preview ms should beat live $live ms", preview <= live)
    }
}
