package com.gt8d.launcher.ui.panel

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
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.platform.InspectableValue
import androidx.compose.ui.platform.isDebugInspectorInfoEnabled
import androidx.test.core.app.ApplicationProvider
import com.gt8d.launcher.ui.theme.LauncherTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w800dp-h800dp")
class IconGridTest {
    @org.junit.Before fun enableInspector() { isDebugInspectorInfoEnabled = true }
    @org.junit.After fun disableInspector() { isDebugInspectorInfoEnabled = false }
    @Test fun fiveAppsAndAdd_twoRowsWithoutPager() {
        rule.setContent { Box(Modifier.width(300.dp)) {
            IconGrid((1..5).map { AppInfo("p$it", "$it", null, true) }, onLaunch = {}, onAdd = {})
        } }
        rule.onNodeWithTag("icon_grid").assertHeightIsEqualTo(184.dp)
        rule.onNodeWithTag("icon_pager").assertDoesNotExist()
        rule.onNodeWithTag("icon_pager_dots").assertDoesNotExist()
        rule.onNodeWithTag("icon_add").assertIsDisplayed()
    }

    @Test fun tenApps_readingOrderSwipeCallbacksAndResize() {
        var width by mutableStateOf(300.dp)
        var page by mutableStateOf(0)
        val launches = mutableListOf<String>()
        var adds = 0
        rule.setContent { Box(Modifier.width(width)) {
            IconGrid((1..10).map { AppInfo("p$it", "$it", null, it != 10) },
                onLaunch = { launches.add(it) }, onAdd = { adds++ },
                page = page, onPageSettled = { page = it })
        } }
        rule.onNodeWithTag("icon_pager").assertHeightIsEqualTo(184.dp)
        rule.onNodeWithTag("icon_pager_dots").assertContentDescriptionEquals("ページ 1/2")
        val bounds = (1..6).map { rule.onNodeWithTag("icon_p$it").assertIsDisplayed().getBoundsInRoot() }
        for (i in 0..2) {
            assertEquals(bounds[0].top, bounds[i].top)
            assertEquals(bounds[3].top, bounds[i + 3].top)
            assertEquals(bounds[i].left, bounds[i + 3].left)
        }
        assertTrue(bounds[0].left < bounds[1].left && bounds[1].left < bounds[2].left)
        assertTrue(bounds[0].bottom < bounds[3].top)
        rule.onNodeWithTag("icon_pager").performTouchInput { swipeLeft() }
        rule.waitForIdle()
        assertEquals(1, page)
        rule.onNodeWithTag("icon_pager_dots").assertContentDescriptionEquals("ページ 2/2")
        for (i in 7..9) rule.onNodeWithTag("icon_p$i").assertIsDisplayed()
        rule.onNodeWithTag("icon_p9").performClick()
        rule.onNodeWithTag("missing_p10").assertIsDisplayed().performClick()
        val alpha = rule.onNodeWithTag("missing_p10").fetchSemanticsNode().layoutInfo.getModifierInfo()
            .mapNotNull { it.modifier as? InspectableValue }
            .filter { it.nameFallback == "graphicsLayer" }
            .flatMap { it.inspectableElements.toList() }.single { it.name == "alpha" }.value
        assertEquals(0.4f, alpha)
        assertEquals(listOf("p9"), launches)
        rule.onNodeWithTag("icon_add").assertIsDisplayed().performClick()
        assertEquals(1, adds)
        rule.onNodeWithTag("icon_pager").assertHeightIsEqualTo(184.dp)
        rule.runOnIdle { width = 600.dp }
        rule.waitForIdle()
        rule.onNodeWithTag("icon_pager").assertDoesNotExist()
        rule.onNodeWithTag("icon_pager_dots").assertDoesNotExist()
        rule.onNodeWithTag("icon_p1").assertIsDisplayed()
        rule.onNodeWithTag("icon_add").assertIsDisplayed()
        assertEquals(1, page)
        rule.runOnIdle { width = 300.dp }
        rule.waitForIdle()
        rule.onNodeWithTag("icon_pager_dots").assertContentDescriptionEquals("ページ 2/2")
    }

    @Test fun sevenCells_lastPageStillHasTwoRowsHeight() {
        rule.setContent { Box(Modifier.width(300.dp)) {
            IconGrid((1..6).map { AppInfo("p$it", "$it", null, true) }, onLaunch = {}, onAdd = {})
        } }
        rule.onNodeWithTag("icon_pager").assertHeightIsEqualTo(184.dp)
        rule.onNodeWithTag("icon_pager").performTouchInput { swipeLeft() }
        rule.waitForIdle()
        rule.onNodeWithTag("icon_add").assertIsDisplayed()
        rule.onNodeWithTag("icon_pager").assertHeightIsEqualTo(184.dp)
    }

    @get:Rule(order = 0)
    val registerActivityRule = object : ExternalResource() {
        override fun before() {
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

    @Test
    fun slot_missingApp_showsPlaceholder() {
        var launchedPkg: String? = null
        val apps = listOf(
            AppInfo(
                pkg = "com.missing.app",
                label = "Missing App",
                icon = null,
                installed = false,
            ),
        )
        rule.setContent {
            LauncherTheme {
                IconGrid(
                    apps = apps,
                    columns = 3,
                    onLaunch = { launchedPkg = it },
                    onAdd = null,
                )
            }
        }

        rule.onNodeWithTag("missing_com.missing.app").assertIsDisplayed()
        rule.onNodeWithContentDescription("Missing App(アプリが見つかりません)").assertIsDisplayed()
        rule.onNodeWithTag("missing_com.missing.app").performClick()
        assertNull(launchedPkg)
    }

    @Test
    fun grid_launchesInstalledApp() {
        var launchedPkg: String? = null
        val apps = listOf(
            AppInfo(
                pkg = "com.google.android.youtube",
                label = "YouTube",
                icon = null,
                installed = true,
            ),
        )
        rule.setContent {
            LauncherTheme {
                IconGrid(
                    apps = apps,
                    columns = 3,
                    onLaunch = { launchedPkg = it },
                    onAdd = null,
                )
            }
        }

        rule.onNodeWithTag("icon_com.google.android.youtube").performClick()
        assertEquals("com.google.android.youtube", launchedPkg)
    }

    @Test
    fun grid_addCell_shownOnlyWhenOnAdd() {
        var showAdd by mutableStateOf(false)
        var addClicked = false
        val apps = listOf(
            AppInfo(
                pkg = "com.google.android.youtube",
                label = "YouTube",
                icon = null,
                installed = true,
            ),
        )
        rule.setContent {
            LauncherTheme {
                IconGrid(
                    apps = apps,
                    columns = 3,
                    onLaunch = {},
                    onAdd = if (showAdd) ({ addClicked = true }) else null,
                )
            }
        }

        rule.onNodeWithTag("icon_add").assertDoesNotExist()

        showAdd = true
        rule.waitForIdle()
        rule.onNodeWithTag("icon_add").assertIsDisplayed()
        rule.onNodeWithTag("icon_add").performClick()
        assertTrue(addClicked)
    }
}
