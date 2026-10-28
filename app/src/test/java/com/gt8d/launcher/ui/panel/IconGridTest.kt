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
import androidx.compose.ui.test.performClick
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
@Config(sdk = [35])
class IconGridTest {

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
        rule.onNodeWithText("アプリが見つかりません").assertIsDisplayed()
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
