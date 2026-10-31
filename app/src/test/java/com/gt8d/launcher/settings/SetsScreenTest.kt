package com.gt8d.launcher.settings

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Color
import com.gt8d.launcher.ui.layout.textLayout
import com.gt8d.launcher.ui.theme.Palette
import com.gt8d.launcher.ui.home.PanelIconMirrored
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.TextLayoutMode
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.gt8d.launcher.config.*
import com.gt8d.launcher.ui.theme.LauncherTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w516dp-h1000dp")
class SetsScreenTest {
    @get:Rule(order = 0) val register = object : ExternalResource() {
        override fun before() {
            val app = ApplicationProvider.getApplicationContext<Application>()
            val component = ComponentName(app, ComponentActivity::class.java)
            shadowOf(app.packageManager).addOrUpdateActivity(ActivityInfo().apply {
                name = component.className; packageName = component.packageName
            })
            shadowOf(app.packageManager).addIntentFilterForActivity(component,
                IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
        }
    }
    @get:Rule(order = 1) val rule = createComposeRule()
    private val a = ScreenSet("a", Slot.App("alpha"), Slot.Panel, SplitRatio.R6_4)
    private val b = ScreenSet("b", Slot.App("beta"), Slot.App("alpha"), SplitRatio.R3_7)
    private val sets = MutableStateFlow(listOf(a, b))
    private var current = 2
    private var exits = 0
    private val opened = mutableListOf<Int>()
    private lateinit var activity: ComponentActivity
    private lateinit var view: android.view.View
    private fun host(route: SettingsRoute = SettingsRoute.Sets, scale: Float = 1f,
        labels: (String) -> String = { if (it == "alpha") "アルファ" else "ベータ" },
        icons: (String) -> ImageBitmap? = { null },
        beforeWrite: suspend () -> Unit = {},
    ) {
        val store = SetsStore(sets, { change ->
            beforeWrite()
            val before = sets.value
            val after = change(before)
            sets.value = after
            before to after
        }, { current }, { current = it })
        rule.setContent { LauncherTheme {
            activity = LocalContext.current as ComponentActivity
            view = LocalView.current
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
            SettingsHost(SettingsActions({}, {}, { false }, {}, { false }), { exits++ }, {},
                initialRoute = route, onOpenSet = { opened += it }, setsStore = store,
                labelFor = labels, iconFor = icons,
                launchablePackages = { listOf("beta", activity.packageName, com.gt8d.launcher.apps.OwnPackages.PANEL_HOST, "alpha", "alpha") })
            }
        } }
    }
    private fun click(tag: String) {
        if (tag.startsWith("set_up_") || tag.startsWith("set_down_") ||
            (tag.startsWith("set_delete_") && tag != "set_delete_yes" && tag != "set_delete_no")) {
            rule.onNodeWithTag("set_menu_${tag.substringAfterLast('_')}").performScrollTo().performClick()
            rule.onNodeWithTag(tag).performClick()
            return
        }
        val node = rule.onNodeWithTag(tag)
        if (!tag.startsWith("settings_")) node.performScrollTo()
        node.performClick()
    }
    private fun chooseLeft() { click("set_left_app"); click("set_pick_alpha") }
    @Test fun sets_editSlots_canChoosePanelOnEitherSide() {
        host(SettingsRoute.SetEdit(null)); click("set_left_app")
        rule.onNodeWithTag("set_pick_${activity.packageName}").assertDoesNotExist()
        rule.onNodeWithTag("set_pick_${com.gt8d.launcher.apps.OwnPackages.PANEL_HOST}").assertDoesNotExist()
        rule.onNodeWithTag("set_right_panel").assertDoesNotExist()
        rule.onAllNodesWithTag("set_pick_alpha").assertCountEquals(1)
        val alphaY = rule.onNodeWithTag("set_pick_alpha").fetchSemanticsNode().positionInRoot.y
        assertTrue(alphaY < rule.onNodeWithTag("set_pick_beta").fetchSemanticsNode().positionInRoot.y)
        click("set_pick_alpha"); click("set_right_app"); click("set_pick_beta"); click("set_right_panel")
        click("set_save"); rule.waitForIdle(); assertEquals(Slot.Panel, sets.value.last().right)
    }
    @Test fun leftPanel_selectRightApp_saves() {
        host(SettingsRoute.SetEdit(null)); click("set_left_panel")
        rule.onNodeWithTag("set_left_panel").assertIsSelected()
        rule.onNodeWithTag("set_right_panel").assertIsEnabled().assertIsNotSelected()
        rule.onNodeWithTag("set_right_label", useUnmergedTree = true).assertTextEquals("アプリを選んでください")
        rule.onNodeWithTag("set_save").assertIsNotEnabled()
        click("set_right_app"); click("set_pick_beta"); click("set_save"); rule.waitForIdle()
        val saved = sets.value.last()
        assertEquals(ScreenSet(saved.id, Slot.Panel, Slot.App("beta"), SplitRatio.R6_4), saved)
    }
    @Test fun swap_exchangesAppLabelsAndRatio() {
        host(SettingsRoute.SetEdit("b"))
        rule.onNodeWithTag("set_left_label", useUnmergedTree = true).assertTextEquals("ベータ")
        rule.onNodeWithTag("set_right_label", useUnmergedTree = true).assertTextEquals("アルファ")
        rule.onNodeWithTag("set_swap").assertHeightIsAtLeast(com.gt8d.launcher.ui.theme.Dims.MinTouch)
        click("set_swap")
        rule.onNodeWithTag("set_left_label", useUnmergedTree = true).assertTextEquals("アルファ")
        rule.onNodeWithTag("set_right_label", useUnmergedTree = true).assertTextEquals("ベータ")
        rule.onNodeWithTag("set_ratio_7:3").assertIsSelected()
    }
    @Test fun list_leftPanel_showsActualOrder() {
        sets.value = listOf(a.copy(left = Slot.Panel, right = Slot.App("alpha")))
        host(); rule.onNodeWithTag("set_row_a").assertTextContains("パネル | アルファ")
    }
    @Test fun sets_ratioChoices_panelHas3_appHas5() {
        host(SettingsRoute.SetEdit(null))
        rule.onNodeWithTag("set_ratio_6:4").assertIsSelected()
        listOf("4:6", "5:5", "6:4").forEach { rule.onNodeWithTag("set_ratio_$it").assertExists() }
        listOf("3:7", "7:3").forEach { rule.onNodeWithTag("set_ratio_$it").assertDoesNotExist() }
        click("set_right_app"); click("set_pick_beta")
        SplitRatio.entries.forEach { rule.onNodeWithTag("set_ratio_${it.label}").assertTextContains(it.label) }
        click("set_ratio_7:3"); click("set_right_panel")
        rule.onNodeWithTag("set_ratio_6:4").assertIsSelected()
    }
    @Test fun list_showsSetsWithRatio() {
        host(); rule.onNodeWithTag("set_row_a").assertTextContains("アルファ | パネル").assertTextContains("6:4")
        rule.onNodeWithTag("set_row_b").assertTextContains("ベータ | アルファ").assertTextContains("3:7")
        listOf("set_row_a", "set_menu_a").forEach {
            rule.onNodeWithTag(it).assertWidthIsAtLeast(com.gt8d.launcher.ui.theme.Dims.MinTouch)
                .assertHeightIsAtLeast(com.gt8d.launcher.ui.theme.Dims.MinTouch).assertIsDisplayed()
        }
        click("set_menu_a")
        rule.onNodeWithTag("set_up_a").assertIsNotEnabled()
        listOf("set_up_a", "set_down_a", "set_delete_a").forEach {
            rule.onNodeWithTag(it).assertWidthIsAtLeast(com.gt8d.launcher.ui.theme.Dims.MinTouch)
                .assertHeightIsAtLeast(com.gt8d.launcher.ui.theme.Dims.MinTouch).assertIsDisplayed()
        }
        rule.onNodeWithTag("set_delete_a").performClick()
        rule.onNodeWithTag("set_delete_no").performClick()
        click("set_menu_b")
        rule.onNodeWithTag("set_down_b").assertIsNotEnabled()
        rule.onNodeWithTag("set_delete_b").performClick()
        rule.onNodeWithTag("set_delete_no").performClick()
        assertEquals(listOf(a, b), sets.value)
        click("set_add"); rule.onNodeWithTag("set_save").performScrollTo().assertIsDisplayed()
    }
    @Test fun add_save_appendsSet() {
        host(); click("set_add"); chooseLeft(); click("set_save"); rule.waitForIdle()
        assertEquals(listOf(a, b), sets.value.take(2)); assertEquals(3, sets.value.size)
        assertEquals(Slot.App("alpha"), sets.value.last().left); assertEquals(SplitRatio.R6_4, sets.value.last().ratio)
        assertTrue(opened.isEmpty()); rule.onNodeWithTag("set_add").assertExists()
    }
    @Test fun edit_back_discardsChanges() {
        host(); click("set_row_a"); click("set_ratio_5:5"); click("settings_back")
        rule.onNodeWithText("変更を保存しますか?").assertIsDisplayed()
        rule.onNodeWithTag("set_unsaved_discard").performClick()
        assertEquals(listOf(a, b), sets.value); rule.onNodeWithTag("set_add").assertExists()
    }
    @Test fun edit_systemBack_discardsChanges() {
        host(); click("set_row_a"); click("set_ratio_5:5")
        rule.runOnIdle { activity.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithText("変更を保存しますか?").assertIsDisplayed()
        rule.onNodeWithTag("set_unsaved_discard").performClick()
        assertEquals(listOf(a, b), sets.value); rule.onNodeWithTag("set_add").assertExists()
    }
    @Test fun save_disabledUntilLeftChosen() {
        host(SettingsRoute.SetEdit(null)); rule.onNodeWithTag("set_save").assertIsNotEnabled()
        chooseLeft(); rule.onNodeWithTag("set_save").assertIsEnabled()
    }
    @Test fun delete_requiresConfirm() {
        host(); click("set_delete_a"); assertEquals(listOf(a, b), sets.value)
        rule.onNodeWithText("このセットを削除しますか?").assertIsDisplayed()
        rule.onNodeWithTag("set_delete_no").performClick(); assertEquals(listOf(a, b), sets.value)
        click("set_delete_a"); rule.onNodeWithTag("set_delete_yes").performClick(); rule.waitForIdle()
        assertEquals(listOf(b), sets.value)
    }
    @Test fun moveUp_reordersAndKeepsCurrentById() {
        host(); click("set_up_b"); rule.waitForIdle(); assertEquals(listOf(b, a), sets.value); assertEquals(1, current)
    }
    @Test fun deleteCurrent_setsIndexHome() {
        host(); click("set_delete_b"); rule.onNodeWithTag("set_delete_yes").performClick(); rule.waitForIdle()
        assertEquals(listOf(a), sets.value); assertEquals(0, current)
    }
    @Test fun fromSplitButton_save_opensNewSetIndex() {
        host(SettingsRoute.SetEdit(null, true)); chooseLeft(); click("set_save"); rule.waitForIdle()
        assertEquals(listOf(3), opened); assertEquals(3, sets.value.size)
    }
    @Test fun fromSplitButton_back_exits() {
        host(SettingsRoute.SetEdit(null, true)); click("settings_back"); assertEquals(1, exits)
        assertEquals(listOf(a, b), sets.value)
    }
    @Test fun unresizeableNote_isShown() {
        host(SettingsRoute.SetEdit(null))
        rule.onNodeWithTag("set_unresizeable_note").performScrollTo()
            .assertTextEquals("分割に対応していないアプリは、分割できないことがあります").assertIsDisplayed()
    }
    @Test fun emptyList_showsAdd() {
        sets.value = emptyList(); host()
        rule.onNodeWithText("セットがありません").assertIsDisplayed()
        rule.onNodeWithTag("set_add").assertIsDisplayed()
        rule.onNodeWithTag("set_row_a").assertDoesNotExist()
    }
    @Test fun picker_systemBack_keepsDraft() {
        host(SettingsRoute.SetEdit(null)); click("set_left_app")
        rule.runOnIdle { activity.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithTag("set_left_label", useUnmergedTree = true).assertTextEquals("アプリを選んでください")
        rule.onNodeWithTag("set_save").assertIsNotEnabled()
        assertEquals(0, exits)
    }
    @Test fun missingExistingId_returnsToList() {
        host(SettingsRoute.SetEdit("missing")); rule.onNodeWithTag("set_add").assertExists()
        rule.onNodeWithTag("set_row_a").assertExists()
    }
    @Test fun edit_save_replacesAndKeepsPairLabel() {
        val original = a.copy(appPairLabel = "ペア")
        sets.value = listOf(original, b)
        host(); click("set_row_a"); click("set_ratio_5:5"); click("set_save"); rule.waitForIdle()
        assertEquals(listOf(original.copy(ratio = SplitRatio.R5_5), b), sets.value)
        assertTrue(opened.isEmpty())
    }
    @Test fun fromSplitButton_systemBack_exits() {
        host(SettingsRoute.SetEdit(null, true))
        rule.runOnIdle { activity.onBackPressedDispatcher.onBackPressed() }
        assertEquals(1, exits); assertEquals(listOf(a, b), sets.value)
    }

    @Test fun edit_unchangedBack_returnsWithoutConfirmation() {
        host(); click("set_row_a"); click("settings_back")
        rule.onNodeWithText("変更を保存しますか?").assertDoesNotExist()
        rule.onNodeWithTag("set_add").assertExists()
        assertEquals(listOf(a, b), sets.value)
    }

    @Test fun edit_continue_keepsDraft_thenDialogSavePersists() {
        host(); click("set_row_a"); click("set_ratio_5:5"); click("settings_back")
        rule.onNodeWithTag("set_unsaved_continue").performClick()
        rule.onNodeWithTag("set_ratio_5:5").assertIsSelected()
        assertEquals(listOf(a, b), sets.value)
        click("settings_back"); rule.onNodeWithTag("set_unsaved_save").performClick()
        rule.waitForIdle()
        assertEquals(listOf(a.copy(ratio = SplitRatio.R5_5), b), sets.value)
        rule.onNodeWithTag("set_add").assertExists()
    }

    @Test fun invalidDraft_dialogSaveIsDisabled() {
        host(SettingsRoute.SetEdit(null)); click("set_left_panel"); click("settings_back")
        rule.onNodeWithTag("set_unsaved_save").assertIsNotEnabled()
        rule.onNodeWithTag("set_unsaved_continue").performClick()
        rule.onNodeWithTag("set_left_panel").assertIsSelected()
        assertEquals(listOf(a, b), sets.value)
    }

    @Test fun fromSplitButton_dialogSave_opensSavedSet() {
        host(SettingsRoute.SetEdit(null, true)); chooseLeft(); click("settings_back")
        rule.onNodeWithTag("set_unsaved_save").performClick(); rule.waitForIdle()
        assertEquals(listOf(3), opened)
        assertEquals(Slot.App("alpha"), sets.value.last().left)
    }

    @Test fun dialogSave_failure_keepsDraftAndShowsError() {
        host(SettingsRoute.SetEdit("a"), beforeWrite = { throw java.io.IOException("test") })
        click("set_ratio_5:5"); click("settings_back")
        rule.onNodeWithTag("set_unsaved_save").performClick(); rule.waitForIdle()
        rule.onNodeWithText("保存できませんでした。もう一度お試しください").assertExists()
        assertEquals(Palette.Danger,
            rule.onNodeWithText("保存できませんでした。もう一度お試しください").textLayout().layoutInput.style.color)
        rule.onNodeWithTag("set_ratio_5:5").assertIsSelected()
        assertEquals(listOf(a, b), sets.value)
        assertEquals(0, exits)
    }

    @Test fun saving_ignoresHeaderAndSystemBack() {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        host(beforeWrite = { gate.await() }); click("set_row_a")
        click("set_ratio_5:5"); click("set_save"); click("settings_back")
        rule.runOnIdle { activity.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithText("変更を保存しますか?").assertDoesNotExist()
        rule.onNodeWithTag("set_save").assertIsNotEnabled()
        assertEquals(listOf(a, b), sets.value)
        rule.runOnIdle { gate.complete(Unit) }; rule.waitForIdle()
        assertEquals(listOf(a.copy(ratio = SplitRatio.R5_5), b), sets.value)
        rule.onNodeWithTag("set_add").assertExists()
    }

    @Test fun appRow_opensPicker_andHeaderBackKeepsDraft() {
        host(SettingsRoute.SetEdit("a")); click("set_ratio_5:5"); click("set_left")
        rule.onNodeWithTag("set_pick_alpha").assertExists()
        click("settings_back")
        rule.onNodeWithTag("set_ratio_5:5").assertIsSelected()
        rule.onNodeWithText("変更を保存しますか?").assertDoesNotExist()
        click("set_left"); click("set_pick_beta")
        rule.onNodeWithTag("set_left_label", useUnmergedTree = true).assertTextEquals("ベータ")
    }

    @Test fun rightPanel_missingLeft_highlightsAndExplains() {
        host(SettingsRoute.SetEdit(null)); click("set_right_panel")
        rule.onNodeWithTag("set_left").assert(SemanticsMatcher.expectValue(SettingsAppHighlighted, true))
            .assertTextContains("アプリを選んでください")
        rule.onNodeWithText("反対側のアプリを選んでください").assertExists()
        rule.onNodeWithText("変更は自動で保存されます").assertDoesNotExist()
        listOf("左", "右", "比率").forEach {
            assertEquals(Palette.Muted, rule.onNodeWithText(it).textLayout().layoutInput.style.color)
        }
        assertEquals(Palette.Muted, rule.onNodeWithTag("set_unresizeable_note").textLayout().layoutInput.style.color)
    }

    @Test fun list_moveDown_reorders() {
        host(); click("set_down_a"); rule.waitForIdle()
        assertEquals(listOf(b, a), sets.value)
        assertEquals(1, current)
        rule.onNodeWithText("変更は自動で保存されます").assertDoesNotExist()
    }

    @Test fun list_panelIcons_haveSideOrderAndMirroring() {
        sets.value = listOf(a, b.copy(left = Slot.Panel))
        host()
        rule.onNodeWithTag("set_icon_a_1", useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(PanelIconMirrored, false)).assertWidthIsEqualTo(48.dp)
        rule.onNodeWithTag("set_icon_b_0", useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(PanelIconMirrored, true)).assertWidthIsEqualTo(48.dp)
        listOf("a", "b").forEach { id ->
            val left = rule.onNodeWithTag("set_icon_${id}_0", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val right = rule.onNodeWithTag("set_icon_${id}_1", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue(left.right <= right.left)
        }
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun list_appIcons_receiveCatalogImagesInSlotOrder() {
        fun bitmap(color: Color): ImageBitmap = ImageBitmap(48, 48).also {
            androidx.compose.ui.graphics.Canvas(it).drawRect(0f, 0f, 48f, 48f,
                androidx.compose.ui.graphics.Paint().apply { this.color = color })
        }
        val alpha = bitmap(Color.Red)
        val beta = bitmap(Color.Blue)
        host(icons = { if (it == "alpha") alpha else beta })
        listOf("set_icon_b_0" to Color.Blue, "set_icon_b_1" to Color.Red).forEach { (tag, color) ->
            val pixels = rule.onNodeWithTag(tag, useUnmergedTree = true).drawPixels(rule, view)
            assertEquals(color, pixels[pixels.width / 2, pixels.height / 2])
        }
    }

    @Test @Config(qualifiers = "w645dp-h1000dp")
    fun ratios_areOneRow_whenAllFiveFit() {
        host(SettingsRoute.SetEdit("b"))
        val nodes = SplitRatio.entries.map { rule.onNodeWithTag("set_ratio_${it.label}").fetchSemanticsNode() }
        assertEquals(1, nodes.map { it.positionInRoot.y }.distinct().size)
        SplitRatio.entries.forEach { rule.onNodeWithTag("set_ratio_${it.label}").assertWidthIsAtLeast(72.dp) }
    }

    @Test @Config(qualifiers = "w360dp-h726dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE) @TextLayoutMode(TextLayoutMode.Mode.REALISTIC)
    fun narrow_longNamesAndMissingApp_atLargeFont_fit() {
        host(scale = 1.3f, labels = { "とても長いアプリケーションの名前 $it" }); click("set_row_b")
        click("set_left_panel"); click("set_right_panel")
        rule.onNodeWithTag("set_left").performScrollTo().assertIsDisplayed().assertHeightIsEqualTo(72.dp)
        rule.onNodeWithTag("set_left_label", useUnmergedTree = true).assertTextEquals("アプリを選んでください")
        rule.onNodeWithText("反対側のアプリを選んでください").performScrollTo()
        assertEquals(1, rule.onNodeWithText("反対側のアプリを選んでください").textLayout().lineCount)
        click("set_left"); click("set_pick_alpha")
        val label = rule.onNodeWithTag("set_left_label", useUnmergedTree = true).performScrollTo()
        assertEquals(1, label.textLayout().lineCount)
        val app = rule.onNodeWithTag("set_left").fetchSemanticsNode().boundsInRoot
        val choice = rule.onNodeWithTag("set_left_app").fetchSemanticsNode().boundsInRoot
        assertTrue(choice.bottom <= app.top)
        click("set_right_app"); click("set_pick_beta")
        SplitRatio.entries.forEach { rule.onNodeWithTag("set_ratio_${it.label}").performScrollTo().assertWidthIsAtLeast(72.dp) }
        val top = rule.onNodeWithTag("set_ratio_3:7").fetchSemanticsNode().positionInRoot.y
        val bottom = rule.onNodeWithTag("set_ratio_7:3").fetchSemanticsNode().positionInRoot.y
        assertTrue(top < bottom)
        click("set_save"); rule.waitForIdle()
        rule.onNodeWithTag("set_row_b").performScrollTo().assertIsDisplayed()
        val name = rule.onNodeWithText("とても長いアプリケーションの名前 alpha | とても長いアプリケーションの名前 beta", useUnmergedTree = true)
        assertEquals(1, name.textLayout().lineCount)
    }
}
