package com.gt8d.launcher.ui.layout

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat
import com.gt8d.launcher.config.*
import com.gt8d.launcher.curtain.*
import com.gt8d.launcher.settings.*
import com.gt8d.launcher.split.SplitMode
import com.gt8d.launcher.ui.apps.*
import com.gt8d.launcher.ui.home.BootScreen
import com.gt8d.launcher.ui.theme.LauncherTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@TextLayoutMode(TextLayoutMode.Mode.REALISTIC)
class OtherScreensLayoutTest(private val width: Int, private val height: Int, private val scale: Float) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}x{1} scale={2}")
        fun cases(): List<Array<Any>> = buildList {
            for (w in listOf(360, 387, 516, 645, 1291))
                for (h in listOf(400, 726)) for (s in listOf(1f, 1.3f)) add(arrayOf(w, h, s))
        }
    }
    @get:Rule val rule = createEmptyComposeRule()

    @Test fun otherScreens_fitAndControlsAreReachable() {
        RuntimeEnvironment.setQualifiers("w${width}dp-h${height}dp")
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        WindowCompat.setDecorFitsSystemWindows(controller.get().window, false)
        var screen by mutableStateOf("boot")
        var density = 0f
        val config = LauncherConfig(screenSets = listOf(ScreenSet("sample", Slot.App("a"), Slot.Panel)), homeApps = listOf("a", "b"), panelApps = listOf("a"), dockApps = listOf("a"), idleApp = "a")
        val sets = MutableStateFlow(config.screenSets)
        val store = SetsStore(sets, { change -> val before = sets.value; val after = change(before); sets.value = after; before to after }, { 0 }, {})
        val actions = SettingsActions({}, {}, { true }, {}, { true })
        val routes = mapOf("root" to SettingsRoute.Root, "sets" to SettingsRoute.Sets,
            "edit" to SettingsRoute.SetEdit(null))
        try {
            controller.get().setContent {
                LauncherTheme {
                    val original = LocalDensity.current
                    density = original.density
                    CompositionLocalProvider(LocalDensity provides Density(density, scale)) {
                        Box(Modifier.fillMaxSize().testTag("other_root")) {
                            key(screen) {
                                when (screen) {
                                    "boot" -> BootScreen(CurtainTarget("セット", SlotPreview.App("a", "長いアプリ名"), SlotPreview.Panel, SplitRatio.R6_4, 1, 4), 3, false, {}, {})
                                    "safety" -> SafetyRecoveryScreen({}, {})
                                    "apps" -> AppListScreen((1..12).map { AppListEntry("app$it", "Main", "長いアプリケーション名 $it") }, { null }, {}, {})
                                    in routes -> SettingsHost(actions, {}, {}, initialRoute = routes.getValue(screen), setsStore = store, labelFor = { "長いアプリ名 $it" }, launchablePackages = { listOf("a", "b") })
                                    else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                                        when (screen) {
                                            "lists" -> ListsScreen(config, {}, { "長いアプリ名 $it" }, { listOf("a", "b") })
                                            "link" -> LinkScreen(config, {})
                                            "gesture" -> GestureScreen(config, {})
                                            "bootSettings" -> BootSettingsScreen(config, {})
                                            "permissions" -> PermissionsScreen(object : PermissionChecker { override fun granted(item: PermissionItem) = false }, object : PermissionOpener { override fun open(item: PermissionItem) {} }, { SplitMode.AppPair }, {})
                                            "prep" -> AppPairPrepScreen()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            controller.visible()
            for (name in listOf("boot", "root", "sets", "edit", "lists", "link", "gesture", "bootSettings", "permissions", "prep", "safety", "apps")) {
                rule.runOnIdle { screen = name; androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications() }
                val root = rule.onNodeWithTag("other_root").fetchSemanticsNode().boundsInRoot
                assertEquals(width * density, root.width, 0.5f)
                assertEquals(height * density, root.height, 0.5f)
                val controls = rule.onAllNodes(hasClickAction())
                val count = if (name == "apps") 13 else controls.fetchSemanticsNodes().size
                for (i in 0 until count) {
                    if (name == "apps" && i > 0) rule.onNode(hasScrollToIndexAction()).performScrollToIndex(i - 1)
                    val target = if (name == "apps") rule.onNodeWithTag(if (i == 0) "applist_close" else "applist_app$i") else controls[i]
                    val node = target.fetchSemanticsNode()
                    if (generateSequence(node.parent) { it.parent }.any { SemanticsActions.ScrollToIndex in it.config || SemanticsActions.ScrollBy in it.config }) {
                        if (name != "apps") target.performScrollTo()
                    }
                    target.assertIsDisplayed()
                    val current = target.fetchSemanticsNode()
                    assertTrue("$name width ${current.size}", current.size.width >= 72 * density)
                    assertTrue("$name height ${current.size}", current.size.height >= 72 * density)
                    assertTrue("$name horizontal ${current.boundsInRoot}", current.boundsInRoot.left >= root.left && current.boundsInRoot.right <= root.right)
                    val visible = controls.fetchSemanticsNodes().filter { it.boundsInRoot.width > 0 && it.boundsInRoot.height > 0 }
                    for (a in visible.indices) for (b in a + 1 until visible.size) {
                        val x = visible[a].boundsInRoot; val y = visible[b].boundsInRoot
                        assertFalse("$name overlapping controls", x.left < y.right && y.left < x.right && x.top < y.bottom && y.top < x.bottom)
                    }
                }
                val texts = rule.onAllNodes(SemanticsMatcher.expectValue(OneLineKey, true), useUnmergedTree = true)
                for (i in texts.fetchSemanticsNodes().indices) assertEquals(1, texts[i].textLayout().lineCount)
                if (name == "boot") {
                    val a = rule.onNodeWithTag("boot_open_now").fetchSemanticsNode().boundsInRoot
                    val b = rule.onNodeWithTag("boot_stay_home").fetchSemanticsNode().boundsInRoot
                    if (width == 387) assertTrue(a.bottom <= b.top)
                    if (width == 1291) { assertEquals(a.top, b.top, 0.5f); assertTrue(a.right <= b.left) }
                }
                if (name == "apps") {
                    rule.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
                    val a = rule.onNodeWithTag("applist_app1").fetchSemanticsNode().boundsInRoot
                    val b = rule.onNodeWithTag("applist_app2").fetchSemanticsNode().boundsInRoot
                    assertEquals(a.top, b.top, 0.5f)
                    assertTrue(a.right <= b.left)
                }
            }
        } finally { controller.pause().stop().destroy() }
    }
}
