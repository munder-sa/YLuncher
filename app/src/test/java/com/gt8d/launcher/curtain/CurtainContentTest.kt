package com.gt8d.launcher.curtain

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.width
import androidx.test.core.app.ApplicationProvider
import com.gt8d.launcher.config.SplitRatio
import com.gt8d.launcher.ui.theme.LauncherTheme
import org.junit.Assert.assertEquals
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
class CurtainContentTest {

    @get:Rule(order = 0)
    val registerActivityRule = object : ExternalResource() {
        override fun before() {
            RuntimeEnvironment.setQualifiers("w1280dp-h800dp")
            val app = ApplicationProvider.getApplicationContext<Application>()
            val shadowPm = Shadows.shadowOf(app.packageManager)
            val cn = ComponentName(app.packageName, ComponentActivity::class.java.name)
            shadowPm.addOrUpdateActivity(
                ActivityInfo().apply {
                    name = cn.className
                    packageName = cn.packageName
                },
            )
            shadowPm.addIntentFilterForActivity(
                cn,
                IntentFilter(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                },
            )
        }
    }

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private fun sampleTarget(
        setName: String = "動画＋音楽",
        right: SlotPreview = SlotPreview.App("com.apple.android.music", "Apple Music"),
        ratio: SplitRatio = SplitRatio.R6_4,
    ) = CurtainTarget(
        setName = setName,
        left = SlotPreview.App("com.google.android.youtube", "YouTube"),
        right = right,
        ratio = ratio,
        position = 0,
        count = 3,
    )

    @Test
    fun curtainContent_showsSetNameAndRatioBlocks() {
        val target = sampleTarget(setName = "動画＋音楽", ratio = SplitRatio.R6_4)
        rule.setContent {
            LauncherTheme {
                CurtainContent(target = target, showProgress = false)
            }
        }

        rule.onNodeWithTag("curtain_set_name")
            .assertIsDisplayed()
            .assertTextEquals("動画＋音楽")

        val leftBounds = rule.onNodeWithTag("curtain_left").getBoundsInRoot()
        val rightBounds = rule.onNodeWithTag("curtain_right").getBoundsInRoot()
        val leftWidth = leftBounds.width.value
        val rightWidth = rightBounds.width.value
        val ratio = leftWidth / (leftWidth + rightWidth)
        assertEquals(0.6f, ratio, 0.02f)
    }

    @Test
    fun curtainContent_progressOnlyWhenRequested() {
        var showProgress by mutableStateOf(false)
        val target = sampleTarget()
        rule.setContent {
            LauncherTheme {
                CurtainContent(target = target, showProgress = showProgress)
            }
        }

        rule.onNodeWithTag("curtain_progress").assertDoesNotExist()

        showProgress = true
        rule.waitForIdle()
        rule.onNodeWithTag("curtain_progress").assertIsDisplayed()
    }

    @Test
    fun curtainContent_panelSlotShowsPanelLabel() {
        val target = sampleTarget(right = SlotPreview.Panel)
        rule.setContent {
            LauncherTheme {
                CurtainContent(target = target, showProgress = false)
            }
        }

        rule.onNodeWithText("パネル").assertIsDisplayed()
    }
}
