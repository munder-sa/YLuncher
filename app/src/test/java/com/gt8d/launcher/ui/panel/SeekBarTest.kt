package com.gt8d.launcher.ui.panel

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.SemanticsMatcher
import com.gt8d.launcher.ui.theme.LauncherTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SeekBarTest {
    @get:Rule(order = 0) val register = object : org.junit.rules.ExternalResource() {
        override fun before() {
            val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
            val pm = org.robolectric.Shadows.shadowOf(app.packageManager)
            val cn = android.content.ComponentName(app.packageName, androidx.activity.ComponentActivity::class.java.name)
            pm.addOrUpdateActivity(android.content.pm.ActivityInfo().apply {
                name = cn.className; packageName = cn.packageName
            })
            pm.addIntentFilterForActivity(cn, android.content.IntentFilter(android.content.Intent.ACTION_MAIN).apply {
                addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            })
        }
    }
    @get:Rule(order = 1) val rule = createComposeRule()

    @Test fun autoProgressUpdatesPerSecond() {
        val position = mutableLongStateOf(0L)
        rule.setContent {
            LauncherTheme { Column { SeekBar(120000L, { position.longValue }, {}) } }
        }
        for ((value, expected) in listOf(0L to 0f, 400L to 0f, 900L to 0f, 1100L to 1000f)) {
            rule.runOnIdle { position.longValue = value }
            assertEquals(expected, rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).fetchSemanticsNode()
                .config[SemanticsProperties.ProgressBarRangeInfo].current, 0f)
        }
    }
}
