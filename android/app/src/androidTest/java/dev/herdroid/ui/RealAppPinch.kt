package dev.herdroid.ui

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Not a check: injects real two-finger pinches into whatever is on screen (the running app),
 * so a screen recording can show what the user sees. Run only with -e pinchLab true.
 */
@RunWith(AndroidJUnit4::class)
class RealAppPinch {
    @Test
    fun pinchTheRunningApp() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("pinchLab") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Starting instrumentation restarts the app's process: bring the app back up and let
        // it reconnect and reopen its last chat before pinching.
        instrumentation.targetContext.startActivity(
            android.content
                .Intent(instrumentation.targetContext, dev.herdroid.MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        Thread.sleep(8_000)
        val ui = instrumentation.uiAutomation
        val cx = 540f
        val cy = 1100f

        /** One gesture following [spreads] (half the finger distance), one move per [stepMs]. */
        fun gesture(
            spreads: List<Float>,
            stepMs: Long,
        ) {
            val down = SystemClock.uptimeMillis()

            fun send(
                action: Int,
                count: Int,
                spread: Float,
            ) {
                val props =
                    Array(count) {
                        MotionEvent.PointerProperties().apply {
                            id = it
                            toolType = MotionEvent.TOOL_TYPE_FINGER
                        }
                    }
                val coords =
                    Array(count) {
                        MotionEvent.PointerCoords().apply {
                            x = if (it == 0) cx - spread else cx + spread
                            y = cy
                            pressure = 1f
                            size = 1f
                        }
                    }
                val e =
                    MotionEvent.obtain(
                        down,
                        SystemClock.uptimeMillis(),
                        action,
                        count,
                        props,
                        coords,
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
            send(MotionEvent.ACTION_DOWN, 1, spreads.first())
            send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, spreads.first())
            for (s in spreads) {
                send(MotionEvent.ACTION_MOVE, 2, s)
                Thread.sleep(stepMs)
            }
            send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, spreads.last())
            send(MotionEvent.ACTION_UP, 1, spreads.last())
        }

        fun ramp(
            from: Float,
            to: Float,
            steps: Int,
        ) = (1..steps).map { from + (to - from) * it / steps }
        // Slow spread, slow pinch, then fast in-and-out with the fingers down the whole time.
        gesture(ramp(100f, 180f, 90), 16)
        Thread.sleep(800)
        gesture(ramp(180f, 100f, 90), 16)
        Thread.sleep(800)
        gesture((0 until 4).flatMap { ramp(100f, 170f, 10) + ramp(170f, 100f, 10) }, 12)
    }
}
