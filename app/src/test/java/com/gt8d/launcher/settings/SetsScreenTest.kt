package com.gt8d.launcher.settings

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.LocalContext
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
    private fun host(route: SettingsRoute = SettingsRoute.Sets) {
        val store = SetsStore(sets, { change ->
            val before = sets.value
            val after = change(before)
            sets.value = after
            before to after
        }, { current }, { current = it })
        rule.setContent { LauncherTheme {
            activity = LocalContext.current as ComponentActivity
            SettingsHost(SettingsActions({}, {}, { false }, {}, { false }), { exits++ }, {},
                initialRoute = route, onOpenSet = { opened += it }, setsStore = store,
                labelFor = { if (it == "alpha") "アルファ" else "ベータ" },
                launchablePackages = { listOf("beta", activity.packageName, com.gt8d.launcher.apps.OwnPackages.PANEL_HOST, "alpha", "alpha") })
        } }
    }
    private fun click(tag: String) {
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
        rule.onNodeWithTag("set_right_label").assertTextEquals("右のアプリ: 選んでください")
        rule.onNodeWithTag("set_save").assertIsNotEnabled()
        click("set_right_app"); click("set_pick_beta"); click("set_save"); rule.waitForIdle()
        val saved = sets.value.last()
        assertEquals(ScreenSet(saved.id, Slot.Panel, Slot.App("beta"), SplitRatio.R6_4), saved)
    }
    @Test fun swap_exchangesAppLabelsAndRatio() {
        host(SettingsRoute.SetEdit("b"))
        rule.onNodeWithTag("set_left_label").assertTextEquals("左のアプリ: ベータ")
        rule.onNodeWithTag("set_right_label").assertTextEquals("右のアプリ: アルファ")
        rule.onNodeWithTag("set_swap").assertHeightIsAtLeast(com.gt8d.launcher.ui.theme.Dims.MinTouch)
        click("set_swap")
        rule.onNodeWithTag("set_left_label").assertTextEquals("左のアプリ: アルファ")
        rule.onNodeWithTag("set_right_label").assertTextEquals("右のアプリ: ベータ")
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
        rule.onNodeWithTag("set_up_a").assertIsNotEnabled()
        rule.onNodeWithTag("set_down_b").assertIsNotEnabled()
        listOf("set_row_a", "set_up_a", "set_down_a", "set_delete_a").forEach {
            rule.onNodeWithTag(it).assertWidthIsAtLeast(com.gt8d.launcher.ui.theme.Dims.MinTouch)
                .assertHeightIsAtLeast(com.gt8d.launcher.ui.theme.Dims.MinTouch).assertIsDisplayed()
        }
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
        assertEquals(listOf(a, b), sets.value); rule.onNodeWithTag("set_add").assertExists()
    }
    @Test fun edit_systemBack_discardsChanges() {
        host(); click("set_row_a"); click("set_ratio_5:5")
        rule.runOnIdle { activity.onBackPressedDispatcher.onBackPressed() }
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
        rule.onNodeWithTag("set_left_label").assertTextEquals("左のアプリ: 選んでください")
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
}
