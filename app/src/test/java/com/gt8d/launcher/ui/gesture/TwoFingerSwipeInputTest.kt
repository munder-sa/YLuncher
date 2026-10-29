package com.gt8d.launcher.ui.gesture

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.gt8d.launcher.edge.EdgeAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TwoFingerSwipeInputTest {
    @get:Rule(order = 0)
    val register = object : ExternalResource() {
        override fun before() {
            RuntimeEnvironment.setQualifiers("w1280dp-h800dp")
            val app = ApplicationProvider.getApplicationContext<Application>()
            val pm = Shadows.shadowOf(app.packageManager)
            val cn = ComponentName(app.packageName, ComponentActivity::class.java.name)
            pm.addOrUpdateActivity(ActivityInfo().apply {
                name = cn.className
                packageName = cn.packageName
            })
            pm.addIntentFilterForActivity(cn, IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            })
        }
    }
    @get:Rule(order = 1) val rule = createComposeRule()
    private val actions = mutableListOf<EdgeAction>()
    private var density = 1f
    private lateinit var scroll: ScrollState

    private fun host() {
        rule.setContent {
            density = LocalDensity.current.density
            scroll = rememberScrollState()
            Box(Modifier.size(516.dp, 400.dp).testTag("host").twoFingerSwipe { actions.add(it) }) {
                Box(Modifier.size(516.dp, 400.dp).verticalScroll(scroll)) {
                    Box(Modifier.height(2000.dp))
                }
            }
        }
    }
    private fun point(x: Float, y: Float) = Offset(x * density, y * density)

    @Test fun input_twoFingerLeft_emitsNextOnce() = swipe(400f, 280f, EdgeAction.Next)
    @Test fun input_twoFingerRight_emitsPrevOnce() = swipe(100f, 220f, EdgeAction.Prev)
    private fun swipe(from: Float, to: Float, expected: EdgeAction) {
        host()
        val node = rule.onNodeWithTag("host")
        node.performTouchInput {
            down(0, point(from, 100f)); down(1, point(from, 200f))
            moveTo(0, point(to, 100f)); moveTo(1, point(to, 200f)); up(0)
        }
        rule.runOnIdle { assertEquals(emptyList<EdgeAction>(), actions) }
        node.performTouchInput { up(1) }
        rule.runOnIdle { assertEquals(listOf(expected), actions) }
    }
    @Test fun input_thirdFingerDuringSwipe_emitsNothing() {
        host()
        rule.onNodeWithTag("host").performTouchInput {
            down(0, point(400f, 100f)); down(1, point(400f, 200f))
            moveTo(0, point(340f, 100f)); moveTo(1, point(340f, 200f))
            down(2, point(340f, 300f)); up(2)
            moveTo(0, point(280f, 100f)); moveTo(1, point(280f, 200f))
            up(0); up(1)
        }
        rule.runOnIdle { assertEquals(emptyList<EdgeAction>(), actions) }
    }
    @Test fun input_oneFingerScroll_reachesChildWithoutSetChange() {
        host()
        rule.onNodeWithTag("host").performTouchInput {
            down(0, point(200f, 300f))
            moveTo(0, point(200f, 250f))
            moveTo(0, point(200f, 200f))
            moveTo(0, point(200f, 100f)); up(0)
        }
        rule.runOnIdle {
            assertTrue(scroll.value > 0)
            assertEquals(emptyList<EdgeAction>(), actions)
        }
    }
    @Test fun input_sequentialSinglePointers_doNotCountAsTwo() {
        host()
        val node = rule.onNodeWithTag("host")
        node.performTouchInput {
            down(0, point(400f, 100f)); moveTo(0, point(280f, 100f)); up(0)
        }
        rule.runOnIdle { assertEquals(emptyList<EdgeAction>(), actions) }
        node.performTouchInput {
            down(1, point(400f, 200f)); moveTo(1, point(280f, 200f)); up(1)
        }
        rule.runOnIdle { assertEquals(emptyList<EdgeAction>(), actions) }
    }
}
