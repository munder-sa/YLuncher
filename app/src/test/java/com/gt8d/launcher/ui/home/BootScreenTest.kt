package com.gt8d.launcher.ui.home

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.core.app.ApplicationProvider
import com.gt8d.launcher.config.SplitRatio
import com.gt8d.launcher.curtain.CurtainTarget
import com.gt8d.launcher.curtain.SlotPreview
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
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import com.gt8d.launcher.ui.theme.Palette

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BootScreenTest {
    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun whiteColorsAndWideSlotIcons() {
        val bitmap = ImageBitmap(48, 48)
        rule.setContent {
            BootScreen(sampleTarget().copy(left = SlotPreview.Panel,
                right = SlotPreview.App("app", "App"), ratio = SplitRatio.R5_5),
                3, false, {}, {}, Modifier.width(640.dp), iconFor = { bitmap })
        }
        rule.onNodeWithTag("boot_set_name").assert(SemanticsMatcher.expectValue(BootColor, Color.White))
        rule.onNodeWithTag("boot_open_now").assert(SemanticsMatcher.expectValue(BootColor, Color.White))
            .assert(SemanticsMatcher.expectValue(BootContentColor, Palette.Background0))
        val panelBounds = rule.onNode(hasContentDescription("パネル"), useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(PanelIconMirrored, true))
            .getBoundsInRoot()
        assertEquals(48f, panelBounds.width.value)
        assertEquals(48f, panelBounds.height.value)
        val bounds = rule.onNodeWithTag("boot_app_icon").assertIsDisplayed().getBoundsInRoot()
        assertEquals(48f, bounds.width.value)
        assertEquals(48f, bounds.height.value)
    }

    @Test fun missingAppIconKeepsTextAndRightPanelIsNotMirrored() {
        rule.setContent { BootScreen(sampleTarget().copy(ratio = SplitRatio.R5_5), 3, false, {}, {}, Modifier.width(640.dp)) }
        rule.onNodeWithTag("boot_app_icon").assertDoesNotExist()
        rule.onNodeWithText("YouTube").assertIsDisplayed()
        rule.onNode(hasContentDescription("パネル"), useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(PanelIconMirrored, false))
    }

    @Test fun narrowSlotsShowOnlyNames() {
        rule.setContent { BootScreen(sampleTarget(), 3, false, {}, {}, Modifier.width(400.dp), iconFor = { ImageBitmap(48, 48) }) }
        rule.onNodeWithTag("boot_app_icon").assertDoesNotExist()
        rule.onNode(hasContentDescription("パネル"), useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithText("YouTube").assertIsDisplayed()
        rule.onNodeWithText("パネル").assertIsDisplayed()
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

    private fun sampleTarget() = CurtainTarget(
        setName = "1  YouTube | パネル",
        left = SlotPreview.App("com.google.android.youtube", "YouTube"),
        right = SlotPreview.Panel,
        ratio = SplitRatio.R6_4,
        position = 1,
        count = 4,
    )

    @Test
    fun bootScreen_countdown_showsButtons() {
        var openNowCount = 0
        var stayHomeCount = 0

        rule.setContent {
            LauncherTheme {
                BootScreen(
                    target = sampleTarget(),
                    secondsLeft = 3,
                    opening = false,
                    onOpenNow = { openNowCount++ },
                    onStayHome = { stayHomeCount++ },
                )
            }
        }

        rule.onNodeWithTag("boot_message")
            .assertIsDisplayed()
            .assertTextEquals("3 秒後に前回のセットを開きます")

        val openNowNode = rule.onNodeWithTag("boot_open_now")
        rule.onNodeWithText("1  YouTube | パネル").assertIsDisplayed()
        val stayHomeNode = rule.onNodeWithTag("boot_stay_home")
        openNowNode.assertIsDisplayed()
        stayHomeNode.assertIsDisplayed()

        val openNowBounds = openNowNode.getBoundsInRoot()
        assertTrue("boot_open_now width ${openNowBounds.width} < 72.dp", openNowBounds.width >= 72.dp)
        assertTrue("boot_open_now height ${openNowBounds.height} < 72.dp", openNowBounds.height >= 72.dp)

        val stayHomeBounds = stayHomeNode.getBoundsInRoot()
        assertTrue("boot_stay_home width ${stayHomeBounds.width} < 72.dp", stayHomeBounds.width >= 72.dp)
        assertTrue("boot_stay_home height ${stayHomeBounds.height} < 72.dp", stayHomeBounds.height >= 72.dp)

        openNowNode.performClick()
        assertEquals(1, openNowCount)
        assertEquals(0, stayHomeCount)

        stayHomeNode.performClick()
        assertEquals(1, openNowCount)
        assertEquals(1, stayHomeCount)
    }

    @Test
    fun bootScreen_opening_hidesOpenNow() {
        rule.setContent {
            LauncherTheme {
                BootScreen(
                    target = sampleTarget(),
                    secondsLeft = 1,
                    opening = true,
                    onOpenNow = {},
                    onStayHome = {},
                )
            }
        }

        rule.onNodeWithTag("boot_open_now").assertDoesNotExist()
        rule.onNodeWithTag("boot_stay_home").assertIsDisplayed()
        rule.onNodeWithTag("boot_message")
            .assertIsDisplayed()
            .assertTextEquals("前回のセットを開いています…")
    }
}
