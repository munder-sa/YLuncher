package com.gt8d.launcher.ui.layout

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.compose.ui.unit.height
import androidx.core.view.WindowCompat
import androidx.test.core.app.ApplicationProvider
import com.gt8d.launcher.AppGraph
import com.gt8d.launcher.config.PanelView
import com.gt8d.launcher.config.Defaults
import com.gt8d.launcher.media.NowPlaying
import com.gt8d.launcher.ui.home.HomeContent
import com.gt8d.launcher.ui.panel.*
import com.gt8d.launcher.ui.theme.LauncherTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.TextLayoutMode

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@TextLayoutMode(TextLayoutMode.Mode.REALISTIC)
class LayoutMatrixTest(
    private val width: Int, private val height: Int, private val scale: Float,
    private val home: Boolean, private val playing: Boolean,
    private val view: PanelView,
    private val leftPlacement: Boolean,
) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}x{1} scale={2} home={3} playing={4} view={5} left={6}")
        fun cases(): List<Array<Any>> = buildList {
            for (w in listOf(360, 387, 516, 645, 774))
                for (h in listOf(400, 600, 726)) for (s in listOf(1f, 1.3f))
                    for (p in listOf(false, true)) for (m in PanelView.entries)
                        for (left in listOf(false, true)) add(arrayOf(w, h, s, false, p, m, left))
            for (w in listOf(387, 516, 645, 774, 839, 840, 903, 1291))
                for (h in listOf(600, 726)) for (view in PanelView.entries)
                    add(arrayOf(w, h, 1f, true, false, view, false))
        }
    }
    @get:Rule val rule = createEmptyComposeRule()

    @Test fun layout_fitsAndAllControlsAreReachable() {
        RuntimeEnvironment.setQualifiers("w${width}dp-h${height}dp")
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        WindowCompat.setDecorFitsSystemWindows(controller.get().window, false)
        val apps = (1..5).map { AppInfo("app$it", "長いアプリケーション名 $it", null, true) }
        var state by mutableStateOf(PanelState(
            if (playing) NowPlaying("music", "長い曲名のテスト", "長いアーティスト名", null, 180000L, true, true) else null,
            AppInfo("idle", "Apple Music", null, true), 10000L, apps, apps.take(4),
            DockStatus(true, true), true, true, view = view, sets = Defaults.build(emptySet()).screenSets,
            placement = if (leftPlacement) com.gt8d.launcher.split.PanelPlacement.Left else null,
            homeEntries = apps.map { com.gt8d.launcher.ui.apps.AppListEntry(it.pkg, "${it.pkg}.Main", it.label) },
        ))
        val actions = PanelActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {})
        var pixelsPerDp = 0f
        try {
            controller.get().setContent {
                LauncherTheme {
                    val density = LocalDensity.current
                    pixelsPerDp = density.density
                    CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                        Box(Modifier.fillMaxSize().testTag("matrix_root")) {
                            if (home) HomeContent { wide ->
                                if (wide) HomeWideContent(state, actions, Defaults.build(emptySet()),
                                    { AppInfo(it, it, null, true) }, 0, {}, {})
                                else PanelContent(state, actions)
                            } else PanelContent(state, actions)
                        }
                    }
                }
            }
            controller.visible()
            val root = rule.onNodeWithTag("matrix_root").fetchSemanticsNode().boundsInRoot
            assertEquals(width * pixelsPerDp, root.width, 0.5f)
            assertEquals(height * pixelsPerDp, root.height, 0.5f)
            val clock = rule.onNodeWithTag("clock")
            assertEquals(1, clock.textLayout().lineCount)
            val clockLayout = clock.textLayout()
            assertFalse("size=${clockLayout.size} paragraph=${clockLayout.multiParagraph.width}x${clockLayout.multiParagraph.height} input=${clockLayout.layoutInput}", clockLayout.hasVisualOverflow)
            val cb = clock.fetchSemanticsNode().boundsInRoot
            assertTrue(cb.left >= root.left && cb.right <= root.right && cb.top >= root.top && cb.bottom <= root.bottom)
            if (!(home && width >= 840)) {
                rule.onNodeWithTag("panel_view_segment").assertIsDisplayed()
                for (tag in listOf("panel_view_apps", "panel_view_sets")) {
                    val tab = rule.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                    assertTrue(tab.width >= 72 * pixelsPerDp && tab.height >= 72 * pixelsPerDp)
                    assertTrue(tab.left >= root.left && tab.right <= root.right && tab.bottom <= root.bottom)
                    assertTrue(tab.top >= cb.bottom)
                }
                if (view == PanelView.Sets) {
                    rule.onNodeWithTag("media_card").assertDoesNotExist()
                    rule.onNodeWithTag("icon_grid").assertDoesNotExist()
                    rule.onNodeWithTag("panel_sets").assertExists()
                    rule.onNodeWithTag("home_sets").assertDoesNotExist()
                    val segment = rule.onNodeWithTag("panel_view_segment").fetchSemanticsNode().boundsInRoot
                    val dockBounds = rule.onNodeWithTag("dock").fetchSemanticsNode().boundsInRoot
                    val cardTags = state.sets.map { "panel_set_${it.id}" } + "panel_set_add"
                    val viewportHeight = rule.onNodeWithTag("panel_sets").fetchSemanticsNode().boundsInRoot.height
                    for (tag in cardTags) {
                        val card = rule.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                        val expectedHeight = if (tag == "panel_set_add") 72 * pixelsPerDp
                            else viewportHeight.coerceAtMost(104 * pixelsPerDp).coerceAtLeast(72 * pixelsPerDp)
                        assertEquals(expectedHeight, card.height, 0.5f)
                        assertTrue(card.left >= root.left && card.right <= root.right && card.top >= segment.bottom && card.bottom <= root.bottom)
                        assertTrue(card.right <= dockBounds.left || card.left >= dockBounds.right)
                        for (other in cardTags.filter { it != tag }) {
                            val b = rule.onNodeWithTag(other).fetchSemanticsNode().boundsInRoot
                            assertFalse(card.left < b.right && b.left < card.right && card.top < b.bottom && b.top < card.bottom)
                        }
                    }
                }
            } else rule.onNodeWithTag("panel_view_segment").assertDoesNotExist()
            if (home) {
                if (width < 840) {
                    if (view == PanelView.Apps) rule.onNodeWithTag("home_sets").assertDoesNotExist()
                }
                else {
                    rule.onNodeWithTag("home_sets").assertExists()
                    rule.onNodeWithTag("home_player").assertExists()
                    rule.onNodeWithTag("media_card").assertDoesNotExist()
                    rule.onNodeWithTag("icon_grid").assertDoesNotExist()
                }
            }
            val tags = if (home && width >= 840) listOf("clock", "home_sets", "home_apps", "home_player", "dock")
                else if (view == PanelView.Apps) listOf("clock", "panel_view_segment", "media_card", "icon_grid", "dock")
                else listOf("clock", "panel_view_segment", "panel_sets", "dock")
            fun checkDisjoint() {
                val bounds = tags.map { rule.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot }
                for (i in bounds.indices) for (j in i + 1 until bounds.size) {
                    val a = bounds[i]; val b = bounds[j]
                    assertFalse("${tags[i]} overlaps ${tags[j]}",
                        a.width > 0 && a.height > 0 && b.width > 0 && b.height > 0 &&
                            a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom)
                }
            }
            checkDisjoint()
            if (playing && view == PanelView.Apps && !(home && width >= 840)) {
                rule.onNodeWithTag("btn_playpause").performScrollTo()
                val controls = listOf("btn_prev", "btn_playpause", "btn_next").map {
                    rule.onNodeWithTag(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                }
                for (control in controls) {
                    assertEquals(controls.first().center.y, control.center.y, 1f)
                    assertTrue(control.width >= 72 * pixelsPerDp && control.height >= 72 * pixelsPerDp)
                    assertTrue(control.left >= root.left && control.right <= root.right &&
                        control.top >= root.top && control.bottom <= root.bottom)
                }
                assertTrue(controls[0].right <= controls[1].left && controls[1].right <= controls[2].left)
            }
            val dock = rule.onNodeWithTag("dock").fetchSemanticsNode().boundsInRoot
            assertTrue(dock.width >= 72 * pixelsPerDp)
            assertTrue(dock.left >= root.left && dock.right <= root.right && dock.top >= root.top && dock.bottom <= root.bottom)
            if (leftPlacement) {
                val segment = rule.onNodeWithTag("panel_view_segment").fetchSemanticsNode().boundsInRoot
                assertTrue(dock.right <= segment.left)
            }
            for (tag in if (home && width >= 840) emptyList() else if (view == PanelView.Apps) listOf("media_card", "icon_grid") else emptyList()) {
                rule.onNodeWithTag(tag).performScrollTo()
                checkDisjoint()
            }
            val clickCount = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().size
            for (index in 0 until clickCount) {
                val target = rule.onAllNodes(hasClickAction())[index]
                if (target.fetchSemanticsNode().config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { "" } in listOf("panel_view_apps", "panel_view_sets")) target.assertIsDisplayed()
                else if (home && width >= 840 && target.fetchSemanticsNode().config
                        .getOrElse(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { "" } in
                        listOf("player_idle_open", "permission_banner")) target.assertIsDisplayed()
                else target.performScrollTo().assertIsDisplayed()
                val node = target.fetchSemanticsNode()
                assertTrue("click width ${node.size}", node.size.width >= 72 * pixelsPerDp)
                assertTrue("click height ${node.size}", node.size.height >= 72 * pixelsPerDp)
                assertTrue(node.boundsInRoot.left >= root.left && node.boundsInRoot.right <= root.right)
            }
            val oneLine = SemanticsMatcher.expectValue(OneLineKey, true)
            if (!playing && ((home && width >= 840) || view == PanelView.Apps)) {
                val wide = home && width >= 840
                val buttonTag = if (wide) "player_idle_open" else "idle_open"
                if (!wide) rule.onNodeWithTag(buttonTag).performScrollTo()
                val button = rule.onNodeWithTag(buttonTag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                assertTrue(button.left >= root.left && button.right <= root.right &&
                    button.top >= root.top && button.bottom <= root.bottom)
                val parts = (if (wide) listOf("player_idle_icon", "player_idle_name", "player_idle_status", buttonTag)
                    else listOf("idle_icon", "idle_name", "idle_status", buttonTag)).flatMap { tag ->
                    rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }
                }
                for (i in parts.indices) for (j in i + 1 until parts.size) {
                    val a = parts[i]; val b = parts[j]
                    assertFalse("idle parts overlap: $a / $b", a.left < b.right && b.left < a.right &&
                        a.top < b.bottom && b.top < a.bottom)
                }
                for (part in parts) assertTrue(part.left >= root.left && part.right <= root.right &&
                    part.top >= root.top && part.bottom <= root.bottom)
                checkDisjoint()
            }
            val texts = rule.onAllNodes(oneLine, useUnmergedTree = true)
            assertTrue(texts.fetchSemanticsNodes().isNotEmpty())
            for (i in texts.fetchSemanticsNodes().indices) assertEquals(1, texts[i].textLayout().lineCount)
            if (view == PanelView.Sets && !(home && width >= 840)) {
                val oneSet = state.sets.take(1)
                for (sets in listOf(emptyList(), oneSet)) {
                    rule.runOnIdle { state = state.copy(sets = sets); Snapshot.sendApplyNotifications() }
                    val banner = rule.onNodeWithTag("permission_banner").performScrollTo().assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                    val area = rule.onNodeWithTag("panel_sets").fetchSemanticsNode().boundsInRoot
                    assertEquals(area.top, banner.top, 0f)
                    val unscrolledFirst = rule.onNodeWithTag(if (sets.isEmpty()) "panel_set_add" else "panel_set_${sets.single().id}")
                        .fetchSemanticsNode().boundsInRoot
                    assertEquals(banner.bottom + 8 * pixelsPerDp, unscrolledFirst.top, 0.5f)
                    val firstTag = if (sets.isEmpty()) "panel_set_add" else "panel_set_${sets.single().id}"
                    val first = rule.onNodeWithTag(firstTag).performScrollTo().assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                    assertTrue(first.left >= root.left && first.right <= root.right && first.top >= root.top && first.bottom <= root.bottom)
                    val add = rule.onNodeWithTag("panel_set_add").performScrollTo().assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                    assertTrue(add.left >= root.left && add.right <= root.right && add.top >= root.top && add.bottom <= root.bottom)
                    if (sets.isEmpty()) rule.onNodeWithTag("panel_set_${oneSet.single().id}").assertDoesNotExist()
                    else assertTrue(rule.onNodeWithTag(firstTag).fetchSemanticsNode().boundsInRoot.bottom <= add.top)
                    checkDisjoint()
                }
            }
        } finally {
            controller.pause().stop().destroy()
        }
    }
}

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@TextLayoutMode(TextLayoutMode.Mode.REALISTIC)
class HomeAppLayoutMatrixTest {
    @get:Rule val rule = createEmptyComposeRule()

