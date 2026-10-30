package com.gt8d.launcher.ui.panel

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.core.app.ApplicationProvider
import com.gt8d.launcher.media.NowPlaying
import com.gt8d.launcher.ui.theme.LauncherTheme
import org.junit.Assert.assertTrue
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
class PanelContentTest {

    @Test
    fun panel_clockShownWhileIdleAndPlaying() {
        var now by mutableStateOf<NowPlaying?>(null)
        rule.setContent {
            LauncherTheme {
                PanelContent(
                    PanelState(now, null, 0L, emptyList(), emptyList(), DockStatus(true, null), false, false),
                    emptyActions(),
                )
            }
        }
        rule.onNodeWithTag("clock").assertIsDisplayed()
        rule.runOnIdle {
            now = NowPlaying("music", "曲名", "歌手", null, 180000L, true, true)
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        }
        rule.onNodeWithTag("clock").assertIsDisplayed()
    }

    @Test
    fun panel_narrowShortWithFavorite_allControlsReachable() {
        val events = mutableListOf<String>()
        val seeks = mutableListOf<Long>()
        rule.setContent {
            LauncherTheme {
                Box(Modifier.size(516.dp, 400.dp)) {
                    PanelContent(
                        PanelState(
                            NowPlaying("music", "Song", "Artist", null, 200000L, true, true),
                            null, 50000L,
                            (1..5).map { AppInfo("panel$it", "Panel $it", null, true) },
                            (1..4).map { AppInfo("dock$it", "Dock $it", null, true) },
                            DockStatus(true, true), true, true,
                        ),
                        PanelActions(
                            onMediaAction = { events.add(it.name) },
                            onSeek = { seeks.add(it) }, onLaunch = { events.add(it) },
                            onAdd = { events.add("add") }, onHome = { events.add("home") },
                            onSplit = { events.add("split") }, onCar = { events.add("car") },
                            onAppList = { events.add("applist") }, onAppListLongPress = {},
                            onOpenPermissions = { events.add("permission") },
                        ),
                    )
                }
            }
        }
        val targets = listOf(
            "permission_banner" to "permission", "progress" to "seek",
            "btn_prev" to "Prev", "btn_playpause" to "Pause", "btn_next" to "Next",
            "btn_favorite" to "Favorite",
        ) + (1..5).map { "icon_panel$it" to "panel$it" } +
            listOf("icon_add" to "add") + (1..4).map { "dock_app_dock$it" to "dock$it" } +
            listOf("dock_home" to "home", "dock_split" to "split", "dock_car" to "car", "dock_applist" to "applist")
        for ((tag, expected) in targets) {
            val node = rule.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
            val bounds = node.getBoundsInRoot()
            assertTrue("$tag width", bounds.width >= 72.dp)
            assertTrue("$tag height: ${bounds.height}; bounds: $bounds", bounds.height >= 72.dp)
            assertTrue("$tag left", bounds.left >= 0.dp)
            assertTrue("$tag right", bounds.right <= 516.dp)
            if (tag == "progress") {
                node.performTouchInput {
                    down(center); moveTo(Offset(width - 1f, centerY))
                }
                rule.runOnIdle { assertEquals(emptyList<Long>(), seeks) }
                node.performTouchInput { up() }
                rule.runOnIdle { assertEquals(listOf(200000L), seeks) }
            } else {
                val count = events.size
                node.performTouchInput { down(center); up() }
                rule.runOnIdle {
                    assertEquals(count + 1, events.size)
                    assertEquals(expected, events.last())
                }
            }
        }
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

    private fun emptyActions(
        onOpenPermissions: () -> Unit = {},
    ) = PanelActions(
        onMediaAction = {},
        onSeek = {},
        onLaunch = {},
        onAdd = {},
        onHome = {},
        onSplit = {},
        onCar = {},
        onAppList = {},
        onAppListLongPress = {},
        onOpenPermissions = onOpenPermissions,
    )

    @Test
    fun panel_needsPermission_showsBanner() {
        var needsPermission by mutableStateOf(true)
        var permissionsOpened = false

        rule.setContent {
            LauncherTheme {
                Box(modifier = Modifier.size(576.dp, 800.dp)) {
                    PanelContent(
                        state = PanelState(
                            now = null,
                            idleApp = null,
                            positionMs = 0L,
                            panelApps = emptyList(),
                            dockApps = emptyList(),
                            dockStatus = DockStatus(wifiOn = true, bluetoothOn = null),
                            showCar = false,
                            needsPermission = needsPermission,
                        ),
                        actions = emptyActions(
                            onOpenPermissions = { permissionsOpened = true },
                        ),
                    )
                }
            }
        }

        rule.onNodeWithTag("permission_banner").assertIsDisplayed()
        rule.onNodeWithText("許可が必要").assertIsDisplayed()
        val bannerBounds = rule.onNodeWithTag("permission_banner").getBoundsInRoot()
        assertTrue("permission_banner width >= 72.dp", bannerBounds.width >= 72.dp)
        assertTrue("permission_banner height >= 72.dp", bannerBounds.height >= 72.dp)
        rule.onNodeWithTag("permission_banner").performClick()
        assertTrue(permissionsOpened)

        needsPermission = false
        rule.waitForIdle()
        rule.onNodeWithTag("permission_banner").assertDoesNotExist()
    }

    @Test
    fun panel_narrowWidth_noOverflow() {
        val panelApps = (1..5).map { i ->
            AppInfo(
                pkg = "com.example.panel$i",
                label = "Panel $i",
                icon = null,
                installed = true,
            )
        }
        val dockApps = (1..4).map { i ->
            AppInfo(
                pkg = "com.example.dock$i",
                label = "Dock $i",
                icon = null,
                installed = true,
            )
        }
        rule.setContent {
            LauncherTheme {
                Box(modifier = Modifier.size(516.dp, 800.dp)) {
                    PanelContent(
                        state = PanelState(
                            now = NowPlaying(
                                pkg = "com.apple.android.music",
                                title = "Narrow Width Song",
                                artist = "Artist",
                                artwork = null,
                                durationMs = 200_000L,
                                playing = true,
                                hasFavorite = false,
                            ),
                            idleApp = null,
                            positionMs = 50_000L,
                            panelApps = panelApps,
                            dockApps = dockApps,
                            dockStatus = DockStatus(wifiOn = true, bluetoothOn = true),
                            showCar = false,
                            needsPermission = false,
                        ),
                        actions = emptyActions(),
                    )
                }
            }
        }

        val requiredTags = listOf(
            "dock_home",
            "dock_split",
            "dock_applist",
            "icon_add",
            "btn_prev",
            "btn_playpause",
            "btn_next",
        )
        for (tag in requiredTags) {
            val node = rule.onNodeWithTag(tag)
            node.assertIsDisplayed()
            val bounds = node.getBoundsInRoot()
            assertTrue("$tag width (${bounds.width}) < 72.dp", bounds.width >= 72.dp)
            assertTrue("$tag height (${bounds.height}) < 72.dp", bounds.height >= 72.dp)
            assertTrue("$tag right (${bounds.right}) > 516.dp", bounds.right <= 516.dp)
        }
    }

    @Test
    fun panel_limitsPanelAppsTo5() {
        val panelApps = (1..6).map { i ->
            AppInfo(
                pkg = "com.example.panel$i",
                label = "Panel $i",
                icon = null,
                installed = true,
            )
        }
        rule.setContent {
            LauncherTheme {
                Box(modifier = Modifier.size(576.dp, 800.dp)) {
                    PanelContent(
                        state = PanelState(
                            now = null,
                            idleApp = null,
                            positionMs = 0L,
                            panelApps = panelApps,
                            dockApps = emptyList(),
                            dockStatus = DockStatus(wifiOn = true, bluetoothOn = null),
                            showCar = false,
                            needsPermission = false,
                        ),
                        actions = emptyActions(),
                    )
                }
            }
        }

        for (i in 1..5) {
            rule.onNodeWithTag("icon_com.example.panel$i").assertIsDisplayed()
        }
        rule.onNodeWithTag("icon_com.example.panel6").assertDoesNotExist()
        rule.onNodeWithTag("icon_add").assertIsDisplayed()
    }
}
