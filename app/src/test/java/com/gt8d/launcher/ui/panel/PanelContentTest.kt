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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.core.app.ApplicationProvider
import com.gt8d.launcher.media.NowPlaying
import com.gt8d.launcher.ui.theme.LauncherTheme
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
class PanelContentTest {

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
                Box(modifier = Modifier.size(576.dp, 800.dp)) {
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
            assertTrue("$tag right (${bounds.right}) > 576.dp", bounds.right <= 576.dp)
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
