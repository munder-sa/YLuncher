package com.gt8d.launcher.settings

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.gt8d.launcher.config.*
import com.gt8d.launcher.ui.theme.Dims
import com.gt8d.launcher.ui.theme.LauncherTheme
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w516dp-h1000dp")
class DailyScreensTest {
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
    private var config by mutableStateOf(LauncherConfig(screenSets = emptyList(), homeApps = listOf("a", "b"), panelApps = emptyList(), dockApps = emptyList(), idleApp = "a"))
    private var writes = 0
    private val update: ((LauncherConfig) -> LauncherConfig) -> Unit = { config = it(config); writes++ }
    private fun host(route: SettingsRoute) {
        rule.setContent { LauncherTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            when (route) {
                SettingsRoute.Lists -> ListsScreen(config, update, { it }, { listOf("a", "b", "c", "d", "e") })
                SettingsRoute.Link -> LinkScreen(config, update)
                SettingsRoute.Gesture -> GestureScreen(config, update)
                SettingsRoute.Boot -> BootSettingsScreen(config, update)
                else -> error("unsupported")
            }
        } } }
    }
    private fun click(tag: String) { rule.onNodeWithTag(tag).performScrollTo().performClick() }
    @Test fun lists_addToDock_disabledAt4() {
        config = config.copy(dockApps = listOf("a", "b", "c", "d")); host(SettingsRoute.Lists)
        rule.onNodeWithTag("lists_dock_add").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("最大 4 個まで").assertExists()
        assertEquals(0, writes)
    }
    @Test fun lists_removeAndReorder_updatesImmediately() {
        host(SettingsRoute.Lists)
        rule.onNodeWithTag("lists_home_row_a").assertWidthIsAtLeast(Dims.MinTouch)
        listOf("up", "down", "remove").forEach {
            val node = rule.onNodeWithTag("lists_home_${it}_a").performScrollTo().assertIsDisplayed()
                .assertWidthIsAtLeast(Dims.MinTouch).assertHeightIsAtLeast(Dims.MinTouch)
            val bounds = node.fetchSemanticsNode().boundsInRoot
            val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.left >= root.left && bounds.right <= root.right)
        }
        click("lists_home_down_a"); assertEquals(listOf("b", "a"), config.homeApps)
        click("lists_home_up_a"); assertEquals(listOf("a", "b"), config.homeApps)
        click("lists_home_remove_a"); assertEquals(listOf("b"), config.homeApps)
        assertEquals(3, writes)
    }
    @Test fun lists_idleChange_setsIdleApp() {
        host(SettingsRoute.Lists); click("lists_idle_change"); click("lists_idle_pick_b")
        assertEquals("b", config.idleApp); assertEquals(1, writes)
    }
    @Test fun lists_add_disablesDuplicates_andSaves() {
        host(SettingsRoute.Lists); click("lists_home_add")
        rule.onNodeWithTag("lists_home_pick_a").assertIsNotEnabled()
        click("lists_home_pick_c"); assertEquals(listOf("a", "b", "c"), config.homeApps)
    }
    @Test fun link_switchToChrome_saves() {
        host(SettingsRoute.Link); click("link_mode_chrome")
        assertEquals(LinkMode.Chrome, config.link.mode); assertEquals(1, writes)
        rule.onNodeWithTag("link_mode_chrome").assertIsSelected()
    }
    @Test fun link_addDomain_normalizes() {
        host(SettingsRoute.Link)
        rule.onNodeWithTag("link_domain_input").performScrollTo().performTextInput(" HTTPS://Example.com/foo ")
        click("link_domain_add"); assertEquals(listOf("accounts.google.com", "example.com"), config.link.chromeDomains)
        click("link_domain_remove_example.com"); assertEquals(listOf("accounts.google.com"), config.link.chromeDomains)
    }
    @Test fun link_addInvalid_showsError() {
        host(SettingsRoute.Link)
        rule.onNodeWithTag("link_domain_input").performScrollTo().performTextInput("bad domain")
        click("link_domain_add"); rule.onNodeWithText("正しいドメインを入力してください").assertExists()
        assertEquals(0, writes)
    }
    @Test fun gesture_chooseWidth_saves() {
        host(SettingsRoute.Gesture); click("gesture_width_24")
        assertEquals(24, config.gesture.stripWidthPx); assertEquals(1, writes)
    }
    @Test fun gesture_unknownWidth_noneSelected() {
        config = config.copy(gesture = GestureConfig(stripWidthPx = 12, longPressMs = 600)); host(SettingsRoute.Gesture)
        listOf(24, 48, 72, 96).forEach { rule.onNodeWithTag("gesture_width_$it").assertIsNotSelected() }
        listOf(500, 750, 1000, 1500).forEach { rule.onNodeWithTag("gesture_longpress_$it").assertIsNotSelected() }
        assertEquals(12, config.gesture.stripWidthPx); assertEquals(600, config.gesture.longPressMs); assertEquals(0, writes)
    }
    @Test fun gesture_toggleGlow_saves() {
        host(SettingsRoute.Gesture); click("gesture_glow")
        assertFalse(config.gesture.glow); assertEquals(1, writes)
    }
    @Test fun gesture_chooseLongPress_saves() {
        host(SettingsRoute.Gesture); click("gesture_longpress_750")
        assertEquals(750, config.gesture.longPressMs); assertEquals(1, writes)
    }
    @Test fun boot_waitCar_isDisabled_withNote() {
        config = config.copy(boot = BootConfig(true)); host(SettingsRoute.Boot)
        rule.onNodeWithTag("boot_wait_car").assertIsNotEnabled().assertIsOn()
        rule.onNodeWithTag("boot_wait_car_note").assertTextEquals("車での確認の後に使えるようになります")
        assertTrue(config.boot.waitForCar); assertEquals(0, writes)
    }
}