    @Test fun dockFourAppsAndSettingsFitWithoutScrollingAt726dp() {
        RuntimeEnvironment.setQualifiers("w1291dp-h726dp")
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        WindowCompat.setDecorFitsSystemWindows(controller.get().window, false)
        var wide by mutableStateOf(true)
        val apps = (1..4).map { AppInfo("a$it", "App $it", null, true) }
        val state = PanelState(null, null, 0L, emptyList(), apps, DockStatus(true, null), false, false,
            settingsApp = AppInfo("settings", "設定", null, true))
        val actions = PanelActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {})
        try {
            controller.get().setContent { LauncherTheme {
                Box(Modifier.fillMaxSize().testTag("dock_layout_root")) {
                    if (wide) HomeWideContent(state, actions, Defaults.build(emptySet()),
                        { AppInfo(it, it, null, true) }, 0, {}, {})
                    else Box(Modifier.width(516.dp).fillMaxHeight()) { PanelContent(state, actions) }
                }
            } }
            controller.visible()
            for (homeWide in listOf(true, false)) {
                rule.runOnIdle { wide = homeWide; Snapshot.sendApplyNotifications() }
                val root = rule.onNodeWithTag("dock_layout_root").getBoundsInRoot()
                assertEquals(1291.dp, root.width)
                assertEquals(726.dp, root.height)
                val tags = (1..4).map { "dock_app_a$it" } +
                    listOf("dock_settings", "dock_home", "dock_split", "dock_applist")
                for (tag in tags) {
                    val bounds = rule.onNodeWithTag(tag).assertIsDisplayed().getBoundsInRoot()
                    assertTrue("$tag outside root", bounds.top >= root.top && bounds.bottom <= root.bottom &&
                        bounds.left >= root.left && bounds.right <= root.right)
                    assertTrue(bounds.width >= 72.dp && bounds.height >= 72.dp)
                }
                val scroll = rule.onAllNodes(hasScrollAction() and hasAnyAncestor(hasTestTag("dock")),
                    useUnmergedTree = true).fetchSemanticsNodes().single()
                    .config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange]
                assertEquals(0f, scroll.value(), 0f)
                assertEquals(0f, scroll.maxValue(), 0f)
            }
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun wideHome_cardsAndAppsStayInsideTheirViewports() {
        data class Case(val width: Int, val height: Int, val scale: Float,
                        val banner: Boolean, val sets: Int, val apps: Int)
        val cases = listOf(
            Case(1291, 726, 1f, false, 5, 5),
            Case(1291, 726, 1.3f, false, 5, 5),
            Case(1291, 726, 1.3f, true, 8, 1),
            Case(840, 600, 1f, true, 6, 0),
            Case(840, 600, 1.3f, true, 8, 5),
            Case(840, 600, 1f, false, 5, 1),
            Case(1291, 726, 1f, false, 5, 0),
            Case(1291, 726, 1f, false, 5, 3),
            Case(1291, 726, 1f, false, 5, 12),
            Case(840, 600, 1.3f, true, 8, 12),
        )
        for (case in cases) {
            RuntimeEnvironment.setQualifiers("w${case.width}dp-h${case.height}dp")
            val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            WindowCompat.setDecorFitsSystemWindows(controller.get().window, false)
            val config = Defaults.build(emptySet()).copy(screenSets = (1..case.sets).map {
                com.gt8d.launcher.config.ScreenSet("s$it", com.gt8d.launcher.config.Slot.App("a"),
                    com.gt8d.launcher.config.Slot.Panel)
            })
            val apps = (1..case.apps).map { AppInfo("a$it", "名前$it", null, true) }
            val state = PanelState(null, AppInfo("idle", "Music", null, true), 0L, apps, emptyList(),
                DockStatus(true, true), false, case.banner,
                homeEntries = apps.map { com.gt8d.launcher.ui.apps.AppListEntry(it.pkg, "${it.pkg}.Main", it.label) })
            try {
                controller.get().setContent { LauncherTheme {
                    CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, case.scale)) {
                        Box(Modifier.fillMaxSize().testTag("wide_root")) {
                            HomeWideContent(state, PanelActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}),
                                config, { AppInfo(it, "長い名前".repeat(20), null, true) }, 0, {}, {})
                        }
                    }
                } }
                controller.visible()
                val root = rule.onNodeWithTag("wide_root").getBoundsInRoot()
                val tags = listOf("clock", "home_sets", "home_player", "dock") +
                    if (case.apps > 0) listOf("home_apps") else emptyList()
                val regions = tags.map { rule.onNodeWithTag(it).getBoundsInRoot() }
                fun inside(b: androidx.compose.ui.unit.DpRect) {
                    assertTrue("$case: $b outside $root", b.left >= root.left + 16.dp &&
                        b.right <= root.right - 16.dp && b.top >= root.top + 16.dp && b.bottom <= root.bottom - 16.dp)
                }
                for (b in regions) inside(b)
                for (i in regions.indices) for (j in i + 1 until regions.size) {
                    val a = regions[i]; val b = regions[j]
                    assertFalse("$case: ${tags[i]} overlaps ${tags[j]}",
                        a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom)
                }
                val setsArea = rule.onNodeWithTag("home_sets").getBoundsInRoot()
                for (tag in config.screenSets.map { "home_set_${it.id}" } + "home_set_add") {
                    val node = rule.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
                    val b = node.getBoundsInRoot()
                    inside(b)
                    assertTrue(b.top >= setsArea.top && b.bottom <= setsArea.bottom)
                    if (tag != "home_set_add") node.assertHeightIsEqualTo(104.dp)
                }
                if (case.apps == 0) rule.onNodeWithTag("home_apps").assertDoesNotExist()
                else {
                    val row = rule.onNodeWithTag("home_apps").getBoundsInRoot()
                    assertEquals(rule.onNodeWithTag("home_player").getBoundsInRoot().bottom, row.bottom)
                    for (app in apps) {
                        rule.onNodeWithTag("home_apps").performScrollToIndex(apps.indexOf(app))
                        val node = rule.onNodeWithTag("home_app_${app.pkg}")
                        if (case.width == 1291 && !case.banner && case.apps == 5) {
                            val before = node.assertIsDisplayed().getBoundsInRoot()
                            assertTrue(before.left >= row.left && before.right <= row.right)
                            node.assertWidthIsEqualTo(96.dp)
                        }
                        val b = node.performScrollTo().assertIsDisplayed().getBoundsInRoot()
                        inside(b)
                        assertTrue(b.left >= row.left && b.right <= row.right && b.top >= row.top && b.bottom <= row.bottom)
                    }
                }
            } finally { controller.pause().stop().destroy() }
        }
    }
}

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [35])
class HomeResizeStateTest {
    @get:Rule val rule = createEmptyComposeRule()

    @Test fun appList_survivesWidthChanges() {
        RuntimeEnvironment.setQualifiers("w1291dp-h726dp")
        AppGraph.init(ApplicationProvider.getApplicationContext())
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        var width by mutableStateOf(1291.dp)
        try {
            controller.get().setContent {
                LauncherTheme {
                    Box(Modifier.width(width).fillMaxHeight()) {
                        HomeContent { wide ->
                            Box(Modifier.fillMaxSize()) { PanelScreen(inSplit = false, homeWide = wide, onLaunchApp = {}) }
                        }
                    }
                }
            }
            controller.visible()
            rule.onNodeWithTag("dock_applist").performScrollTo().performClick()
            rule.onNodeWithTag("applist_close").assertIsDisplayed()
            for (next in listOf(516.dp, 1291.dp)) {
                rule.runOnIdle { width = next; Snapshot.sendApplyNotifications() }
                rule.onNodeWithTag("applist_close").assertIsDisplayed()
            }
        } finally { controller.pause().stop().destroy() }
    }
}
