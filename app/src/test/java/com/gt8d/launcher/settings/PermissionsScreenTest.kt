package com.gt8d.launcher.settings

import android.Manifest
import android.app.AppOpsManager
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import com.gt8d.launcher.split.SplitMode
import com.gt8d.launcher.ui.theme.LauncherTheme
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w516dp-h1000dp")
class PermissionsScreenTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    @get:Rule(order = 0) val register = object : ExternalResource() {
        override fun before() {
            val component = ComponentName(app, ComponentActivity::class.java)
            shadowOf(app.packageManager).addOrUpdateActivity(ActivityInfo().apply {
                name = component.className; packageName = component.packageName
            })
            shadowOf(app.packageManager).addIntentFilterForActivity(component,
                IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
        }
    }
    @get:Rule(order = 1) val rule = createComposeRule()
    private var currentMode = SplitMode.Vendor
    private var restoreCalls = 0
    private val allowed = mutableSetOf<PermissionItem>()
    private val opened = mutableListOf<PermissionItem>()
    private val owner = object : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private fun host() {
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        rule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                LauncherTheme {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        PermissionsScreen(object : PermissionChecker {
                            override fun granted(item: PermissionItem) = item in allowed
                        }, object : PermissionOpener {
                            override fun open(item: PermissionItem) { opened += item }
                        }, { currentMode }, { restoreCalls++; currentMode = SplitMode.Vendor })
                    }
                }
            }
        }
    }
    private fun resume() = rule.runOnIdle {
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }
    @Test fun splitStatus_showsMode_andRestoreButton() {
        host()
        rule.onNodeWithTag("split_status").assertTextEquals("メーカー方式で動作中")
        rule.onNodeWithTag("split_restore").assertDoesNotExist()
        rule.runOnIdle { currentMode = SplitMode.AppPair }; resume()
        rule.onNodeWithTag("split_status").assertTextEquals("予備方式で動作中")
        rule.onNodeWithTag("split_restore").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("perm_row_Accessibility").assertExists()
        rule.runOnIdle { currentMode = SplitMode.LeftOnly }; resume()
        rule.onNodeWithTag("split_status").assertTextEquals("分割が使えなくなりました")
        rule.onNodeWithTag("perm_row_Accessibility").assertDoesNotExist()
        rule.onNodeWithTag("split_restore").performScrollTo().performClick()
        assertEquals(1, restoreCalls)
        rule.onNodeWithTag("split_status").assertTextEquals("メーカー方式で動作中")
        rule.onNodeWithTag("split_restore").assertDoesNotExist()
    }
    @Test fun granted_showsGranted_noOpenButton() {
        allowed += PermissionItem.entries; host()
        permissionItems(SplitMode.Vendor).forEach {
            rule.onNodeWithTag("perm_row_$it").performScrollTo().assert(hasText(it.label) and hasText("許可済み"))
            rule.onNodeWithTag("perm_open_$it").assertDoesNotExist()
        }
    }
    @Test fun notGranted_open_callsOpener() {
        host()
        permissionItems(SplitMode.Vendor).forEach {
            rule.onNodeWithTag("perm_row_$it").assert(hasText("未許可"))
            rule.onNodeWithTag("perm_open_$it").performScrollTo().assertIsDisplayed()
                .assertWidthIsAtLeast(72.dp).assertHeightIsAtLeast(72.dp).performClick()
            rule.onNodeWithText(it.label).assertIsDisplayed()
        }
        assertEquals(permissionItems(SplitMode.Vendor), opened)
    }
    @Test fun resume_rereadsState() {
        host()
        rule.onNodeWithTag("perm_row_Home").assert(hasText("未許可"))
        rule.runOnIdle { allowed += PermissionItem.Home }; resume()
        rule.onNodeWithTag("perm_row_Home").assert(hasText("許可済み"))
        rule.onNodeWithTag("perm_open_Home").assertDoesNotExist()
    }
    @Test fun appPairPrep_showsNote() {
        rule.setContent { LauncherTheme { AppPairPrepScreen() } }
        rule.onNodeWithTag("apppair_note").assertIsDisplayed()
            .assertTextEquals("予備方式の準備は、今後の更新で使えるようになります")
    }
    @Test fun checker_overlay() {
        val checker = AndroidPermissionChecker(app)
        ShadowSettings.setCanDrawOverlays(false); assertFalse(checker.granted(PermissionItem.Overlay))
        ShadowSettings.setCanDrawOverlays(true); assertTrue(checker.granted(PermissionItem.Overlay))
    }
    @Test fun checker_bluetooth() {
        val checker = AndroidPermissionChecker(app)
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        assertFalse(checker.granted(PermissionItem.Bluetooth))
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        assertTrue(checker.granted(PermissionItem.Bluetooth))
    }
    @Test fun checker_usageAccess() {
        val checker = AndroidPermissionChecker(app)
        val ops = shadowOf(app.getSystemService(AppOpsManager::class.java))
        ops.setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), app.packageName, AppOpsManager.MODE_IGNORED)
        assertFalse(checker.granted(PermissionItem.UsageAccess))
        ops.setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), app.packageName, AppOpsManager.MODE_ALLOWED)
        assertTrue(checker.granted(PermissionItem.UsageAccess))
    }
}
