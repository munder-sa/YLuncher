package com.gt8d.launcher.ui.apps

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
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
    private val sample = listOf(AppListEntry("a", "A", "Alpha"), AppListEntry("b", "B", "Beta"))
    private lateinit var activity: ComponentActivity
    private val toggles = mutableListOf<String>()
    private val launched = mutableListOf<AppListEntry>()
    private var closed = 0

    private fun hostRegistered(pkgs: Set<String> = setOf("a"), entries: List<AppListEntry> = sample) {
        val selected = mutableStateOf(pkgs)
        rule.setContent {
            activity = LocalContext.current as ComponentActivity
            LauncherTheme {
                AppListScreen(entries, { null }, { launched += it }, { closed++ },
                    registered = selected.value, onOpenSettings = {}, onToggle = { pkg ->
                        toggles += pkg
                        selected.value = toggleRegistered(selected.value.toList(), pkg).toSet()
                    })
            }
        }
    }

    private fun back() = rule.runOnIdle { activity.onBackPressedDispatcher.onBackPressed() }

    @Test fun registered_defaultTileAllAndBothBackPaths() {
        hostRegistered()
        rule.onNodeWithTag("applist_a").assertIsDisplayed()
        rule.onNodeWithTag("applist_b").assertDoesNotExist()
        rule.onNodeWithTag("applist_all").assertIsDisplayed().performClick()
        rule.onNodeWithTag("applist_a").assertIsDisplayed()
        rule.onNodeWithTag("applist_b").assertIsDisplayed()
        rule.onNodeWithTag("applist_back").performClick()
        rule.onNodeWithTag("applist_b").assertDoesNotExist()
        rule.onNodeWithTag("applist_all").performClick()
        back()
        rule.onNodeWithTag("applist_all").assertIsDisplayed()
        rule.onNodeWithTag("applist_b").assertDoesNotExist()
        back()
        assertEquals(1, closed)
    }

    @Test fun editing_togglePackageWithoutLaunchAndDoneReturnsToEntryState() {
        hostRegistered(entries = sample + AppListEntry("b", "Other", "Beta other"))
        rule.onNodeWithTag("applist_edit").performClick()
        rule.onAllNodesWithTag("applist_check_a", useUnmergedTree = true).assertCountEquals(1)
        rule.onAllNodesWithTag("applist_check_a", useUnmergedTree = true)[0].assertIsSelected()
        rule.onAllNodesWithTag("applist_check_b", useUnmergedTree = true).assertCountEquals(2)
        rule.onAllNodesWithTag("applist_check_b", useUnmergedTree = true).fetchSemanticsNodes().indices.forEach {
            rule.onAllNodesWithTag("applist_check_b", useUnmergedTree = true)[it].assertIsNotSelected()
        }
        rule.onAllNodesWithTag("applist_b")[0].performClick()
        rule.onAllNodesWithTag("applist_check_b", useUnmergedTree = true).fetchSemanticsNodes().indices.forEach {
            rule.onAllNodesWithTag("applist_check_b", useUnmergedTree = true)[it].assertIsSelected()
        }
        assertEquals(listOf("b"), toggles)
        assertTrue(launched.isEmpty())
        rule.onNodeWithTag("applist_done").performClick()
        rule.onNodeWithTag("applist_all").assertIsDisplayed().performClick()
        rule.onNodeWithTag("applist_edit").performClick()
        rule.onNodeWithTag("applist_done").performClick()
        rule.onNodeWithTag("applist_back").assertIsDisplayed()
        rule.onNodeWithTag("applist_edit").performClick()
        back()
        rule.onNodeWithTag("applist_back").assertIsDisplayed()
        rule.onNodeWithTag("applist_back").performClick()
        rule.onNodeWithTag("applist_edit").performClick()
        back()
        rule.onNodeWithTag("applist_all").assertIsDisplayed()
    }

    @Test fun emptyRegistration_guidanceAndAllLaunch() {
        hostRegistered(emptySet())
        rule.onNodeWithText("登録したアプリはありません。「編集」で登録できます").assertIsDisplayed()
        rule.onNodeWithTag("applist_a").assertDoesNotExist()
        rule.onNodeWithTag("applist_all").performClick()
        rule.onNodeWithTag("applist_b").performClick()
        assertEquals(listOf(sample[1]), launched)
        assertTrue(toggles.isEmpty())
    }

    private fun checkHeader(width: Int) {
        RuntimeEnvironment.setQualifiers("w${width}dp-h726dp")
        hostRegistered()
        fun verify(title: String, tags: List<String>) {
            val header = rule.onNodeWithTag("applist_header").getBoundsInRoot()
            val heading = rule.onNodeWithText(title)
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            heading.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(1, layouts.single().lineCount)
            val titleBounds = heading.getBoundsInRoot()
            assertTrue(titleBounds.left >= header.left && titleBounds.right <= header.right)
            val bounds = tags.map {
                rule.onNodeWithTag(it).assertIsDisplayed().assertWidthIsAtLeast(72.dp)
                    .assertHeightIsAtLeast(72.dp).getBoundsInRoot()
            }
            bounds.forEach {
                assertTrue(it.left >= header.left && it.right <= header.right)
                assertTrue(it.top >= header.top && it.bottom <= header.bottom)
                assertTrue(it.right <= titleBounds.left || titleBounds.right <= it.left)
            }
            bounds.forEachIndexed { i, a -> bounds.drop(i + 1).forEach { b ->
                assertTrue(a.right <= b.left || b.right <= a.left)
            } }
        }
        verify("アプリ一覧", listOf("applist_edit", "applist_settings", "applist_close"))
        rule.onNodeWithTag("applist_all").performClick()
        verify("すべてのアプリ", listOf("applist_back", "applist_edit", "applist_settings", "applist_close"))
        rule.onNodeWithTag("applist_edit").performClick()
        verify("すべてのアプリ — 押して登録・外す", listOf("applist_done"))
    }

    @Test fun header360_buttonsFitAndMeetMinTouch() = checkHeader(360)
    @Test fun header1291_buttonsFitAndMeetMinTouch() = checkHeader(1291)

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun editing_unregisteredIconHalfOpacityCheckRemainsWhite() {
        val bitmap = ImageBitmap(56, 56)
        Canvas(bitmap).drawRect(Rect(0f, 0f, 56f, 56f), Paint().apply { color = Color.White })
        rule.setContent {
            activity = LocalContext.current as ComponentActivity
            LauncherTheme { AppListScreen(sample, { bitmap }, {}, {}, registered = setOf("a")) }
        }
        rule.onNodeWithTag("applist_edit").performClick()
        val rendered = rule.runOnIdle {
            val content = activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
            android.graphics.Bitmap.createBitmap(content.width, content.height, android.graphics.Bitmap.Config.ARGB_8888).also {
                content.draw(android.graphics.Canvas(it))
            }
        }
        fun red(x: Int, y: Int) = android.graphics.Color.red(rendered.getPixel(x, y)) / 255f
        fun centerRed(tag: String): Float {
            val bounds = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            return red(bounds.center.x.toInt(), bounds.center.y.toInt())
        }
        val background = com.gt8d.launcher.ui.theme.Palette.Background0.red
        assertEquals(1f, centerRed("applist_icon_a"), 0.01f)
        assertEquals((1f + background) / 2f, centerRed("applist_icon_b"), 0.01f)
        val check = rule.onAllNodesWithTag("applist_check_b", useUnmergedTree = true)[0].fetchSemanticsNode().boundsInRoot
        assertEquals(1f, red(check.center.x.toInt(), check.top.toInt() + 1), 0.01f)
    }

    @Test fun editing1291_longLabelsCellsAndChecksDoNotOverlap() {
        RuntimeEnvironment.setQualifiers("w1291dp-h726dp")
        val entries = List(18) { AppListEntry("p$it", "Main", "とても長いアプリの名前を表示するテスト $it") }
        hostRegistered(entries = entries)
        rule.onNodeWithTag("applist_edit").performClick()
        val cells = entries.map { rule.onNodeWithTag("applist_${it.pkg}").assertIsDisplayed().getBoundsInRoot() }
        cells.forEachIndexed { i, a ->
            cells.drop(i + 1).forEach { b ->
                assertTrue(a.right <= b.left || b.right <= a.left || a.bottom <= b.top || b.bottom <= a.top)
            }
            val check = rule.onAllNodesWithTag("applist_check_p$i", useUnmergedTree = true)[0]
                .assertWidthIsEqualTo(24.dp).assertHeightIsEqualTo(24.dp).getBoundsInRoot()
            assertTrue(check.left >= a.left && check.right <= a.right && check.top >= a.top && check.bottom <= a.bottom)
        }
    }
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
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(entries.size)
        rule.onNodeWithTag("applist_all").performClick()
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
    fun appList_excludesStockLauncherAndSplitAppIncludingOtherActivities() {
        val youtube = AppListEntry(
            "com.google.android.youtube",
            "com.google.android.youtube.HomeActivity",
            "YouTube",
        )
        val result1 = buildAppList(listOf(youtube), ownPkg)
        assertEquals(listOf(youtube), result1)

        val launcher3Other = AppListEntry(
            "com.android.launcher3",
            "com.android.launcher3.Launcher",
            "Quickstep",
        )
        val hidden = com.gt8d.launcher.apps.HiddenApps.packages.flatMap {
            listOf(AppListEntry(it, "Main", "Hidden"), AppListEntry(it, "Other", "Other"))
        }
        val result2 = buildAppList(listOf(youtube, STOCK_LAUNCHER_ENTRY, launcher3Other) + hidden, ownPkg)
        assertEquals(listOf(youtube), result2)
        hostRegistered(com.gt8d.launcher.apps.HiddenApps.packages + youtube.pkg, result2)
        fun verify() {
            com.gt8d.launcher.apps.HiddenApps.packages.forEach {
                rule.onNodeWithTag("applist_$it").assertDoesNotExist()
            }
            rule.onNodeWithTag("applist_${youtube.pkg}").assertIsDisplayed()
        }
        verify()
        rule.onNodeWithTag("applist_all").performClick()
        verify()
        rule.onNodeWithTag("applist_edit").performClick()
        verify()
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
            listOf("Apple Music", "chrome", "YouTube"),
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
        assertEquals(listOf(youtube), result)
        assertTrue(result.none { it.pkg == "com.gt8d.launcher" })
    }
}
