package com.gt8d.launcher.settings

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
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
@Config(sdk = [35], qualifiers = "w516dp-h1600dp")
class SettingsUiTest {
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

    @Test fun topBar_usesIcons_withDescriptions() {
        rule.setContent { LauncherTheme { SettingsTopBar("設定", {}, {}) } }
        listOf("back" to "戻る", "close" to "閉じる").forEach { (tag, description) ->
            val node = rule.onNodeWithTag("settings_$tag")
            node.assertContentDescriptionEquals(description).assertWidthIsEqualTo(com.gt8d.launcher.ui.theme.Dims.MinTouch)
                .assertHeightIsEqualTo(com.gt8d.launcher.ui.theme.Dims.MinTouch)
            assertNull(node.fetchSemanticsNode().config.getOrElseNullable(SemanticsProperties.Text) { null })
            rule.onNodeWithText(description).assertDoesNotExist()
        }
    }

    @Test fun root_hasThreeSections_inOrder() {
        rule.setContent { LauncherTheme {
            SettingsHost(SettingsActions({}, {}, { false }, {}, { true }), {}, {})
        } }
        val groups = listOf("画面" to listOf("sets", "lists", "link", "gesture", "boot"),
            "システム" to listOf("permissions", "apppair", "stock"), "元に戻す" to listOf("reset", "safety"))
        val headers = groups.map { rule.onNodeWithText(it.first).fetchSemanticsNode().positionInRoot.y }
        assertTrue(headers.zipWithNext().all { (a, b) -> a < b })
        groups.forEachIndexed { index, (title, items) ->
            items.forEach { id ->
                rule.onNodeWithTag("settings_item_$id").assert(hasAnyAncestor(hasTestTag("settings_section_$title")))
                val y = rule.onNodeWithTag("settings_item_$id").fetchSemanticsNode().positionInRoot.y
                assertTrue(y > headers[index])
                if (index < headers.lastIndex) assertTrue(y < headers[index + 1])
            }
        }
    }

    @Test fun row_kinds_showTrailingIcon() {
        rule.setContent { LauncherTheme { Column {
            SettingsRow("移動", "navigate", {}, kind = SettingsRowKind.Navigate)
            SettingsRow("別画面", "external", {}, kind = SettingsRowKind.External)
            SettingsRow("削除", "danger", {}, kind = SettingsRowKind.Danger)
        } } }
        rule.onNodeWithTag("navigate_trailing", useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SettingsTrailingIcon, "Navigate"))
        rule.onNodeWithTag("external_trailing", useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SettingsTrailingIcon, "External"))
        rule.onNodeWithTag("danger_trailing", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun choice_selected_isFilled_andSelected() {
        rule.setContent { LauncherTheme { Column {
            SettingsChoice("6:4", "selected", true, {})
            SettingsChoice("5:5", "other", false, {})
        } } }
        rule.onNodeWithTag("selected").assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SettingsChoiceFilled, true))
        rule.onNodeWithTag("other").assertIsNotSelected()
            .assert(SemanticsMatcher.expectValue(SettingsChoiceFilled, false))
    }

    @Test fun editScreen_hasAtMostOnePrimary() {
        val store = SetsStore(MutableStateFlow(emptyList()), { transform -> emptyList<com.gt8d.launcher.config.ScreenSet>() to transform(emptyList()) }, { 0 }, {})
        rule.setContent { LauncherTheme {
            SettingsHost(SettingsActions({}, {}, { false }, {}, { false }), {}, {},
                initialRoute = SettingsRoute.SetEdit(null), setsStore = store,
                labelFor = { it }, launchablePackages = { emptyList() })
        } }
        rule.onAllNodes(SemanticsMatcher.expectValue(SettingsPrimary, true)).assertCountEquals(1)
        rule.onNodeWithTag("set_save").assert(SemanticsMatcher.expectValue(SettingsPrimary, true))
    }
}
