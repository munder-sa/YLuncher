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
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.width
import androidx.compose.ui.unit.height
import androidx.test.core.app.ApplicationProvider
import com.gt8d.launcher.config.SplitRatio
import com.gt8d.launcher.ui.theme.LauncherTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.assert
import com.gt8d.launcher.ui.home.PanelIconMirrored

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CurtainContentTest {
    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun whiteProgressDotsAndAppIconGeometry() {
        val bitmap = ImageBitmap(96, 96)
        rule.setContent {
            CurtainContent(sampleTarget(right = SlotPreview.Panel), true,
                iconFor = { bitmap })
        }
        rule.onNodeWithTag("curtain_progress").assert(SemanticsMatcher.expectValue(CurtainColor, Color.White))
            .assert(SemanticsMatcher.expectValue(CurtainTrackColor, Color.White.copy(alpha = 0.24f)))
        rule.onNodeWithTag("curtain_dot_0").assert(SemanticsMatcher.expectValue(CurtainColor, Color.White))
        rule.onNodeWithTag("curtain_dot_1").assert(SemanticsMatcher.expectValue(CurtainColor, Color.White.copy(alpha = 0.4f)))
        val icon = rule.onNodeWithTag("curtain_app_icon")
            .assert(SemanticsMatcher.expectValue(CurtainIconCorner, 8)).getBoundsInRoot()
        assertEquals(96f, icon.width.value)
        assertEquals(96f, icon.height.value)
        val panelBounds = rule.onNode(hasContentDescription("パネル"), useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(PanelIconMirrored, false))
            .getBoundsInRoot()
        assertEquals(96f, panelBounds.width.value)
        assertEquals(96f, panelBounds.height.value)
    }

    @Test fun leftPanelMirrorsAndMissingIconShowsInitial() {
        rule.setContent { CurtainContent(sampleTarget().copy(left = SlotPreview.Panel), false) }
        rule.onNode(hasContentDescription("パネル"), useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(PanelIconMirrored, true))
        rule.onNodeWithText("A").assertIsDisplayed()
        rule.onNodeWithTag("curtain_app_icon").assertDoesNotExist()
    }

    @Test fun cancel_visibilitySizeCallbackAndStablePositions() {
        var progress by mutableStateOf(false)
        var cancellable by mutableStateOf(true)
        var calls = 0
        rule.setContent {
            LauncherTheme {
                CurtainContent(sampleTarget().copy(cancellable = cancellable), progress,
                    onCancel = { calls++ })
            }
        }
        rule.onNodeWithTag("curtain_opening").assertTextEquals("開いています").assertIsDisplayed()
        rule.onNodeWithTag("curtain_cancel").assertDoesNotExist()
        val tags = listOf("curtain_set_name", "curtain_left", "curtain_right", "curtain_dots")
        val before = tags.map { rule.onNodeWithTag(it).getBoundsInRoot() }
        progress = true
        rule.waitForIdle()
        val button = rule.onNodeWithTag("curtain_cancel").assertIsDisplayed().assertTextEquals("ホームへ戻る")
        val bounds = button.getBoundsInRoot()
        assertTrue(bounds.height.value >= 72f)
        assertTrue(bounds.width.value >= 72f)
        button.performClick()
        assertEquals(1, calls)
        assertEquals(before, tags.map { rule.onNodeWithTag(it).getBoundsInRoot() })
        cancellable = false
        rule.waitForIdle()
        rule.onNodeWithTag("curtain_cancel").assertDoesNotExist()
        assertEquals(before, tags.map { rule.onNodeWithTag(it).getBoundsInRoot() })
    }

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
