package com.gt8d.launcher.ui.home

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.InspectableValue
import androidx.compose.ui.platform.isDebugInspectorInfoEnabled
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import com.gt8d.launcher.config.*
import com.gt8d.launcher.ui.panel.AppInfo
import com.gt8d.launcher.ui.theme.LauncherTheme
import com.gt8d.launcher.ui.theme.Palette
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
@Config(sdk = [35], qualifiers = "w1000dp-h1000dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@TextLayoutMode(TextLayoutMode.Mode.REALISTIC)
class HomeSetCardsTest {
    @get:Rule val rule = createEmptyComposeRule()
    private lateinit var controller: ActivityController<ComponentActivity>
    @Before fun inspector() { isDebugInspectorInfoEnabled = true }
    private fun properties(node: SemanticsNodeInteraction, name: String) =
        node.fetchSemanticsNode().layoutInfo.getModifierInfo().mapNotNull {
            (it.modifier as? InspectableValue)?.takeIf { it.nameFallback == name }
                ?.inspectableElements?.associate { value -> value.name to value.value }
        }
    private fun sets(count: Int) = (1..count).map {
        ScreenSet("s$it", Slot.App("left"), if (it == 1) Slot.Panel else Slot.App("right"), SplitRatio.R6_4)
    }
    private fun host(width: Int = 700, height: Int = 800, count: Int = 1,
                     current: Int = 0, onOpen: (Int) -> Unit = {}, onAdd: () -> Unit = {},
                     leftLabel: String = "左アプリ", screenSets: List<ScreenSet> = sets(count), installed: Boolean = true,
                     scale: Float = 1f, icon: ImageBitmap? = null) {
        controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller.get().setContent { LauncherTheme {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
            Box(Modifier.size(width.dp, height.dp).testTag("area")) {
                HomeSetCards(screenSets, { AppInfo(it, if (it == "left") leftLabel else "右アプリ", icon, installed) },
                    current, onOpen, onAdd, Modifier.fillMaxSize())
            }
            }
        } }
        controller.visible()
    }
    @After fun cleanup() {
        if (::controller.isInitialized) controller.pause().stop().destroy()
        isDebugInspectorInfoEnabled = false
    }
    @Test fun cards_have104dpHeight24dpCornersAndGaps() {
        host(count = 3)
        val first = rule.onNodeWithTag("home_set_s1").assertHeightIsEqualTo(104.dp)
        val second = rule.onNodeWithTag("home_set_s2").getBoundsInRoot()
        val third = rule.onNodeWithTag("home_set_s3").getBoundsInRoot()
        assertEquals(24f, second.left.value - first.getBoundsInRoot().right.value, 0f)
        assertEquals(24f, third.top.value - first.getBoundsInRoot().bottom.value, 0f)
        val background = properties(first, "background").single()
        assertEquals(Palette.Background1, background["color"])
        assertEquals(RoundedCornerShape(24.dp), background["shape"])
        rule.onNodeWithText("1", useUnmergedTree = true).assertExists()
        for (side in listOf("left", "right")) rule.onNodeWithTag("home_set_s1_$side", true)
            .assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
    }
    @Test fun fontScale13_namesFitFixedHeight() {
        host(scale = 1.3f)
        val card = rule.onNodeWithTag("home_set_s1").assertHeightIsEqualTo(104.dp).getBoundsInRoot()
        for (label in listOf("左アプリ", "パネル")) {
            val name = rule.onNodeWithText(label, useUnmergedTree = true).getBoundsInRoot()
            assertTrue(name.top >= card.top && name.bottom <= card.bottom)
        }
    }
    @Test fun installedWithoutIcon_hasPanelInitial() {
        host()
        rule.onNodeWithText("左", useUnmergedTree = true).assertExists()
        rule.onNode(SemanticsMatcher.expectValue(HomeSetFallbackBackground, Palette.Panel), true).assertExists()
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText("左", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(Color.White, layouts.single().layoutInput.style.color)
    }
    @Test fun installedImage_fills48dpIconWithoutInitial() {
        host(icon = ImageBitmap(48, 48))
        rule.onNodeWithContentDescription("左アプリ", useUnmergedTree = true)
            .assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
        rule.onNodeWithText("左", useUnmergedTree = true).assertDoesNotExist()
    }
    @Test fun missingApp_hasControlInitial() {
        host(installed = false)
        rule.onNodeWithText("左", useUnmergedTree = true).assertExists()
        rule.onNode(SemanticsMatcher.expectValue(HomeSetFallbackBackground, Palette.Control), true).assertExists()
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText("左", useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(Palette.Muted, layouts.single().layoutInput.style.color)
    }
    @Test fun cards_showNamesAndPanelLabel() {
        host(count = 2)
        rule.onNodeWithTag("home_set_s1").assert(hasContentDescription("1  左アプリ | パネル"))
        rule.onNodeWithTag("home_set_s2").assert(hasContentDescription("2  左アプリ | 右アプリ"))
        rule.onAllNodesWithText("左アプリ", useUnmergedTree = true).assertCountEquals(2)
        rule.onNodeWithText("右アプリ", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("パネル", useUnmergedTree = true).assertExists()
        rule.onAllNodesWithContentDescription("左アプリ", useUnmergedTree = true).assertCountEquals(2)
    }
    @Test fun missingApp_showsInitialAndMissingName_withoutOverlappingIcon() {
        host(height = 560, count = 5, installed = false)
        rule.onAllNodesWithText("左", useUnmergedTree = true).assertCountEquals(5)
        val slot = rule.onNodeWithTag("home_set_s1_left", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val missingNode = rule.onNode(hasText("見つかりません") and hasAnyAncestor(hasTestTag("home_set_s1")),
            useUnmergedTree = true).assertIsDisplayed()
        val missing = missingNode.fetchSemanticsNode().boundsInRoot
        val card = rule.onNodeWithTag("home_set_s1").fetchSemanticsNode().boundsInRoot
        assertTrue(missing.left >= slot.right)
        assertTrue(missing.right <= card.right)
        assertTrue(missing.top >= card.top)
        assertTrue(missing.bottom <= card.bottom)
        rule.onNodeWithTag("home_set_s1").assert(hasContentDescription("1  左アプリ | パネル"))
        val layouts = mutableListOf<TextLayoutResult>()
        missingNode.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.single().lineCount)
        rule.onNode(hasContentDescription("左アプリ(アプリが見つかりません)") and hasAnyAncestor(hasTestTag("home_set_s1_left")),
            useUnmergedTree = true).assertExists()
    }
    @Test fun panel_shows48dpIcon_andNameLine() {
        host()
        rule.onNodeWithContentDescription("パネル", useUnmergedTree = true)
            .assertExists().assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
        rule.onNodeWithText("パネル", useUnmergedTree = true).assertExists()
    }
    @Test fun leftPanelSet_showsPanelIconOnLeft() {
        host(screenSets = listOf(ScreenSet("s1", Slot.Panel, Slot.App("right"), SplitRatio.R4_6)))
        val card = rule.onNodeWithTag("home_set_s1").fetchSemanticsNode().boundsInRoot
        val panel = rule.onNodeWithContentDescription("パネル", useUnmergedTree = true)
            .assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
            .fetchSemanticsNode().boundsInRoot
        assertTrue(panel.center.x < card.center.x)
        rule.onNodeWithTag("home_set_s1").assert(hasContentDescription("1  パネル | 右アプリ"))
    }
    @Test fun panelIcon_mirroredOnlyOnLeft() {
        host(screenSets = listOf(
            ScreenSet("s1", Slot.Panel, Slot.App("right"), SplitRatio.R4_6),
            ScreenSet("s2", Slot.App("left"), Slot.Panel, SplitRatio.R6_4),
        ))
        rule.onNode(hasContentDescription("パネル") and hasAnyAncestor(hasTestTag("home_set_s1")),
            useUnmergedTree = true).assert(SemanticsMatcher.expectValue(PanelIconMirrored, true))
        rule.onNode(hasContentDescription("パネル") and hasAnyAncestor(hasTestTag("home_set_s2")),
            useUnmergedTree = true).assert(SemanticsMatcher.expectValue(PanelIconMirrored, false))
    }
    @Test fun leftPanelSet_equal48dpIconWidths() {
        host(screenSets = listOf(ScreenSet("s1", Slot.Panel, Slot.App("right"), SplitRatio.R4_6)))
        val leftBlock = rule.onNodeWithTag("home_set_s1_left", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val rightBlock = rule.onNodeWithTag("home_set_s1_right", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertEquals(with(rule.density) { 48.dp.toPx() }, leftBlock.width, 0f)
        assertEquals(leftBlock.width, rightBlock.width, 0f)
    }
    @Test fun shortNames_areLeftAlignedAfterIcons() {
        host()
        val card = rule.onNodeWithTag("home_set_s1").fetchSemanticsNode().boundsInRoot
        val name = rule.onNodeWithText("左アプリ", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val panel = rule.onNodeWithText("パネル", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(card.left + with(rule.density) { 168.dp.toPx() }, name.left, 1f)
        assertEquals(name.left, panel.left, 0f)
        assertTrue(name.bottom <= panel.top)
    }
    @Test fun longName_isOneLineAndFitsInCard() {
        val label = "長".repeat(40)
        host(leftLabel = label)
        val card = rule.onNodeWithTag("home_set_s1").fetchSemanticsNode().boundsInRoot
        val name = rule.onNodeWithText(label, useUnmergedTree = true)
        val layouts = mutableListOf<TextLayoutResult>()
        name.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.size)
        assertEquals(1, layouts.single().lineCount)
        assertTrue(layouts.single().isLineEllipsized(0))
        val bounds = name.fetchSemanticsNode().boundsInRoot
        val innerWidth = card.width - with(rule.density) { 32.dp.toPx() }
        assertTrue(bounds.width <= innerWidth)
        assertTrue(bounds.left >= card.left)
        assertTrue(bounds.right <= card.right)
        assertTrue(bounds.top >= card.top)
        assertTrue(bounds.bottom <= card.bottom)
    }
    @Test fun currentSet_isSelected_withAccentBorder() {
        host(count = 2, current = 2)
        rule.onNodeWithTag("home_set_s2").assertIsSelected()
            .assert(SemanticsMatcher.expectValue(HomeSetHighlight, true))
        rule.onNodeWithTag("home_set_s1").assertIsNotSelected()
            .assert(SemanticsMatcher.expectValue(HomeSetHighlight, false))
        val border = properties(rule.onNodeWithTag("home_set_s2"), "border").single()
        assertEquals(3.dp, border["width"])
        assertEquals(Color.White, border["color"])
        assertEquals(RoundedCornerShape(24.dp), border["shape"])
        assertTrue(properties(rule.onNodeWithTag("home_set_s1"), "border").isEmpty())
    }
    @Test fun click_opensIndex() {
        var index = 0
        host(count = 2, onOpen = { index = it })
        rule.onNodeWithTag("home_set_s2").performClick()
        assertEquals(2, index)
    }
    @Test fun add_callsOnAdd() {
        var calls = 0
        host(onAdd = { calls++ })
        rule.onNodeWithTag("home_set_add").performClick()
        assertEquals(1, calls)
    }
    @Test fun fewCards_areTopAligned() {
        host()
        val area = rule.onNodeWithTag("area").fetchSemanticsNode().boundsInRoot
        val first = rule.onNodeWithTag("home_set_s1").fetchSemanticsNode().boundsInRoot
        val last = rule.onNodeWithTag("home_set_add").fetchSemanticsNode().boundsInRoot
        assertEquals(area.top, first.top, 0f)
        assertEquals(first.top, last.top, 0f)
    }
    @Test fun threeRows_fitWithoutClipping() {
        host(height = 560, count = 5)
        val area = rule.onNodeWithTag("area").fetchSemanticsNode().boundsInRoot
        val first = rule.onNodeWithTag("home_set_s1").fetchSemanticsNode().boundsInRoot
        val last = rule.onNodeWithTag("home_set_add").fetchSemanticsNode().boundsInRoot
        assertTrue(first.top >= area.top - 1f)
        assertTrue(last.bottom <= area.bottom + 1f)
    }
    @Test fun manyCards_scrollFromTop() {
        host(height = 500, count = 8)
        val area = rule.onNodeWithTag("area").fetchSemanticsNode().boundsInRoot
        assertEquals(area.top, rule.onNodeWithTag("home_set_s1").fetchSemanticsNode().boundsInRoot.top, 0f)
        rule.onNodeWithTag("home_set_s8").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("home_set_add").performScrollTo().assertIsDisplayed()
    }
    @Test fun addRow_is72dpFullWidthBelowLastCard() {
        host(count = 4)
        val add = rule.onNodeWithTag("home_set_add")
            .assertHeightIsEqualTo(72.dp).assertWidthIsEqualTo(700.dp)
            .fetchSemanticsNode().boundsInRoot
        val last = rule.onNodeWithTag("home_set_s4").fetchSemanticsNode().boundsInRoot
        assertEquals(with(rule.density) { 24.dp.toPx() }, add.top - last.bottom, 1f)
    }
    @Test fun fiveSets_addOccupiesNextCellAndFitsWideHomeArea() {
        // 1920x1080 / (238/160) から、外側余白・プレイヤー・ドック・時計を除く。
        // 利用可能領域を小さい側の整数 dp に丸める。
        host(width = 708, height = 525, count = 5)
        val area = rule.onNodeWithTag("area").fetchSemanticsNode().boundsInRoot
        val last = rule.onNodeWithTag("home_set_s5").fetchSemanticsNode().boundsInRoot
        val add = rule.onNodeWithTag("home_set_add").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals(last.top, add.top, 0f)
        assertEquals(last.width, add.width, 1f)
        assertEquals(last.height, add.height, 0f)
        assertEquals(with(rule.density) { 24.dp.toPx() }, add.left - last.right, 1f)
        assertTrue(add.right <= area.right)
        assertTrue(add.bottom <= area.bottom)
        rule.onNodeWithTag("home_set_s1").assertHeightIsEqualTo(104.dp)
    }
    @Test fun sixSets_addIs72dpFullWidthBelowLastCard() {
        host(count = 6)
        val add = rule.onNodeWithTag("home_set_add")
            .assertHeightIsEqualTo(72.dp).assertWidthIsEqualTo(700.dp)
            .fetchSemanticsNode().boundsInRoot
        val last = rule.onNodeWithTag("home_set_s6").fetchSemanticsNode().boundsInRoot
        assertEquals(with(rule.density) { 24.dp.toPx() }, add.top - last.bottom, 1f)
    }
    @Test fun oneColumnThreeSets_addIs72dpFullWidthBelowLastCard() {
        host(width = 600, count = 3)
        val add = rule.onNodeWithTag("home_set_add")
            .assertHeightIsEqualTo(72.dp).assertWidthIsEqualTo(600.dp)
            .fetchSemanticsNode().boundsInRoot
        val last = rule.onNodeWithTag("home_set_s3").fetchSemanticsNode().boundsInRoot
        assertEquals(with(rule.density) { 24.dp.toPx() }, add.top - last.bottom, 1f)
    }
    @Test fun add_hasMutedLabelAndButtonDescription() {
        host()
        rule.onNodeWithTag("home_set_add").assertContentDescriptionEquals("セットを追加")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        rule.onNodeWithText("＋ セットを追加").assertDoesNotExist()
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText("セットを追加", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(Palette.Muted, layouts.single().layoutInput.style.color)
    }
    @Test fun fourSets_cardHeightIsFixed104dp() {
        host(height = 420, count = 4)
        for (index in 1..4) {
            val bounds = rule.onNodeWithTag("home_set_s$index").getBoundsInRoot()
            assertEquals(104f, bounds.height.value, 1f)
        }
    }
    @Test fun noSets_showsOnlyAddRow() {
        host(count = 0)
        rule.onAllNodes(hasTestTag("home_set_add")).assertCountEquals(1)
        rule.onNodeWithTag("home_set_s1").assertDoesNotExist()
        val add = rule.onNodeWithTag("home_set_add")
            .assertHeightIsEqualTo(72.dp).assertWidthIsEqualTo(700.dp)
            .fetchSemanticsNode().boundsInRoot
        val area = rule.onNodeWithTag("area").fetchSemanticsNode().boundsInRoot
        assertEquals(area.top, add.top, 0f)
    }
    @Test fun columns_followWidth() {
        host(width = 600, count = 2)
        val first = rule.onNodeWithTag("home_set_s1").fetchSemanticsNode().boundsInRoot
        val second = rule.onNodeWithTag("home_set_s2").fetchSemanticsNode().boundsInRoot
        assertEquals(first.left, second.left, 0f)
        assertTrue(second.top > first.top)
        controller.pause().stop().destroy()
        host(width = 700, count = 2)
        val wideFirst = rule.onNodeWithTag("home_set_s1").fetchSemanticsNode().boundsInRoot
        val wideSecond = rule.onNodeWithTag("home_set_s2").fetchSemanticsNode().boundsInRoot
        assertEquals(wideFirst.top, wideSecond.top, 0f)
        assertTrue(wideSecond.left > wideFirst.left)
    }
}
