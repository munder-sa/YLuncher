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
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.core.app.ApplicationProvider
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DockTest {

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

    @Test
    fun dock_hasButtonsInOrder() {
        var showCar by mutableStateOf(true)
        rule.setContent {
            LauncherTheme {
                Dock(
                    status = DockStatus(wifiOn = true, bluetoothOn = true),
                    dockApps = emptyList(),
                    showCar = showCar,
                    onHome = {},
                    onSplit = {},
                    onCar = {},
                    onAppList = {},
                    onAppListLongPress = {},
                    onLaunch = {},
                )
            }
        }

        val homeTop = rule.onNodeWithTag("dock_home").getBoundsInRoot().top
        val splitTop = rule.onNodeWithTag("dock_split").getBoundsInRoot().top
        val carTop = rule.onNodeWithTag("dock_car").getBoundsInRoot().top
        val appListTop = rule.onNodeWithTag("dock_applist").getBoundsInRoot().top

        assertTrue("home < split ($homeTop vs $splitTop)", homeTop < splitTop)
        assertTrue("split < car ($splitTop vs $carTop)", splitTop < carTop)
        assertTrue("car < applist ($carTop vs $appListTop)", carTop < appListTop)

        showCar = false
        rule.waitForIdle()
        rule.onNodeWithTag("dock_car").assertDoesNotExist()
    }

    @Test
    fun dock_status_hidesBluetoothWithoutPermission() {
        var btStatus by mutableStateOf<Boolean?>(null)
        rule.setContent {
            LauncherTheme {
                Dock(
                    status = DockStatus(wifiOn = true, bluetoothOn = btStatus),
                    dockApps = emptyList(),
                    showCar = false,
                    onHome = {},
                    onSplit = {},
                    onCar = {},
                    onAppList = {},
                    onAppListLongPress = {},
                    onLaunch = {},
                )
            }
        }

        rule.onNodeWithTag("dock_wifi").assertIsDisplayed()
        rule.onNodeWithTag("dock_bt").assertDoesNotExist()

        btStatus = true
        rule.waitForIdle()
        rule.onNodeWithTag("dock_bt").assertIsDisplayed()

        btStatus = false
        rule.waitForIdle()
        rule.onNodeWithTag("dock_bt").assertIsDisplayed()
    }

    @Test
    fun dock_limitsAppsTo4() {
        val apps = (1..5).map { i ->
            AppInfo(
                pkg = "com.example.app$i",
                label = "App $i",
                icon = null,
                installed = true,
            )
        }
        var launched: String? = null
        rule.setContent {
            LauncherTheme {
                Dock(
                    status = DockStatus(wifiOn = true, bluetoothOn = null),
                    dockApps = apps,
                    showCar = false,
                    onHome = {},
                    onSplit = {},
                    onCar = {},
                    onAppList = {},
                    onAppListLongPress = {},
                    onLaunch = { launched = it },
                )
            }
        }

        for (i in 1..4) {
            rule.onNodeWithTag("dock_app_com.example.app$i").assertIsDisplayed()
        }
        rule.onNodeWithTag("dock_app_com.example.app5").assertDoesNotExist()

        rule.onNodeWithTag("dock_app_com.example.app1").performClick()
        assertEquals("com.example.app1", launched)
    }

    @Test
    fun dock_appList_tapAndLongPress() {
        var tapCount = 0
        var longPressCount = 0
        rule.setContent {
            LauncherTheme {
                Dock(
                    status = DockStatus(wifiOn = true, bluetoothOn = null),
                    dockApps = emptyList(),
                    showCar = false,
                    onHome = {},
                    onSplit = {},
                    onCar = {},
                    onAppList = { tapCount++ },
                    onAppListLongPress = { longPressCount++ },
                    onLaunch = {},
                )
            }
        }

        rule.onNodeWithTag("dock_applist").performClick()
        assertEquals(1, tapCount)
        assertEquals(0, longPressCount)

        rule.onNodeWithTag("dock_applist").performTouchInput {
            longClick()
        }
        rule.waitForIdle()
        assertEquals(1, tapCount)
        assertEquals(1, longPressCount)
    }

    @Test
    fun dock_buttons_atLeast72dp() {
        rule.setContent {
            LauncherTheme {
                Dock(
                    status = DockStatus(wifiOn = true, bluetoothOn = true),
                    dockApps = emptyList(),
                    showCar = true,
                    onHome = {},
                    onSplit = {},
                    onCar = {},
                    onAppList = {},
                    onAppListLongPress = {},
                    onLaunch = {},
                )
            }
        }

        listOf("dock_home", "dock_split", "dock_car", "dock_applist").forEach { tag ->
            val bounds = rule.onNodeWithTag(tag).getBoundsInRoot()
            assertTrue("$tag width ${bounds.width} < 72.dp", bounds.width >= 72.dp)
            assertTrue("$tag height ${bounds.height} < 72.dp", bounds.height >= 72.dp)
        }
    }
}
