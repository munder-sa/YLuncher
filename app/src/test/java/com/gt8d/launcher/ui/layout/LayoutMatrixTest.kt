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
import androidx.core.view.WindowCompat
import androidx.test.core.app.ApplicationProvider
import com.gt8d.launcher.AppGraph
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
) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}x{1} scale={2} home={3} playing={4}")
        fun cases(): List<Array<Any>> = buildList {
            for (w in listOf(360, 387, 516, 645, 774))
                for (h in listOf(400, 600, 726)) for (s in listOf(1f, 1.3f))
                    for (p in listOf(false, true)) add(arrayOf(w, h, s, false, p))
            for (w in listOf(387, 516, 645, 774, 839, 840, 903, 1291))
                for (h in listOf(600, 726)) add(arrayOf(w, h, 1f, true, false))
        }
    }
    @get:Rule val rule = createEmptyComposeRule()

    @Test fun layout_fitsAndAllControlsAreReachable() {
        RuntimeEnvironment.setQualifiers("w${width}dp-h${height}dp")
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        WindowCompat.setDecorFitsSystemWindows(controller.get().window, false)
        val apps = (1..5).map { AppInfo("app$it", "長いアプリケーション名 $it", null, true) }
        val state = PanelState(
            if (playing) NowPlaying("music", "長い曲名のテスト", "長いアーティスト名", null, 180000L, true, true) else null,
            AppInfo("idle", "Apple Music", null, true), 10000L, apps, apps.take(4),
            DockStatus(true, true), true, true,
        )
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
            if (home) {
                if (width < 840) rule.onNodeWithTag("home_sets").assertDoesNotExist()
                else {
                    rule.onNodeWithTag("home_sets").assertExists()
                    rule.onNodeWithTag("home_player").assertExists()
                    rule.onNodeWithTag("media_card").assertDoesNotExist()
                    rule.onNodeWithTag("icon_grid").assertDoesNotExist()
                }
            }
            val tags = if (home && width >= 840) listOf("clock", "home_sets", "home_player", "dock")
                else listOf("clock", "media_card", "icon_grid", "dock")
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
            for (tag in if (home && width >= 840) emptyList() else listOf("media_card", "icon_grid")) {
                rule.onNodeWithTag(tag).performScrollTo()
                checkDisjoint()
            }
            val clickCount = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().size
            for (index in 0 until clickCount) {
                val target = rule.onAllNodes(hasClickAction())[index]
                if (home && width >= 840 && target.fetchSemanticsNode().config
                        .getOrElse(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { "" } in
                        listOf("player_idle_app", "permission_banner")) target.assertIsDisplayed()
                else target.performScrollTo().assertIsDisplayed()
                val node = target.fetchSemanticsNode()
                assertTrue("click width ${node.size}", node.size.width >= 72 * pixelsPerDp)
                assertTrue("click height ${node.size}", node.size.height >= 72 * pixelsPerDp)
                assertTrue(node.boundsInRoot.left >= root.left && node.boundsInRoot.right <= root.right)
            }
            val oneLine = SemanticsMatcher.expectValue(OneLineKey, true)
            val texts = rule.onAllNodes(oneLine, useUnmergedTree = true)
            assertTrue(texts.fetchSemanticsNodes().isNotEmpty())
            for (i in texts.fetchSemanticsNodes().indices) assertEquals(1, texts[i].textLayout().lineCount)
        } finally {
            controller.pause().stop().destroy()
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
