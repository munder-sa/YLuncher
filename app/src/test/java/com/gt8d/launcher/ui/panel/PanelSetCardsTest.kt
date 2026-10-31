package com.gt8d.launcher.ui.panel

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.InspectableValue
import androidx.compose.ui.platform.isDebugInspectorInfoEnabled
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.gt8d.launcher.config.*
import com.gt8d.launcher.ui.home.PanelIconMirrored
import com.gt8d.launcher.ui.theme.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.TextLayoutMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w600dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@TextLayoutMode(TextLayoutMode.Mode.REALISTIC)
class PanelSetCardsTest {
    @get:Rule val rule = createEmptyComposeRule()
    private lateinit var controller: ActivityController<ComponentActivity>
    @Before fun enableInspector() { isDebugInspectorInfoEnabled = true }
    private fun properties(node: SemanticsNodeInteraction, name: String, parent: Boolean = false): List<Map<String, Any?>> =
        node.fetchSemanticsNode().layoutInfo.let { if (parent) requireNotNull(it.parentInfo) else it }
            .getModifierInfo().mapNotNull {
            (it.modifier as? InspectableValue)?.takeIf { it.nameFallback == name }
                ?.inspectableElements?.associate { value -> value.name to value.value }
        }
    private fun sets(count: Int) = (1..count).map {
        ScreenSet("s$it", Slot.App("left"), Slot.Panel)
    }
    private fun host(sets: List<ScreenSet> = sets(2), height: Int = 600,
                     label: String = "左アプリ", installed: Boolean = true, icon: ImageBitmap? = null,
                     onOpen: (Int) -> Unit = {}, onAdd: () -> Unit = {}) {
        controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller.get().setContent { LauncherTheme {
            Box(Modifier.size(360.dp, height.dp).testTag("area")) {
                PanelSetCards(sets, { AppInfo(it, label, icon, installed) }, 2, onOpen, onAdd, Modifier.fillMaxSize())
            }
        } }
        controller.visible()
    }
    @After fun cleanup() {
        if (::controller.isInitialized) controller.pause().stop().destroy()
        isDebugInspectorInfoEnabled = false
    }
    @Test fun cards_have104dpHeight8dpGap_andOpenNumber() {
        val opened = mutableListOf<Int>()
        host(onOpen = { opened.add(it) })
        val first = rule.onNodeWithTag("panel_set_s1").assertHeightIsEqualTo(104.dp).assertWidthIsEqualTo(360.dp)
        val second = rule.onNodeWithTag("panel_set_s2").assertHeightIsEqualTo(104.dp)
        assertEquals(8f, second.getBoundsInRoot().top.value - first.getBoundsInRoot().bottom.value, 0f)
        assertEquals(rule.onNodeWithTag("area").getBoundsInRoot().top, first.getBoundsInRoot().top)
        rule.onNodeWithText("1  左アプリ | パネル").assertExists()
        second.assertIsSelected().performClick()
        first.assertIsNotSelected().performClick()
        assertEquals(listOf(2, 1), opened)
        val border = properties(second, "border").single()
        assertEquals(3.dp, border["width"])
        assertEquals(Color.White, border["color"])
        assertEquals(RoundedCornerShape(24.dp), border["shape"])
        assertTrue(properties(first, "border").isEmpty())
        assertEquals(Palette.Background1, properties(first, "background").single()["color"])
    }
    @Test fun panelIcons_are48dp_andMirroredOnlyOnLeft() {
        host(sets = listOf(ScreenSet("s1", Slot.Panel, Slot.App("right")),
            ScreenSet("s2", Slot.App("left"), Slot.Panel)))
        for ((id, mirrored) in listOf("s1" to true, "s2" to false)) {
            rule.onNode(hasContentDescription("パネル") and hasAnyAncestor(hasTestTag("panel_set_$id")), true)
                .assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
                .assert(SemanticsMatcher.expectValue(PanelIconMirrored, mirrored))
        }
    }
    @Test fun installedIcon_is48dp() {
        host(sets = listOf(ScreenSet("s1", Slot.App("left"), Slot.App("right"))), icon = ImageBitmap(48, 48))
        rule.onAllNodesWithContentDescription("左アプリ", useUnmergedTree = true).assertCountEquals(2)
        for (i in 0..1) rule.onAllNodesWithContentDescription("左アプリ", useUnmergedTree = true)[i]
            .assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
        rule.onNodeWithText("左", useUnmergedTree = true).assertDoesNotExist()
    }
    private fun fallback(installed: Boolean) {
        host(sets = sets(1), installed = installed)
        val text = rule.onNodeWithText("左", useUnmergedTree = true)
        val layouts = mutableListOf<TextLayoutResult>()
        text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(if (installed) Color.White else Palette.Muted, layouts.single().layoutInput.style.color)
        val description = "左アプリ" + if (installed) "" else "(アプリが見つかりません)"
        rule.onNodeWithContentDescription(description, useUnmergedTree = true)
            .assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
        rule.onNode(SemanticsMatcher.expectValue(PanelSetFallbackBackground,
            if (installed) Palette.Panel else Palette.Control), useUnmergedTree = true).assertExists()
    }
    @Test fun installedWithoutIcon_hasPanelInitial() = fallback(true)
    @Test fun missingApp_hasControlInitial() = fallback(false)
    @Test fun add_is72dpFullWidthSolidBorderMuted_andCallsAdd() {
        var calls = 0
        host(onAdd = { calls++ })
        val add = rule.onNodeWithTag("panel_set_add").assertHeightIsEqualTo(72.dp).assertWidthIsEqualTo(360.dp)
        val border = properties(add, "border").single()
        assertEquals(1.dp, border["width"])
        assertEquals(Palette.Control, border["color"])
        assertEquals(RoundedCornerShape(24.dp), border["shape"])
        assertTrue(properties(add, "background").isEmpty())
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText("セットを追加", useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(Palette.Muted, layouts.single().layoutInput.style.color)
        add.performClick()
        assertEquals(1, calls)
    }
    @Test fun longName_isOneLineEllipsized() {
        val label = "長い名前".repeat(40)
        host(label = label, sets = sets(1))
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText("1  $label | パネル", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.single().lineCount)
        assertTrue(layouts.single().isLineEllipsized(0))
    }
    @Test fun fiveSets_scrollToLastAdd() {
        host(sets = sets(5), height = 240)
        for (i in 1..5) rule.onNodeWithTag("panel_set_s$i").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("panel_set_add").performScrollTo().assertIsDisplayed()
    }
    @Test fun shortViewport_cardsShrinkToViewportWith72dpMinimum() {
        host(sets = sets(2), height = 80)
        rule.onNodeWithTag("panel_set_s1").assertHeightIsEqualTo(80.dp)
        rule.onNodeWithTag("panel_set_s2").performScrollTo().assertHeightIsEqualTo(80.dp).assertIsDisplayed()
        rule.onNodeWithTag("panel_set_add").performScrollTo().assertHeightIsEqualTo(72.dp).assertIsDisplayed()
    }
    @Test fun below72dpViewport_keeps72dpCardHeight() {
        host(sets = sets(1), height = 60)
        assertEquals(72.dp, with(rule.density) {
            rule.onNodeWithTag("panel_set_s1").fetchSemanticsNode().size.height.toDp()
        })
    }
    @Test fun noSets_hasOnlyAddAtTop() {
        host(sets = emptyList())
        rule.onNodeWithTag("panel_set_s1").assertDoesNotExist()
        val add = rule.onNodeWithTag("panel_set_add").assertIsDisplayed().getBoundsInRoot()
        assertEquals(rule.onNodeWithTag("area").getBoundsInRoot().top, add.top)
    }
}
