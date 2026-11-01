package com.gt8d.launcher.ui.home

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.isDebugInspectorInfoEnabled
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import com.gt8d.launcher.ui.apps.AppListEntry
import com.gt8d.launcher.ui.theme.LauncherTheme
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1000dp-h600dp")
class HomeAppRowTest {
    @get:Rule val rule = createEmptyComposeRule()
    private lateinit var controller: ActivityController<ComponentActivity>
    private fun host(width: Int = 700, count: Int = 5,
                     onLaunch: (AppListEntry) -> Unit = {}) {
        isDebugInspectorInfoEnabled = true
        controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller.get().setContent { LauncherTheme {
            HomeAppRow((1..count).map { AppListEntry("p$it", "p$it.Main", "名前$it") }, { null }, onLaunch, Modifier.width(width.dp))
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
    }
    @Test fun click_launchesEntry_andNoIconShowsInitial() {
        val launches = mutableListOf<AppListEntry>()
        host(count = 1, onLaunch = { launches.add(it) })
        rule.onNodeWithText("名", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("home_app_p1").assertContentDescriptionEquals("名前1").performClick()
        assertEquals(listOf(AppListEntry("p1", "p1.Main", "名前1")), launches)
    }
    @Test fun twelveApps_scrollToLastWhole96dpIcon() {
        host(width = 300, count = 12)
        val row = rule.onNodeWithTag("home_apps")
        for (i in 1..12) {
            row.performScrollToIndex(i - 1)
            val node = rule.onNodeWithTag("home_app_p$i").assertIsDisplayed()
            node.assertWidthIsEqualTo(96.dp).assertHeightIsEqualTo(96.dp)
            val bounds = node.getBoundsInRoot()
            val area = row.getBoundsInRoot()
            assertTrue(bounds.left >= area.left && bounds.right <= area.right &&
                bounds.top >= area.top && bounds.bottom <= area.bottom)
        }
    }
    @Test fun narrowRow_swipeMovesFirstIconWithoutShrinking() {
        host(width = 300, count = 12)
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
        host(count = 0)
        rule.onNodeWithTag("home_apps").assertDoesNotExist()
    }
}
