package com.gt8d.launcher.ui.apps

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.gt8d.launcher.ui.theme.LauncherTheme
import org.junit.Rule
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppListScreenTest {
    @get:Rule(order = 0) val register = object : ExternalResource() {
        override fun before() {
            RuntimeEnvironment.setQualifiers("w516dp-h400dp")
            val app = ApplicationProvider.getApplicationContext<Application>()
            val cn = ComponentName(app.packageName, ComponentActivity::class.java.name)
            Shadows.shadowOf(app.packageManager).apply {
                addOrUpdateActivity(ActivityInfo().apply { name = cn.className; packageName = cn.packageName })
                addIntentFilterForActivity(cn, IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
            }
        }
    }
    @get:Rule(order = 1) val rule = createComposeRule()

    @Test
    fun appList_iconRevisionChange_reloadsIconsKeepsScroll() {
        val revision = mutableIntStateOf(0)
        val calls = mutableMapOf<String, Int>()
        val entries = List(150) { AppListEntry("pkg$it", "Main", "App $it") }
        lateinit var state: androidx.compose.foundation.lazy.grid.LazyGridState
        rule.setContent {
            state = rememberLazyGridState()
            LauncherTheme {
                AppListScreen(
                    entries = entries,
                    iconFor = { pkg -> calls[pkg] = (calls[pkg] ?: 0) + 1; null },
                    onLaunch = {}, onClose = {}, gridState = state,
                    iconRevision = revision.intValue,
                )
            }
        }
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(90)
        rule.waitForIdle()
        val before = rule.runOnIdle {
            Triple(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset, calls.toMap())
        }
        rule.runOnIdle { revision.intValue++ }
        rule.runOnIdle {
            val visible = state.layoutInfo.visibleItemsInfo
            assertTrue(visible.isNotEmpty())
            visible.forEach { item ->
                val pkg = entries[item.index].pkg
                assertTrue("$pkg icon must reload", (calls[pkg] ?: 0) > (before.third[pkg] ?: 0))
            }
            assertTrue(before.first > 0)
            assertEquals(before.first, state.firstVisibleItemIndex)
            assertEquals(before.second, state.firstVisibleItemScrollOffset)
        }
    }
    private val ownPkg = "com.gt8d.launcher"

    @Test fun appList_settingsButton_minTouchAndCallback() {
        var calls = 0
        rule.setContent {
            LauncherTheme {
                AppListScreen(emptyList(), { null }, {}, {}, onOpenSettings = { calls++ })
            }
        }
        rule.onNodeWithTag("applist_settings").assertIsDisplayed()
            .assertWidthIsAtLeast(androidx.compose.ui.unit.Dp(72f))
            .assertHeightIsAtLeast(androidx.compose.ui.unit.Dp(72f)).performClick()
        assertEquals(1, calls)
    }

    @Test fun appList_withoutSettingsCallback_hasNoSettingsButton() {
        rule.setContent { LauncherTheme { AppListScreen(emptyList(), { null }, {}, {}) } }
        rule.onNodeWithTag("applist_settings").assertDoesNotExist()
    }

    @Test
    fun appList_alwaysIncludesStockLauncher() {
        val youtube = AppListEntry(
            "com.google.android.youtube",
            "com.google.android.youtube.HomeActivity",
            "YouTube",
        )
        val result1 = buildAppList(listOf(youtube), ownPkg)
        assertEquals(2, result1.size)
        assertEquals(STOCK_LAUNCHER_ENTRY, result1.first())

        val launcher3Other = AppListEntry(
            "com.android.launcher3",
            "com.android.launcher3.Launcher",
            "Quickstep",
        )
        val result2 = buildAppList(listOf(youtube, launcher3Other), ownPkg)
        val launcher3Items = result2.filter { it.pkg == "com.android.launcher3" }
        assertEquals(listOf(STOCK_LAUNCHER_ENTRY), launcher3Items)
    }

    @Test
    fun appList_sortedByLabel() {
        val entries = listOf(
            AppListEntry("y", "y.Main", "YouTube"),
            AppListEntry("c", "c.Main", "chrome"),
            AppListEntry("a", "a.Main", "Apple Music"),
        )
        val result = buildAppList(entries, ownPkg)
        assertEquals(
            listOf("純正ランチャー", "Apple Music", "chrome", "YouTube"),
            result.map { it.label },
        )
    }

    @Test
    fun appList_excludesOwnPanel() {
        val ownPanel = AppListEntry(
            "com.gt8d.launcher",
            "com.gt8d.launcher.ui.panel.PanelActivity",
            "GT8D パネル",
        )
        val youtube = AppListEntry(
            "com.google.android.youtube",
            "com.google.android.youtube.HomeActivity",
            "YouTube",
        )
        val result = buildAppList(listOf(ownPanel, youtube), ownPkg)
        assertEquals(2, result.size)
        assertTrue(result.none { it.pkg == "com.gt8d.launcher" })
    }
}
