package com.gt8d.launcher.ui.home

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.InspectableValue
import androidx.compose.ui.platform.isDebugInspectorInfoEnabled
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import com.gt8d.launcher.ui.panel.AppInfo
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
    private fun host(width: Int = 700, count: Int = 5, installed: Boolean = true,
                     onLaunch: (String) -> Unit = {}) {
        isDebugInspectorInfoEnabled = true
        controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller.get().setContent { LauncherTheme {
            HomeAppRow((1..count).map { AppInfo("p$it", "名前$it", null, installed) }, onLaunch, Modifier.width(width.dp))
        } }
        controller.visible()
    }
    @After fun cleanup() {
        if (::controller.isInitialized) controller.pause().stop().destroy()
        isDebugInspectorInfoEnabled = false
    }
    @Test fun fiveApps_are96dpLeftAlignedWithCalculatedGap() {
        host()
        val row = rule.onNodeWithTag("home_apps").getBoundsInRoot()
        var previousRight = row.left.value - 48f
        for (i in 1..5) {
            val icon = rule.onNodeWithTag("home_app_p$i").assertWidthIsEqualTo(96.dp)
                .assertHeightIsEqualTo(96.dp).getBoundsInRoot()
            assertEquals(48f, icon.left.value - previousRight, 0f)
            previousRight = icon.right.value
            assertTrue(icon.right <= row.right)
        }
    }
    @Test fun click_launchesPackage_andNoIconShowsInitial() {
        val launches = mutableListOf<String>()
        host(count = 1, onLaunch = { launches.add(it) })
        rule.onNodeWithText("名", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("home_app_p1").assertContentDescriptionEquals("名前1").performClick()
        assertEquals(listOf("p1"), launches)
    }
    @Test fun missingApp_hasAlphaAndDescription_andDoesNotLaunch() {
        val launches = mutableListOf<String>()
        host(count = 1, installed = false, onLaunch = { launches.add(it) })
        val icon = rule.onNodeWithTag("home_missing_p1")
            .assertContentDescriptionEquals("名前1(アプリが見つかりません)").performClick()
        assertTrue(launches.isEmpty())
        val alpha = icon.fetchSemanticsNode().layoutInfo.getModifierInfo().mapNotNull {
            (it.modifier as? InspectableValue)?.takeIf { it.nameFallback == "graphicsLayer" }
                ?.inspectableElements?.firstOrNull { value -> value.name == "alpha" }?.value
        }
        assertEquals(listOf(0.4f), alpha)
    }
    @Test fun width400_shrinksIconsWith8dpGaps() {
        host(width = 400)
        for (i in 1..5) rule.onNodeWithTag("home_app_p$i").assertWidthIsEqualTo(73.6.dp)
        val a = rule.onNodeWithTag("home_app_p1").getBoundsInRoot()
        val b = rule.onNodeWithTag("home_app_p2").getBoundsInRoot()
        assertEquals(8f, b.left.value - a.right.value, 1f)
    }
    @Test fun width600_usesCalculated30dpGap() {
        host(width = 600)
        for (i in 1..5) rule.onNodeWithTag("home_app_p$i").assertWidthIsEqualTo(96.dp)
        val a = rule.onNodeWithTag("home_app_p1").getBoundsInRoot()
        val b = rule.onNodeWithTag("home_app_p2").getBoundsInRoot()
        assertEquals(30f, b.left.value - a.right.value, 0f)
    }
    @Test fun narrowRow_scrollsToLastWhole72dpIcon() {
        host(width = 300)
        val row = rule.onNodeWithTag("home_apps").getBoundsInRoot()
        for (i in 1..5) {
            val node = rule.onNodeWithTag("home_app_p$i").performScrollTo().assertIsDisplayed()
            node.assertWidthIsEqualTo(72.dp).assertHeightIsEqualTo(72.dp)
            val b = node.getBoundsInRoot()
            assertTrue(b.left >= row.left && b.right <= row.right)
        }
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
