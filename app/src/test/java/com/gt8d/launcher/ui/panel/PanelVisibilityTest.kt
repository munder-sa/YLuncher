package com.gt8d.launcher.ui.panel

import android.app.Application
import android.app.AppOpsManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import com.gt8d.launcher.AppGraph
import com.gt8d.launcher.apps.AppCatalog
import com.gt8d.launcher.apps.AppCatalogState
import com.gt8d.launcher.config.Defaults
import com.gt8d.launcher.media.MediaWatcher
import com.gt8d.launcher.media.NowPlaying
import com.gt8d.launcher.ui.theme.LauncherTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1280dp-h1000dp")
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PanelVisibilityTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val owner = PanelTestLifecycle()
    private val composed = mutableStateOf(true)
    private var shared: AppCatalogState? = null
    private lateinit var result: PanelEnvironment
    private var wifi: WifiIndicator = WifiIndicator.Off
    private var wifiReads = 0
    private val originalWifi = WifiReader.read
    private val registrations = mutableListOf<BroadcastReceiver>()
    private val releases = mutableListOf<BroadcastReceiver>()
    private var permissionReads = 0
    private var resolverReads = 0
    private var failRadioRegistration = false
    private var failRadioRelease = false
    private val context by lazy { object : ContextWrapper(app) {
        override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter,
            permission: String?, scheduler: Handler?, flags: Int): Intent? {
            if (receiver != null && filter.hasAction(WifiManager.RSSI_CHANGED_ACTION)) {
                if (failRadioRegistration) throw SecurityException("Test radio registration failure")
                registrations.add(receiver)
            }
            return super.registerReceiver(receiver, filter, permission, scheduler, flags)
        }
        override fun unregisterReceiver(receiver: BroadcastReceiver) {
            if (receiver in registrations) releases.add(receiver)
            super.unregisterReceiver(receiver)
            if (receiver in registrations && failRadioRelease) throw IllegalArgumentException("Test radio release failure")
        }
        override fun getSystemService(name: String): Any? {
            if (name == Context.APP_OPS_SERVICE) permissionReads++
            return super.getSystemService(name)
        }
        override fun getContentResolver(): android.content.ContentResolver {
            resolverReads++
            return super.getContentResolver()
        }
    } }

    @get:Rule(order = 0) val register = object : ExternalResource() {
        override fun before() {
            val cn = ComponentName(app.packageName, ComponentActivity::class.java.name)
            Shadows.shadowOf(app.packageManager).addOrUpdateActivity(ActivityInfo().apply {
                name = cn.className; packageName = cn.packageName
            })
            Shadows.shadowOf(app.packageManager).addIntentFilterForActivity(cn,
                IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
            WifiReader.read = { wifiReads++; wifi }
        }
    }
    @get:Rule(order = 1) val rule = createComposeRule()

    @After fun close() {
        shared?.close()
        AppGraph.appCatalogStateForTest?.close()
        AppGraph.appCatalogStateForTest = null
        WifiReader.read = originalWifi
    }

    private fun host(useShared: Boolean) {
        val catalog = AppCatalog(app)
        if (useShared) shared = AppCatalogState(app, catalog, UnconfinedTestDispatcher())
        rule.runOnIdle { owner.stop() }
        rule.setContent {
            if (composed.value) CompositionLocalProvider(LocalContext provides context, LocalLifecycleOwner provides owner) {
                result = if (useShared) rememberPanelEnvironment(shared!!) else rememberPanelEnvironment(catalog)
            }
        }
        rule.waitForIdle()
    }

    private fun permissions(enabled: Boolean) {
        ShadowSettings.setCanDrawOverlays(enabled)
        Settings.Secure.putString(app.contentResolver, "enabled_notification_listeners",
            if (enabled) "${app.packageName}/.media.MediaWatcher" else "")
        Shadows.shadowOf(app.getSystemService(AppOpsManager::class.java)).setMode(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), app.packageName,
            if (enabled) AppOpsManager.MODE_ALLOWED else AppOpsManager.MODE_IGNORED)
    }

    private fun verifyVisibility(useShared: Boolean) {
        val connectivity = Shadows.shadowOf(app.getSystemService(ConnectivityManager::class.java))
        val before = connectivity.networkCallbacks.toSet()
        host(useShared)
        assertTrue(registrations.isEmpty())
        assertEquals(before, connectivity.networkCallbacks.toSet())
        rule.runOnIdle {
            wifi = WifiIndicator.Connected(4)
            owner.registry.currentState = Lifecycle.State.STARTED
        }
        rule.runOnIdle { assertEquals(wifi, result.dockStatus.wifi) }
        assertEquals(1, registrations.size)
        val oldCallback = (connectivity.networkCallbacks.toSet() - before).single()
        val oldReceiver = registrations.single()
        val network = ShadowNetwork.newInstance(43)
        val capabilities = ShadowNetworkCapabilities.newInstance()
        rule.runOnIdle {
            owner.resume()
            owner.registry.currentState = Lifecycle.State.STARTED
            wifi = WifiIndicator.Connected(1)
            oldCallback.onCapabilitiesChanged(network, capabilities)
        }
        rule.runOnIdle { assertEquals(wifi, result.dockStatus.wifi) }
        assertEquals(1, registrations.size)
        assertTrue(releases.isEmpty())
        rule.runOnIdle { owner.stop() }
        assertEquals(listOf(oldReceiver), releases)
        assertEquals(before, connectivity.networkCallbacks.toSet())
        val stoppedReads = wifiReads
        rule.runOnIdle {
            wifi = WifiIndicator.Disconnected
            oldCallback.onLost(network)
            oldReceiver.onReceive(context, Intent(WifiManager.RSSI_CHANGED_ACTION))
        }
        assertEquals(stoppedReads, wifiReads)
        rule.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        rule.runOnIdle { assertEquals(wifi, result.dockStatus.wifi) }
        val restartedReads = wifiReads
        val newCallback = (connectivity.networkCallbacks.toSet() - before).single()
        assertNotSame(oldCallback, newCallback)
        rule.runOnIdle {
            oldCallback.onCapabilitiesChanged(network, capabilities)
            oldReceiver.onReceive(context, Intent(WifiManager.RSSI_CHANGED_ACTION))
        }
        assertEquals(restartedReads, wifiReads)
        rule.runOnIdle {
            owner.stop()
            owner.registry.currentState = Lifecycle.State.STARTED
            owner.stop()
            owner.registry.currentState = Lifecycle.State.STARTED
            composed.value = false
        }
        rule.waitForIdle()
        assertEquals(registrations, releases)
        assertEquals(registrations.size, registrations.toSet().size)
        assertEquals(before, connectivity.networkCallbacks.toSet())
        assertEquals(0, owner.registry.observerCount)
        val disposedReads = wifiReads
        rule.runOnIdle {
            newCallback.onLost(network)
            owner.stop()
            owner.resume()
        }
        assertEquals(disposedReads, wifiReads)
    }

    @Test fun sharedEnvironment_visibilityAndRegistrationGenerations() = verifyVisibility(true)
    @Test fun legacyEnvironment_visibilityAndRegistrationGenerations() = verifyVisibility(false)

    private fun verifyPermissions(useShared: Boolean) {
        permissions(false)
        host(useShared)
        rule.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        assertEquals(MissingPermission.entries, result.missingPermissions)
        val initialReads = permissionReads to resolverReads
        assertTrue(permissionReads > 0)
        assertTrue(resolverReads > 0)
        permissions(true)
        val callback = Shadows.shadowOf(app.getSystemService(ConnectivityManager::class.java)).networkCallbacks.single()
        rule.runOnIdle {
            wifi = WifiIndicator.Connected(3)
            registrations.single().onReceive(context, Intent(WifiManager.RSSI_CHANGED_ACTION))
            callback.onLost(ShadowNetwork.newInstance(44))
        }
        rule.runOnIdle {
            assertEquals(wifi, result.dockStatus.wifi)
            assertEquals(MissingPermission.entries, result.missingPermissions)
        }
        assertEquals(initialReads, permissionReads to resolverReads)
        rule.runOnIdle { owner.resume() }
        rule.runOnIdle { assertTrue(result.missingPermissions.isEmpty()) }
        assertTrue(permissionReads > initialReads.first)
        rule.runOnIdle {
            owner.stop()
            permissions(false)
            owner.registry.currentState = Lifecycle.State.STARTED
        }
        rule.runOnIdle { assertEquals(MissingPermission.entries, result.missingPermissions) }
    }

    @Test fun sharedEnvironment_radioEventsDoNotReadPermissions_startAndResumeDo() = verifyPermissions(true)
    @Test fun legacyEnvironment_radioEventsDoNotReadPermissions_startAndResumeDo() = verifyPermissions(false)

    private fun verifyRegistrationFailure(useShared: Boolean) {
        failRadioRegistration = true
        val connectivity = Shadows.shadowOf(app.getSystemService(ConnectivityManager::class.java))
        val before = connectivity.networkCallbacks.toSet()
        host(useShared)
        rule.runOnIdle { owner.resume() }
        assertTrue(registrations.isEmpty())
        assertEquals(1, (connectivity.networkCallbacks.toSet() - before).size)
        rule.runOnIdle { owner.stop() }
        assertTrue(releases.isEmpty())
        assertEquals(before, connectivity.networkCallbacks.toSet())
        rule.runOnIdle { failRadioRegistration = false; owner.resume() }
        assertEquals(1, registrations.size)
        rule.runOnIdle { failRadioRelease = true; owner.stop(); composed.value = false }
        rule.waitForIdle()
        assertEquals(registrations, releases)
        assertEquals(before, connectivity.networkCallbacks.toSet())
        assertEquals(0, owner.registry.observerCount)
    }

    @Test fun sharedEnvironment_failedRegistrationAndReleaseArePaired() = verifyRegistrationFailure(true)
    @Test fun legacyEnvironment_failedRegistrationAndReleaseArePaired() = verifyRegistrationFailure(false)

    @Test fun panelPositionAndMediaCollection_stopThenSyncLatestWhileOnlyStarted() {
        AppGraph.init(app)
        AppGraph.appCatalogStateForTest = AppCatalogState(app, AppGraph.catalog, UnconfinedTestDispatcher())
        val field = MediaWatcher::class.java.getDeclaredField("_nowPlaying").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val flow = field.get(MediaWatcher) as MutableStateFlow<NowPlaying?>
        val previous = flow.value
        val config = Defaults.build(emptySet()).copy(appListPkgs = emptyList())
        try {
            rule.runOnIdle {
                owner.registry.currentState = Lifecycle.State.STARTED
                flow.value = NowPlaying("dummy", "Visible track", "Dummy", null, 120_000L, true, false,
                    positionUpdatedAt = SystemClock.elapsedRealtime())
            }
            rule.setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    LauncherTheme { PanelScreen(false, homeWide = true, homeConfig = config, onLaunchApp = {}) }
                }
            }
            rule.waitForIdle()
            rule.mainClock.autoAdvance = false
            fun position() = rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
                .fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current
            val initial = position()
            rule.runOnIdle { ShadowSystemClock.advanceBy(Duration.ofSeconds(2)) }
            rule.mainClock.advanceTimeBy(2_000)
            assertTrue(position() > initial)
            rule.runOnIdle { owner.stop() }
            rule.mainClock.advanceTimeBy(64)
            val stopped = position()
            rule.runOnIdle {
                ShadowSystemClock.advanceBy(Duration.ofSeconds(5))
                flow.value = flow.value!!.copy(title = "Hidden track")
            }
            rule.mainClock.advanceTimeBy(2_000)
            assertEquals(stopped, position(), 0f)
            rule.onNodeWithText("Hidden track").assertDoesNotExist()
            rule.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
            rule.mainClock.advanceTimeBy(64)
            assertTrue(position() > stopped)
            rule.onNodeWithText("Hidden track").assertExists()
        } finally {
            flow.value = previous
            rule.mainClock.autoAdvance = true
        }
    }
}
