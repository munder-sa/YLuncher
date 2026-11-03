package com.gt8d.launcher.ui.home

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.test.core.app.ApplicationProvider
import com.gt8d.launcher.media.NowPlaying
import com.gt8d.launcher.media.lyrics.LyricLine
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LyricIndexTest {
    @get:Rule(order = 0) val activityRule = object : ExternalResource() {
        override fun before() {
            val app = ApplicationProvider.getApplicationContext<Application>()
            val component = ComponentName(app.packageName, ComponentActivity::class.java.name)
            val pm = Shadows.shadowOf(app.packageManager)
            pm.addOrUpdateActivity(ActivityInfo().apply {
                name = component.className
                packageName = component.packageName
            })
            pm.addIntentFilterForActivity(component, IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            })
        }
    }
    @get:Rule(order = 1) val composeTestRule = createComposeRule()
    private val lines = listOf(1000L, 2000L, 3000L).map { LyricLine(it, "Dummy $it") }
    private var t = 500L
    private var now by mutableStateOf(NowPlaying(
        "dummy.pkg", "Dummy song", "Dummy artist", null, null, true, false,
        positionMs = 0, positionUpdatedAt = 0, speed = 1f,
    ))

    private fun show() {
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            val index by rememberLyricIndex(now, lines) { t }
            Text(index.toString(), Modifier.testTag("index"))
        }
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.mainClock.advanceTimeByFrame()
    }

    @Test fun advancesEvery200msWhilePlaying() {
        show()
        composeTestRule.onNodeWithTag("index").assertTextEquals("-1")
        composeTestRule.runOnIdle { t = 2100 }
        composeTestRule.mainClock.advanceTimeBy(200)
        composeTestRule.onNodeWithTag("index").assertTextEquals("1")
    }

    @Test fun pausedDoesNotAdvance() {
        now = now.copy(playing = false, positionMs = 500)
        show()
        composeTestRule.onNodeWithTag("index").assertTextEquals("-1")
        composeTestRule.runOnIdle { t = 9000 }
        composeTestRule.mainClock.advanceTimeBy(200)
        composeTestRule.onNodeWithTag("index").assertTextEquals("-1")
    }

    @Test fun seekJumps() {
        show()
        composeTestRule.onNodeWithTag("index").assertTextEquals("-1")
        composeTestRule.runOnIdle {
            now = now.copy(positionMs = 9000, positionUpdatedAt = t)
            Snapshot.sendApplyNotifications()
        }
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.onNodeWithTag("index").assertTextEquals("2")
    }
}
