package com.gt8d.launcher.settings

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import kotlin.math.roundToInt
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import com.gt8d.launcher.ui.theme.Palette
import com.gt8d.launcher.ui.theme.Dims
import com.gt8d.launcher.ui.layout.textLayout
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
import org.robolectric.annotation.GraphicsMode

// PixelCopy の非同期コールバックに依存せず、Robolectric の native Canvas で実描画を検証する。
internal fun SemanticsNodeInteraction.drawPixels(rule: ComposeContentTestRule, view: android.view.View): PixelMap {
    val bounds = fetchSemanticsNode().boundsInRoot
    val bitmap = rule.runOnIdle {
        android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888).also {
            view.draw(android.graphics.Canvas(it))
        }
    }
    return android.graphics.Bitmap.createBitmap(bitmap, bounds.left.roundToInt(), bounds.top.roundToInt(),
        bounds.width.roundToInt(), bounds.height.roundToInt()).asImageBitmap().toPixelMap()
}

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
        listOf("back" to "戻る").forEach { (tag, description) ->
            val node = rule.onNodeWithTag("settings_$tag")
            node.assertContentDescriptionEquals(description).assertWidthIsEqualTo(com.gt8d.launcher.ui.theme.Dims.MinTouch)
                .assertHeightIsEqualTo(com.gt8d.launcher.ui.theme.Dims.MinTouch)
            assertNull(node.fetchSemanticsNode().config.getOrElseNullable(SemanticsProperties.Text) { null })
            rule.onNodeWithText(description).assertDoesNotExist()
        }
        rule.onNodeWithTag("settings_close").assertDoesNotExist()
        rule.onNodeWithContentDescription("閉じる").assertDoesNotExist()
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

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun selectedChoiceAndPrimary_useWhiteSurfaceAndDarkText() {
        lateinit var view: android.view.View
        rule.setContent { LauncherTheme { Column(Modifier.background(Palette.Background0)) {
            view = LocalView.current
            SettingsChoice("選択", "choice", true, {}, Modifier.width(160.dp))
            SettingsChoice("未選択", "other_choice", false, {}, Modifier.width(160.dp))
            SettingsButton("保存", "primary", kind = SettingsButtonKind.Primary) {}
        } } }
        listOf("choice", "primary").forEach { tag ->
            val pixels = rule.onNodeWithTag(tag).drawPixels(rule, view)
            assertEquals(Palette.PanelAccent, pixels[pixels.width / 2, 12])
        }
        listOf("選択", "保存").forEach {
            assertEquals(Palette.Background0, rule.onNodeWithText(it, useUnmergedTree = true).textLayout().layoutInput.style.color)
        }
        val other = rule.onNodeWithTag("other_choice").drawPixels(rule, view)
        assertEquals(Palette.Background1, other[other.width / 2, 12])
        assertEquals(Palette.Background1, other[0, other.height / 2])
        assertEquals(androidx.compose.ui.graphics.Color.White,
            rule.onNodeWithText("未選択", useUnmergedTree = true).textLayout().layoutInput.style.color)
        val choice = rule.onNodeWithTag("choice").drawPixels(rule, view)
        assertEquals(Palette.Background0, choice[0, 0])
        assertEquals(Palette.PanelAccent, choice[0, choice.height / 2])
        assertEquals(Palette.PanelAccent, choice[1, 20])
    }

    @Test fun segments_notifySelection_andHaveEqualTouchTargets() {
        var selected by mutableStateOf(0)
        var enabled by mutableStateOf(true)
        val calls = mutableListOf<Int>()
        rule.setContent { LauncherTheme { Column(Modifier.width(360.dp)) {
            SettingsSegments(listOf("4:6" to "first", "5:5" to "second", "6:4" to "third"), selected,
                { selected = it; calls += it }, enabled = enabled)
        } } }
        rule.onNodeWithTag("first").assertIsSelected()
        rule.onNodeWithTag("second").performClick().assertIsSelected()
        rule.onNodeWithTag("first").assertIsNotSelected()
        assertEquals(listOf(1), calls)
        listOf("first", "second", "third").forEach {
            rule.onNodeWithTag(it).assertWidthIsEqualTo(120.dp).assertHeightIsEqualTo(Dims.MinTouch)
        }
        rule.runOnIdle { enabled = false }
        rule.onNodeWithTag("third").assertIsNotEnabled().performClick()
        rule.onNodeWithTag("second").assertIsSelected()
        assertEquals(listOf(1), calls)
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun appRow_placeholderHighlightAndClick() {
        var clicked = false
        rule.setContent { LauncherTheme {
            SettingsAppRow("アルファ", ImageBitmap(48, 48), "app", "アプリを選んでください", true) { clicked = true }
        } }
        rule.onNodeWithTag("app").assertHeightIsEqualTo(Dims.MinTouch)
            .assertTextContains("アプリを選んでください")
            .assert(SemanticsMatcher.expectValue(SettingsAppHighlighted, true)).performClick()
        assertTrue(clicked)
        rule.onNodeWithText("ア", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun appRow_highlightIsWhiteOutline_andMissingIconUsesInitial() {
        lateinit var view: android.view.View
        rule.setContent { LauncherTheme {
            view = LocalView.current
            SettingsAppRow("アルファ", null, "app", highlighted = true) {}
        } }
        rule.onNodeWithText("ア", useUnmergedTree = true).assertExists()
        val pixels = rule.onNodeWithTag("app").drawPixels(rule, view)
        assertEquals(Palette.PanelAccent, pixels[0, pixels.height / 2])
        assertEquals(Palette.Background1, pixels[pixels.width / 2, 4])
    }
}
