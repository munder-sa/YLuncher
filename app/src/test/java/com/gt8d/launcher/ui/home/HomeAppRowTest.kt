package com.gt8d.launcher.ui.home

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.isDebugInspectorInfoEnabled
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import com.gt8d.launcher.ui.panel.AppInfo
import androidx.compose.ui.graphics.ImageBitmap
import com.gt8d.launcher.ui.theme.LauncherTheme
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1000dp-h600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeAppRowTest {
    @get:Rule val rule = createEmptyComposeRule()
    private lateinit var controller: ActivityController<ComponentActivity>
    private fun host(width: Int = 700, count: Int = 5,
                     onLaunch: (String) -> Unit = {}, onAdd: (() -> Unit)? = {},
                     missing: Boolean = false) {
        isDebugInspectorInfoEnabled = true
        controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller.get().setContent { LauncherTheme {
            HomeAppRow((1..count).map { AppInfo("p$it", "名前$it",
                if (missing) ImageBitmap(8, 8) else null, !missing) }, onLaunch, onAdd, Modifier.width(width.dp))
        } }
        controller.visible()
    }
    @After fun cleanup() {
        if (::controller.isInitialized) controller.pause().stop().destroy()
        isDebugInspectorInfoEnabled = false
    }
    @Test fun fiveApps_are96dpLeftAlignedWith24dpGap() {
        host()
        val row = rule.onNodeWithTag("home_apps").getBoundsInRoot()
        var previousRight = row.left.value - 24f
        for (i in 1..5) {
            val icon = rule.onNodeWithTag("home_app_p$i").assertWidthIsEqualTo(96.dp)
                .assertHeightIsEqualTo(96.dp).getBoundsInRoot()
            assertEquals(24f, icon.left.value - previousRight, 0f)
            previousRight = icon.right.value
            assertTrue(icon.right <= row.right)
        }
        val add = rule.onNodeWithTag("home_app_add").assertWidthIsEqualTo(96.dp)
            .assertHeightIsEqualTo(96.dp).getBoundsInRoot()
        assertEquals(24f, add.left.value - previousRight, 0f)
        assertTrue(add.right <= row.right)
    }
    @Test fun click_launchesPackage_andNoIconShowsInitial() {
        val launches = mutableListOf<String>()
        host(count = 1, onLaunch = { launches.add(it) })
        rule.onNodeWithText("名", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("home_app_p1").assertContentDescriptionEquals("名前1").performClick()
        assertEquals(listOf("p1"), launches)
    }
    @Test fun fiveAppsAndAdd_scrollToLastWhole96dpCell() {
        host(width = 300)
        val row = rule.onNodeWithTag("home_apps")
        for (i in 1..6) {
            row.performScrollToIndex(i - 1)
            val node = rule.onNodeWithTag(if (i == 6) "home_app_add" else "home_app_p$i").assertIsDisplayed()
            node.assertWidthIsEqualTo(96.dp).assertHeightIsEqualTo(96.dp)
            val bounds = node.getBoundsInRoot()
            val area = row.getBoundsInRoot()
            assertTrue(bounds.left >= area.left && bounds.right <= area.right &&
                bounds.top >= area.top && bounds.bottom <= area.bottom)
        }
    }
    @Test fun narrowRow_swipeMovesFirstIconWithoutShrinking() {
        host(width = 300)
        val first = rule.onNodeWithTag("home_app_p1").assertWidthIsEqualTo(96.dp)
        val before = first.getBoundsInRoot().right
        rule.onNodeWithTag("home_app_p2").assertWidthIsEqualTo(96.dp)
        rule.onNodeWithTag("home_apps").performTouchInput {
            swipeLeft(startX = centerX, endX = centerX - 60f, durationMillis = 1000L)
        }
        assertTrue(first.getBoundsInRoot().right < before)
        assertTrue(rule.onNodeWithTag("home_apps").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.HorizontalScrollAxisRange].value() > 0f)
    }
    @Test fun tenAppsAndAdd_scrollToLastWholeCell() {
        host(width = 300, count = 10)
        for (index in 0..10) {
            rule.onNodeWithTag("home_apps").performScrollToIndex(index)
            val node = rule.onNodeWithTag(if (index == 10) "home_app_add" else "home_app_p${index + 1}")
                .assertIsDisplayed().assertWidthIsEqualTo(96.dp).assertHeightIsEqualTo(96.dp)
            val bounds = node.getBoundsInRoot()
            val area = rule.onNodeWithTag("home_apps").getBoundsInRoot()
            assertTrue(bounds.left >= area.left && bounds.right <= area.right &&
                bounds.top >= area.top && bounds.bottom <= area.bottom)
        }
    }
    @Test fun width600_uses24dpGap() {
        host(width = 600)
        val a = rule.onNodeWithTag("home_app_p1").assertWidthIsEqualTo(96.dp).getBoundsInRoot()
        val b = rule.onNodeWithTag("home_app_p2").assertWidthIsEqualTo(96.dp).getBoundsInRoot()
        assertEquals(24f, b.left.value - a.right.value, 0f)
    }
    @Test fun oneApp_is96dpAtLeft() {
        host(count = 1)
        val icon = rule.onNodeWithTag("home_app_p1").assertWidthIsEqualTo(96.dp).getBoundsInRoot()
        assertEquals(rule.onNodeWithTag("home_apps").getBoundsInRoot().left, icon.left)
    }
    @Test fun emptyApps_drawsNothing() {
        host(count = 0, onAdd = null)
        rule.onNodeWithTag("home_apps").assertDoesNotExist()
    }
    @Test fun emptyApps_withAddShowsOnlyAddAndCallsCallback() {
        var added = 0
        host(count = 0, onAdd = { added++ })
        rule.onNodeWithTag("home_apps").assertExists()
        rule.onAllNodes(hasClickAction()).assertCountEquals(1)
        rule.onNodeWithTag("home_app_add").assertWidthIsEqualTo(96.dp).assertHeightIsEqualTo(96.dp)
            .assertContentDescriptionEquals("パネルのアイコンを追加").performClick()
        assertEquals(1, added)
    }
    @Test fun missingAppWithIcon_describesMissingAndDoesNotLaunch() {
        val launches = mutableListOf<String>()
        host(count = 1, missing = true, onLaunch = { launches.add(it) })
        rule.onNodeWithTag("home_missing_p1")
            .assertContentDescriptionEquals("名前1(アプリが見つかりません)").performClick()
        rule.onNodeWithTag("home_app_p1").assertDoesNotExist()
        assertTrue(launches.isEmpty())
    }
}
