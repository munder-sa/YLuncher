package com.gt8d.launcher.settings

import android.app.Application
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ApplicationProvider
import com.gt8d.launcher.AppGraph
import com.gt8d.launcher.config.Defaults
import com.gt8d.launcher.safety.SafetyGuard
import com.gt8d.launcher.ui.home.HomeActivity
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w516dp-h1000dp")
class SettingsActivityTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private var controller: ActivityController<SettingsActivity>? = null
    private lateinit var safetyFile: File
    private var safety: String? = null
    private var handled = false
    @Before fun prepare() {
        AppGraph.init(app); safetyFile = File(app.filesDir, "safety.json")
        safety = safetyFile.takeIf { it.exists() }?.readText()
        handled = AppGraph.bootHandled; SafetyGuard.clear(app)
    }
    @After fun cleanup() {
        controller?.pause()?.stop()?.destroy()
        safety?.let { safetyFile.writeText(it) } ?: safetyFile.delete()
        AppGraph.bootHandled = handled
    }
    private fun host() {
        controller = Robolectric.buildActivity(SettingsActivity::class.java).create().start().resume().visible()
        rule.waitForIdle()
    }
    @Test fun close_finishes() {
        host(); rule.onNodeWithTag("settings_close").performClick(); assertTrue(controller!!.get().isFinishing)
    }
    @Test fun permissionsRoute_opensPermissionCheck() {
        controller = Robolectric.buildActivity(SettingsActivity::class.java,
            Intent(app, SettingsActivity::class.java).putExtra("open_route", "permissions"))
            .create().start().resume().visible()
        rule.onNodeWithText("許可のチェック").assertIsDisplayed()
        rule.onNodeWithTag("perm_row_Notification").assertExists()
        rule.onNodeWithTag("settings_item_sets").assertDoesNotExist()
    }
    @Test fun safetyCleared_startsHome_andFinishes() {
        SafetyGuard.recordStart(app); repeat(3) { SafetyGuard.recordCrash(app) }
        assertTrue(SafetyGuard.isFallbackActive(app)); host()
        rule.onNodeWithTag("settings_item_safety").performScrollTo().performClick()
        val intent = shadowOf(controller!!.get()).nextStartedActivity
        assertEquals(HomeActivity::class.java.name, intent.component?.className)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP, intent.flags)
        assertFalse(SafetyGuard.isFallbackActive(app)); assertTrue(AppGraph.bootHandled)
        assertTrue(controller!!.get().isFinishing)
    }
    @Test fun forApp_reset_restoresDefaults_keepsLastSetIndex() = runBlocking {
        val saved = AppGraph.config.config.first()
        val indexFile = AppGraph.config.javaClass.getDeclaredField("lastSetFile")
            .apply { isAccessible = true }.get(AppGraph.config) as File
        val index = indexFile.takeIf { it.exists() }?.readText()
        try {
            val defaults = Defaults.build(AppGraph.catalog.installed())
            AppGraph.config.update { defaults.copy(gesture = defaults.gesture.copy(glow = false), boot = defaults.boot.copy(waitForCar = true)) }
            assertNotEquals(defaults, AppGraph.config.config.first())
            AppGraph.config.lastSetIndex = 3
            SettingsActions.forApp(app).resetToDefaults()
            val result = withTimeout(5000) { AppGraph.config.config.first { it == defaults } }
            assertEquals(defaults, result); assertEquals(3, AppGraph.config.lastSetIndex)
        } finally {
            AppGraph.config.update { saved }
            index?.let { indexFile.writeText(it) } ?: indexFile.delete()
        }
    }
}
