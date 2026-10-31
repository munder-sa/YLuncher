package com.gt8d.launcher.ui.home

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1000dp-h1000dp")
class HomeSetCardsTest {
    @get:Rule val rule = createEmptyComposeRule()
    private lateinit var controller: ActivityController<ComponentActivity>
    private fun sets(count: Int) = (1..count).map {
        ScreenSet("s$it", Slot.App("left"), if (it == 1) Slot.Panel else Slot.App("right"), SplitRatio.R6_4)
    }
    private fun host(width: Int = 700, height: Int = 800, count: Int = 1,
                     current: Int = 0, onOpen: (Int) -> Unit = {}, onAdd: () -> Unit = {},
                     leftLabel: String = "左アプリ", screenSets: List<ScreenSet> = sets(count), installed: Boolean = true) {
        controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller.get().setContent { LauncherTheme {
            Box(Modifier.size(width.dp, height.dp).testTag("area")) {
                HomeSetCards(screenSets, { AppInfo(it, if (it == "left") leftLabel else "右アプリ", null, installed) },
                    current, onOpen, onAdd, Modifier.fillMaxSize())
            }
        } }
        controller.visible()
    }
    @After fun cleanup() { if (::controller.isInitialized) controller.pause().stop().destroy() }
    @Test fun cards_showNamesAndPanelLabel() {
        host(count = 2)
        rule.onNodeWithText("1  左アプリ | パネル").assertExists()
        rule.onNodeWithText("2  左アプリ | 右アプリ").assertExists()
        rule.onAllNodesWithContentDescription("左アプリ", useUnmergedTree = true).assertCountEquals(2)
    }
    @Test fun missingApp_showsOnlyMissingTextInsideSlot_withoutOverlappingSetName() {
        host(height = 560, count = 5, installed = false)
        rule.onNodeWithText("左", useUnmergedTree = true).assertDoesNotExist()
        val slot = rule.onNodeWithTag("home_set_s1_left", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val missingNode = rule.onNode(hasText("見つかりません") and hasAnyAncestor(hasTestTag("home_set_s1_left")),
            useUnmergedTree = true).assertIsDisplayed()
        val missing = missingNode.fetchSemanticsNode().boundsInRoot
        assertTrue(missing.left >= slot.left)
        assertTrue(missing.right <= slot.right)
        assertTrue(missing.top >= slot.top)
        assertTrue(missing.bottom <= slot.bottom)
        assertEquals(slot.center.x, missing.center.x, 1f) // 座標の丸めの 0.5px を許す
        assertEquals(slot.center.y, missing.center.y, 1f) // 座標の丸めの 0.5px を許す
        val name = rule.onNodeWithText("1  左アプリ | パネル", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(missing.bottom <= name.top)
        val layouts = mutableListOf<TextLayoutResult>()
        missingNode.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.single().lineCount)
        rule.onNode(hasContentDescription("左アプリ(アプリが見つかりません)") and hasAnyAncestor(hasTestTag("home_set_s1_left")),
            useUnmergedTree = true).assertExists()
    }
    @Test fun panel_shows56dpIcon_withoutStandaloneText() {
        host()
        rule.onNodeWithContentDescription("パネル", useUnmergedTree = true)
            .assertExists().assertWidthIsEqualTo(56.dp).assertHeightIsEqualTo(56.dp)
        rule.onNodeWithText("パネル", useUnmergedTree = true).assertDoesNotExist()
    }
    @Test fun leftPanelSet_showsPanelIconOnLeft() {
        host(screenSets = listOf(ScreenSet("s1", Slot.Panel, Slot.App("right"), SplitRatio.R4_6)))
        val card = rule.onNodeWithTag("home_set_s1").fetchSemanticsNode().boundsInRoot
        val panel = rule.onNodeWithContentDescription("パネル", useUnmergedTree = true)
            .assertWidthIsEqualTo(56.dp).assertHeightIsEqualTo(56.dp)
            .fetchSemanticsNode().boundsInRoot
        assertTrue(panel.center.x < card.center.x)
        rule.onNodeWithText("1  パネル | 右アプリ").assertExists()
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
    @Test fun leftPanelSet_ratioWidths() {
        host(screenSets = listOf(ScreenSet("s1", Slot.Panel, Slot.App("right"), SplitRatio.R4_6)))
        val leftBlock = rule.onNodeWithTag("home_set_s1_left", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val rightBlock = rule.onNodeWithTag("home_set_s1_right", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertTrue(leftBlock.width < rightBlock.width)
    }
    @Test fun shortName_isCenteredInCard() {
        host()
        val card = rule.onNodeWithTag("home_set_s1").fetchSemanticsNode().boundsInRoot
        val name = rule.onNodeWithText("1  左アプリ | パネル", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertEquals(card.center.x, name.center.x, with(rule.density) { 1.dp.toPx() })
    }
    @Test fun longName_isOneLineAndFitsInCard() {
        val label = "長".repeat(40)
        host(leftLabel = label)
        val card = rule.onNodeWithTag("home_set_s1").fetchSemanticsNode().boundsInRoot
        val name = rule.onNodeWithText("1  $label | パネル", useUnmergedTree = true)
        val layouts = mutableListOf<TextLayoutResult>()
        name.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.size)
        assertEquals(1, layouts.single().lineCount)
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
    @Test fun fewCards_areVerticallyCentered() {
        host()
        val area = rule.onNodeWithTag("area").fetchSemanticsNode().boundsInRoot
        val first = rule.onNodeWithTag("home_set_s1").fetchSemanticsNode().boundsInRoot
        val last = rule.onNodeWithTag("home_set_add").fetchSemanticsNode().boundsInRoot
        assertEquals(first.top - area.top, area.bottom - last.bottom, 2f * area.height / 800f)
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
        rule.onNodeWithTag("home_set_s1").assertHeightIsEqualTo(159.dp)
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
    @Test fun fourSets_cardHeightExcludesAddRow() {
        host(height = 420, count = 4)
        for (index in 1..4) {
            val bounds = rule.onNodeWithTag("home_set_s$index").getBoundsInRoot()
            assertEquals(150f, bounds.height.value, 1f)
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
        assertEquals(area.center.y, add.center.y, 1f)
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
