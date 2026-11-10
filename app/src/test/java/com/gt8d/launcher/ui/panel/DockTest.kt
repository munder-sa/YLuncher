package com.gt8d.launcher.ui.panel

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
    @Test fun dock_settingsAfterAppsBeforeHomeAndCallbackAndFallback() {
        var calls = 0
        rule.setContent {
            LauncherTheme {
                Dock(DockStatus(true, null), listOf(AppInfo("a", "A", null, true)), false,
                    {}, {}, {}, {}, {}, {}, AppInfo("settings", "設定", null, true), { calls++ })
            }
        }
        val app = rule.onNodeWithTag("dock_app_a").getBoundsInRoot()
        val settings = rule.onNodeWithTag("dock_settings").assertContentDescriptionEquals("設定")
            .assertIsDisplayed().getBoundsInRoot()
        val home = rule.onNodeWithTag("dock_home").getBoundsInRoot()
        assertTrue(app.bottom <= settings.top && settings.bottom <= home.top)
        assertTrue(settings.width >= 72.dp && settings.height >= 72.dp)
        val fallback = rule.onNodeWithTag("dock_settings_fallback", useUnmergedTree = true).getBoundsInRoot()
        assertEquals(32.dp, fallback.width)
        assertEquals(32.dp, fallback.height)
        rule.onNodeWithTag("dock_settings").performClick()
        assertEquals(1, calls)
    }

    @Test fun dock_settingsNullIsHidden() {
        rule.setContent { LauncherTheme { Dock(DockStatus(true, null), emptyList(), false,
            {}, {}, {}, {}, {}, {}) } }
        rule.onNodeWithTag("dock_settings").assertDoesNotExist()
    }

    @Test fun dock_setAdd_descriptionAndCallback() {
        var calls = 0
        rule.setContent {
            LauncherTheme {
                Dock(DockStatus(true, null), emptyList(), false, {}, { calls++ }, {}, {}, {}, {})
            }
        }
        rule.onNodeWithTag("dock_split").assertContentDescriptionEquals("セットを追加").performClick()
        assertEquals(1, calls)
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

    private fun hostStatus(status: DockStatus) {
        rule.setContent {
            LauncherTheme { Dock(status, emptyList(), false, {}, {}, {}, {}, {}, {}) }
        }
    }

    @Test fun dock_statusCard_hasFixedSizeAndGap() {
        hostStatus(DockStatus(true, true, cell = CellIndicator.Signal(3, "5G")))
        val status = rule.onNodeWithTag("dock_status").assertIsDisplayed().getBoundsInRoot()
        val apps = rule.onNodeWithTag("dock_apps").getBoundsInRoot()
        assertEquals(88.dp, status.width)
        assertEquals(80.dp, status.height)
        assertEquals(8.dp, apps.top - status.bottom)
    }

    @Test fun dock_cellSignal_hasBarsDescriptionLabelAndSize() {
        hostStatus(DockStatus(true, true, cell = CellIndicator.Signal(3, "5G")))
        val cell = rule.onNodeWithTag("dock_cell").assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(CellBars, 3))
            .assertContentDescriptionEquals("モバイル通信 強さ 3/4 5G").getBoundsInRoot()
        rule.onNodeWithTag("dock_cell_label").assertTextEquals("5G")
        assertEquals(30.dp, cell.width)
        assertEquals(30.dp, cell.height)
    }

    @Test fun dock_cellOutOfService_hasNoLabel() {
        hostStatus(DockStatus(true, null, cell = CellIndicator.Signal(0, null)))
        rule.onNodeWithTag("dock_cell").assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(CellBars, 0))
            .assertContentDescriptionEquals("モバイル通信 圏外")
        rule.onNodeWithTag("dock_cell_label").assertDoesNotExist()
    }

    @Test fun dock_cellNoSim_hasDescriptionAndSentinelBars() {
        hostStatus(DockStatus(true, null, cell = CellIndicator.NoSim))
        rule.onNodeWithTag("dock_cell").assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(CellBars, -1))
            .assertContentDescriptionEquals("SIM なし")
        rule.onNodeWithTag("dock_cell_label").assertDoesNotExist()
    }

    @Test fun dock_cellAirplane_hasDescriptionAndSentinelBars() {
        hostStatus(DockStatus(true, null, cell = CellIndicator.Airplane))
        rule.onNodeWithTag("dock_cell").assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(CellBars, -1))
            .assertContentDescriptionEquals("機内モード")
        rule.onNodeWithTag("dock_cell_label").assertDoesNotExist()
    }

    @Test fun dock_cellUnavailable_hidesRowAndCentersWifi() {
        hostStatus(DockStatus(true, null))
        rule.onNodeWithTag("dock_cell_row").assertDoesNotExist()
        val wifi = rule.onNodeWithTag("dock_wifi").assertIsDisplayed().getBoundsInRoot()
        val status = rule.onNodeWithTag("dock_status").getBoundsInRoot()
        assertEquals((status.top + status.bottom) / 2, (wifi.top + wifi.bottom) / 2)
    }

    @Test fun dock_wifiAndBluetooth_areThirtyDpBelowCell() {
        hostStatus(DockStatus(true, true, cell = CellIndicator.Signal(3, "5G")))
        val cell = rule.onNodeWithTag("dock_cell").getBoundsInRoot()
        val wifi = rule.onNodeWithTag("dock_wifi").assertIsDisplayed().getBoundsInRoot()
        val bt = rule.onNodeWithTag("dock_bt").assertIsDisplayed().getBoundsInRoot()
        for (bounds in listOf(wifi, bt)) {
            assertEquals(30.dp, bounds.width)
            assertEquals(30.dp, bounds.height)
            assertTrue(bounds.top > cell.bottom)
        }
        assertEquals(8.dp, bt.left - wifi.right)
    }

    @Test fun dock_shortParent_scrollsAppsWhileStatusStaysVisible() {
        rule.setContent {
            LauncherTheme {
                Box(Modifier.width(88.dp).height(400.dp).testTag("dock_test_parent")) {
                    Dock(DockStatus(true, true, cell = CellIndicator.Signal(3, "5G")),
                        (1..4).map { AppInfo("a$it", "App $it", null, true) }, true,
                        {}, {}, {}, {}, {}, {}, settingsApp = AppInfo("settings", "設定", null, true))
                }
            }
        }
        val parent = rule.onNodeWithTag("dock_test_parent").getBoundsInRoot()
        val before = rule.onNodeWithTag("dock_status").assertIsDisplayed().getBoundsInRoot()
        assertEquals(80.dp, before.height)
        assertEquals(88.dp, before.width)
        assertTrue(before.top >= parent.top && before.bottom <= parent.bottom)
        val scroll = rule.onAllNodes(hasScrollAction() and hasAnyAncestor(hasTestTag("dock_apps")),
            useUnmergedTree = true).fetchSemanticsNodes().single()
            .config[SemanticsProperties.VerticalScrollAxisRange]
        assertEquals(0f, scroll.value(), 0f)
        assertTrue(scroll.maxValue() > 0f)
        rule.onNodeWithTag("dock_applist").performScrollTo().assertIsDisplayed()
        assertTrue(scroll.value() > 0f)
        val after = rule.onNodeWithTag("dock_status").assertIsDisplayed().getBoundsInRoot()
        assertEquals(before, after)
        val apps = rule.onNodeWithTag("dock_apps").getBoundsInRoot()
        assertEquals(8.dp, apps.top - after.bottom)
        val appList = rule.onNodeWithTag("dock_applist").getBoundsInRoot()
        assertTrue(appList.top >= apps.top && appList.bottom <= apps.bottom)
    }

    @Test
    fun dock_wifiReflectsStrengthAndConnection() {
        var status by mutableStateOf(DockStatus(true, null))
        rule.setContent {
            LauncherTheme {
                Dock(status, emptyList(), false, {}, {}, {}, {}, {}, {})
            }
        }
        fun check(bars: Int, description: String) {
            rule.onNodeWithTag("dock_wifi")
                .assert(SemanticsMatcher.expectValue(WifiBars, bars))
                .assertContentDescriptionEquals(description)
        }
        check(4, "Wi-Fi 強さ 4/4")
        rule.runOnIdle { status = DockStatus(true, null, WifiIndicator.Connected(3)) }
        check(3, "Wi-Fi 強さ 3/4")
        rule.runOnIdle { status = DockStatus(true, null, WifiIndicator.Disconnected) }
        check(-1, "Wi-Fi 未接続")
        rule.runOnIdle { status = DockStatus(false, null) }
        check(-1, "Wi-Fi オフ")
    }

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
