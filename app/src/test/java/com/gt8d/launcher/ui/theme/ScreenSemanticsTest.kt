package com.gt8d.launcher.ui.theme

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScreenSemanticsTest {
    @get:Rule(order = 0) val register = object : ExternalResource() {
        override fun before() {
            val app = ApplicationProvider.getApplicationContext<Application>()
            val pm = shadowOf(app.packageManager)
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

    @After
    fun restoreDetailedSemantics() {
        ScreenSemantics.detailed = true
    }

    @Test
    fun clearsChildrenWhenNotDetailed() {
        ScreenSemantics.detailed = false
        rule.setContent {
            Box(Modifier.screenSemantics()) {
                Text("中の文字", Modifier.testTag("inner"))
            }
        }
        rule.onNodeWithTag("inner").assertDoesNotExist()
        rule.onNodeWithText("中の文字").assertDoesNotExist()
        rule.onAllNodesWithContentDescription("パネル").assertCountEquals(1)
    }

    @Test
    fun keepsChildrenWhenDetailed() {
        ScreenSemantics.detailed = true
        rule.setContent {
            Box(Modifier.screenSemantics()) {
                Text("中の文字", Modifier.testTag("inner"))
            }
        }
        rule.onNodeWithTag("inner", useUnmergedTree = true).assertExists()
    }
}
