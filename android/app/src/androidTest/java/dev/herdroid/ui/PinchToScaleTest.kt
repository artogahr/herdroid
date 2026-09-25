package dev.herdroid.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PinchToScaleTest {
    @get:Rule
    val compose = createComposeRule()

    private var scale by mutableFloatStateOf(1f)

    private fun content() =
        compose.setContent {
            Box(Modifier.size(360.dp).testTag("chat").pinchToScale({ scale }, { scale = it }))
        }

    @Test
    fun spreadingFingersEnlargesText() {
        content()
        compose.onNodeWithTag("chat").performTouchInput {
            pinch(center - Offset(40f, 0f), center - Offset(300f, 0f), center + Offset(40f, 0f), center + Offset(300f, 0f))
        }
        compose.waitForIdle()
        assertTrue("scale $scale", scale > 1.3f)
    }

    @Test
    fun pinchingShrinksTextWithinLimits() {
        content()
        compose.onNodeWithTag("chat").performTouchInput {
            pinch(center - Offset(400f, 0f), center - Offset(10f, 0f), center + Offset(400f, 0f), center + Offset(10f, 0f))
        }
        compose.waitForIdle()
        assertEquals(0.7f, scale, 0.01f)
    }

    @Test
    fun oneFingerScrollLeavesTextAlone() {
        content()
        compose.onNodeWithTag("chat").performTouchInput { swipeUp() }
        compose.waitForIdle()
        assertEquals(1f, scale, 0.001f)
    }
}
