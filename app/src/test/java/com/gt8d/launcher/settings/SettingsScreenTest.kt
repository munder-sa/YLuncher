package com.gt8d.launcher.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.gt8d.launcher.ui.theme.LauncherTheme
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.junit.rules.ExternalResource
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1280dp-h1000dp")
class SettingsScreenTest {
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
    private val calls = mutableListOf<String>()
    private lateinit var activity: ComponentActivity
    private val items = listOf(
        "sets" to "画面セット", "lists" to "パネルとホーム", "link" to "リンクの行き先",
        "gesture" to "操作", "boot" to "起動時", "permissions" to "許可のチェック",
        "stock" to "純正ランチャーに戻す", "reset" to "デフォルトに戻す", "safety" to "安全モードを解除",
    )
    private fun host(safety: Boolean = false, prep: Boolean = false) {
        rule.setContent { LauncherTheme {
            activity = androidx.compose.ui.platform.LocalContext.current as ComponentActivity
            SettingsHost(SettingsActions(
                { calls += "reset" }, { calls += "stock" }, { safety },
                { calls += "clear" }, { prep },
            ), { calls += "exit" }, { calls += "cleared" })
        } }
    }
    private fun click(id: String) { rule.onNodeWithTag("settings_$id").performScrollTo().performClick() }
    @Test fun root_listsItemsInSpecOrder() {
        host()
        rule.onNodeWithTag("settings_item_apppair").assertDoesNotExist()
        var previous = Float.NEGATIVE_INFINITY
        items.forEach { (id, label) ->
            val node = rule.onNodeWithTag("settings_item_$id").assert(hasText(label))
            val y = node.fetchSemanticsNode().positionInRoot.y
            assertTrue("$id order", y > previous); previous = y
        }
    }
    @Test fun root_showsAppPairPrep_whenNotVendor() {
        host(prep = true); rule.onNodeWithTag("settings_item_apppair").performScrollTo().assertIsDisplayed()
    }
    @Test fun reset_requiresConfirm() {
        host(); click("item_reset"); assertTrue(calls.isEmpty())
        rule.onNodeWithText("設定をデフォルトに戻しますか?").assertIsDisplayed()
        rule.onNodeWithTag("settings_reset_yes").performClick(); assertEquals(listOf("reset"), calls)
    }
    @Test fun reset_no_doesNotReset() {
        host(); click("item_reset"); rule.onNodeWithTag("settings_reset_no").performClick(); assertTrue(calls.isEmpty())
    }
    @Test fun stock_opensHomeChooser() { host(); click("item_stock"); assertEquals(listOf("stock"), calls) }
    @Test fun safety_inactive_isDisabled() {
        host(); rule.onNodeWithTag("settings_item_safety").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("安全モードではありません").assertIsDisplayed(); assertTrue(calls.isEmpty())
    }
    @Test fun safety_active_clearsThenCallsCleared() {
        host(safety = true); click("item_safety"); assertEquals(listOf("clear", "cleared"), calls)
    }
    @Test fun detail_backReturnsToRoot() {
        host(); click("item_sets"); rule.onNodeWithTag("settings_item_sets").assertDoesNotExist()
        rule.onNodeWithTag("settings_back").performClick(); rule.onNodeWithTag("settings_item_sets").assertIsDisplayed()
    }
    @Test fun back_atRoot_exits() { host(); rule.onNodeWithTag("settings_back").performClick(); assertEquals(listOf("exit"), calls) }
    @Test fun systemBack_atRoot_exits() {
        host()
        rule.runOnIdle { activity.onBackPressedDispatcher.onBackPressed() }
        assertEquals(listOf("exit"), calls)
    }
    @Test fun close_inDetail_exits() {
        host(); click("item_sets"); rule.onNodeWithTag("settings_close").performClick(); assertEquals(listOf("exit"), calls)
    }
    @Test @Config(qualifiers = "w516dp-h1000dp") fun narrowPanel_itemsFit() {
        host(prep = true)
        (items + ("apppair" to "予備方式の準備")).forEach { (id, label) ->
            rule.onNodeWithTag("settings_item_$id").performScrollTo().assertIsDisplayed().assert(hasText(label))
            rule.onNodeWithText(label).assertIsDisplayed()
        }
    }
}
