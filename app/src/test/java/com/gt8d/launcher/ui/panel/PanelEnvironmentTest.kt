package com.gt8d.launcher.ui.panel

import android.Manifest
import android.app.Application
import android.app.AppOpsManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Process
import android.os.Handler
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.State
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import com.gt8d.launcher.apps.AppCatalog
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PanelEnvironmentTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val pkg = "example.environment"
    private val catalog by lazy { AppCatalog(app) }
    private val catalogRegistered = mutableListOf<BroadcastReceiver>()
    private val catalogUnregistered = mutableListOf<BroadcastReceiver>()
    private val shared by lazy { com.gt8d.launcher.apps.AppCatalogState(object : ContextWrapper(app) {
        override fun getApplicationContext(): Context = this
        override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter, permission: String?, scheduler: Handler?, flags: Int): Intent? {
            if (receiver != null) catalogRegistered.add(receiver)
            return super.registerReceiver(receiver, filter, permission, scheduler, flags)
        }
        override fun unregisterReceiver(receiver: BroadcastReceiver) {
            catalogUnregistered.add(receiver)
            super.unregisterReceiver(receiver)
        }
    }, catalog,
        kotlinx.coroutines.test.UnconfinedTestDispatcher()) }
    @org.junit.After fun closeCatalog() { shared.close() }
    private lateinit var owner: PanelTestLifecycle
    private lateinit var result: PanelEnvironment
    private val visible = mutableStateOf(true)
    private var reads = 0
    private val registered = mutableListOf<BroadcastReceiver>()
    private val unregistered = mutableListOf<BroadcastReceiver>()
    @get:Rule(order = 0) val register = object : ExternalResource() {
        override fun before() {
            val cn = ComponentName(app.packageName, ComponentActivity::class.java.name)
            Shadows.shadowOf(app.packageManager).addOrUpdateActivity(ActivityInfo().apply {
                name = cn.className; packageName = cn.packageName
            })
            Shadows.shadowOf(app.packageManager).addIntentFilterForActivity(cn,
                IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
        }
    }
    @get:Rule(order = 1) val rule = createComposeRule()
    private fun host() {
        val context = object : ContextWrapper(app) {
            override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter, permission: String?, scheduler: Handler?, flags: Int): Intent? {
                if (receiver != null) registered.add(receiver)
                return super.registerReceiver(receiver, filter, permission, scheduler, flags)
            }
            override fun unregisterReceiver(receiver: BroadcastReceiver) {
                unregistered.add(receiver)
                super.unregisterReceiver(receiver)
            }
            override fun getSystemService(name: String): Any? {
                reads++
                return super.getSystemService(name)
            }
        }
        rule.setContent {
            if (visible.value) {
                owner = androidx.compose.runtime.remember { PanelTestLifecycle().also { it.resume() } }
                CompositionLocalProvider(LocalContext provides context, LocalLifecycleOwner provides owner) {
                    result = rememberPanelEnvironment(shared)
                }
            }
        }
        rule.waitForIdle()
    }
    private fun radios(wifi: Boolean, bluetooth: Boolean) {
        app.getSystemService(WifiManager::class.java).setWifiEnabled(wifi)
        Shadows.shadowOf(app.getSystemService(BluetoothManager::class.java).adapter).setEnabled(bluetooth)
    }
    private fun permissions(enabled: Boolean) {
        ShadowSettings.setCanDrawOverlays(enabled)
        Settings.Secure.putString(app.contentResolver, "enabled_notification_listeners",
            if (enabled) "${app.packageName}/.media.MediaWatcher" else "")
        Shadows.shadowOf(app.getSystemService(AppOpsManager::class.java)).setMode(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), app.packageName,
            if (enabled) AppOpsManager.MODE_ALLOWED else AppOpsManager.MODE_IGNORED)
    }
    private fun install(label: String) {
        val cn = ComponentName(pkg, "$pkg.Main")
        val pm = Shadows.shadowOf(app.packageManager)
        pm.addOrUpdateActivity(ActivityInfo().apply {
            name = cn.className; packageName = pkg; nonLocalizedLabel = label
        })
        pm.addIntentFilterForActivity(cn, IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
    }
    private fun broadcast(action: String, packages: Boolean = false) {
        rule.runOnIdle {
            app.sendBroadcast(if (packages) Intent(action, Uri.parse("package:$pkg")) else Intent(action))
        }
        rule.waitForIdle()
    }
    @Test fun environment_resume_refreshesPermissionsAndRadios() {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        permissions(false); radios(false, false); host()
        rule.runOnIdle { assertEquals(DockStatus(false, false), result.dockStatus); assertTrue(result.needsPermission) }
        rule.runOnIdle { owner.stop(); permissions(true); radios(true, true); owner.resume() }
        rule.runOnIdle { assertEquals(DockStatus(true, true, WifiIndicator.Disconnected), result.dockStatus); assertFalse(result.needsPermission) }
    }
    @Test fun environment_missingPermissions_allCombinationsInOrder() {
        permissions(false)
        host()
        for (mask in 0..7) {
            val notification = mask and 1 != 0
            val overlay = mask and 2 != 0
            val usage = mask and 4 != 0
            rule.runOnIdle {
                owner.stop()
                Settings.Secure.putString(app.contentResolver, "enabled_notification_listeners",
                    if (notification) "${app.packageName}/.media.MediaWatcher" else "")
                ShadowSettings.setCanDrawOverlays(overlay)
                Shadows.shadowOf(app.getSystemService(AppOpsManager::class.java)).setMode(
                    AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), app.packageName,
                    if (usage) AppOpsManager.MODE_ALLOWED else AppOpsManager.MODE_IGNORED)
                owner.resume()
            }
            rule.runOnIdle {
                val expected = buildList {
                    if (!notification) add(MissingPermission.Notification)
                    if (!overlay) add(MissingPermission.Overlay)
                    if (!usage) add(MissingPermission.UsageStats)
                }
                assertEquals("mask=$mask", expected, result.missingPermissions)
                assertEquals(expected.isNotEmpty(), result.needsPermission)
            }
        }
    }
    @Test fun environment_radioBroadcast_refreshesWithoutMediaChange() {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        radios(false, false); host()
        val before = result.radioRevision
        val appsBefore = result.appRevision
        rule.runOnIdle { radios(true, false) }
        broadcast(WifiManager.WIFI_STATE_CHANGED_ACTION)
        rule.runOnIdle {
            assertEquals(DockStatus(true, false, WifiIndicator.Disconnected), result.dockStatus)
            assertEquals(before + 1, result.radioRevision)
            assertEquals(appsBefore, result.appRevision)
        }
        rule.runOnIdle { radios(true, true) }
        broadcast(BluetoothAdapter.ACTION_STATE_CHANGED)
        rule.runOnIdle { assertEquals(true, result.dockStatus.bluetoothOn) }
        rule.runOnIdle { Shadows.shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT) }
        broadcast(BluetoothAdapter.ACTION_STATE_CHANGED)
        rule.runOnIdle { assertNull(result.dockStatus.bluetoothOn) }
    }
    @Test fun environment_dispose_unregistersObserversAndReceivers() {
        val states = mutableSetOf<State<*>>()
        val observer = Snapshot.registerApplyObserver { changed, _ ->
            changed.forEach { value ->
                if (value is State<*> && value.value is PanelEnvironment) states.add(value)
            }
        }
        try {
            radios(false, false)
            host()
            assertEquals(1, registered.size)
            assertTrue(unregistered.isEmpty())
            assertEquals(2, catalogRegistered.size)
            assertTrue(catalogUnregistered.isEmpty())
            rule.runOnIdle { radios(true, false) }
            broadcast(WifiManager.WIFI_STATE_CHANGED_ACTION)
            val oldState = states.single()
            val oldEnvironment = oldState.value
            val oldOwner = owner
            assertEquals(1, oldOwner.registry.observerCount)
            rule.runOnIdle { visible.value = false }
            rule.waitForIdle()
            assertEquals(registered, unregistered)
            assertTrue(catalogUnregistered.isEmpty())
            assertEquals(0, oldOwner.registry.observerCount)
            val before = reads
            broadcast(WifiManager.WIFI_STATE_CHANGED_ACTION)
            broadcast(BluetoothAdapter.ACTION_STATE_CHANGED)
            broadcast(Intent.ACTION_PACKAGE_ADDED, true)
            rule.runOnIdle { oldOwner.stop(); oldOwner.resume(); assertEquals(before, reads) }
            assertEquals(oldEnvironment, oldState.value)
            rule.runOnIdle { visible.value = true }
            rule.waitForIdle()
            assertEquals(2, registered.size)
            assertEquals(1, unregistered.size)
            assertEquals(1, owner.registry.observerCount)
            val revision = result.radioRevision
            rule.runOnIdle { radios(false, false) }
            broadcast(WifiManager.WIFI_STATE_CHANGED_ACTION)
            rule.runOnIdle { assertEquals(revision + 1, result.radioRevision) }
            assertEquals(oldEnvironment, oldState.value)
            rule.runOnIdle { visible.value = false }
            rule.waitForIdle()
            assertEquals(registered, unregistered)
            assertEquals(0, owner.registry.observerCount)
            val oldRevision = shared.snapshot.value.appRevision
            rule.runOnIdle { install("Hidden") }
            broadcast(Intent.ACTION_PACKAGE_ADDED, true)
            assertTrue(shared.snapshot.value.appRevision > oldRevision)
            shared.close()
            assertEquals(catalogRegistered.toSet(), catalogUnregistered.toSet())
            assertEquals(1, catalogUnregistered.size)
            val closed = shared.snapshot.value
            broadcast(Intent.ACTION_PACKAGE_CHANGED, true)
            assertEquals(closed, shared.snapshot.value)
        } finally {
            observer.dispose()
        }
    }
    @Test fun environment_wifiRssiBroadcast_refreshesBars() {
        val original = WifiReader.read
        var wifi: WifiIndicator = WifiIndicator.Connected(4)
        try {
            WifiReader.read = { wifi }
            host()
            rule.runOnIdle { assertEquals(WifiIndicator.Connected(4), result.dockStatus.wifi) }
            rule.runOnIdle { wifi = WifiIndicator.Connected(1) }
            broadcast(WifiManager.RSSI_CHANGED_ACTION)
            rule.runOnIdle { assertEquals(WifiIndicator.Connected(1), result.dockStatus.wifi) }
            rule.runOnIdle { wifi = WifiIndicator.Disconnected }
            broadcast(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            rule.runOnIdle { assertEquals(WifiIndicator.Disconnected, result.dockStatus.wifi) }
        } finally {
            WifiReader.read = original
        }
    }
    @Test fun environment_networkCallback_refreshesAndUnregisters() {
        val original = WifiReader.read
        var wifi: WifiIndicator = WifiIndicator.Disconnected
        try {
            WifiReader.read = { wifi }
            val connectivity = Shadows.shadowOf(app.getSystemService(ConnectivityManager::class.java))
            val before = connectivity.networkCallbacks.toSet()
            host()
            val callback = (connectivity.networkCallbacks.toSet() - before).single()
            val network = ShadowNetwork.newInstance(43)
            val capabilities = ShadowNetworkCapabilities.newInstance()
            Shadows.shadowOf(capabilities).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            rule.runOnIdle {
                wifi = WifiIndicator.Connected(4)
                callback.onCapabilitiesChanged(network, capabilities)
            }
            rule.runOnIdle { assertEquals(WifiIndicator.Connected(4), result.dockStatus.wifi) }
            rule.runOnIdle {
                wifi = WifiIndicator.Disconnected
                callback.onLost(network)
            }
            rule.runOnIdle { assertEquals(WifiIndicator.Disconnected, result.dockStatus.wifi) }
            rule.runOnIdle { visible.value = false }
            rule.waitForIdle()
            assertFalse(callback in connectivity.networkCallbacks)
        } finally {
            WifiReader.read = original
        }
    }
    @Test fun environment_packageBroadcast_refreshesInstalledAndEntries() {
        host()
        rule.runOnIdle { install("First") }
        broadcast(Intent.ACTION_PACKAGE_ADDED, true)
        rule.runOnIdle { assertTrue(pkg in result.installedPkgs); assertEquals("First", result.appListEntries.single { it.pkg == pkg }.label) }
        rule.runOnIdle {
            Shadows.shadowOf(app.packageManager).removeActivity(ComponentName(pkg, "$pkg.Main"))
            Shadows.shadowOf(app.packageManager).removePackage(pkg)
        }
        broadcast(Intent.ACTION_PACKAGE_REMOVED, true)
        rule.runOnIdle { assertFalse(pkg in result.installedPkgs); assertFalse(result.appListEntries.any { it.pkg == pkg }) }
        rule.runOnIdle { install("Changed") }
        broadcast(Intent.ACTION_PACKAGE_CHANGED, true)
        rule.runOnIdle { assertTrue(pkg in result.installedPkgs); assertEquals("Changed", result.appListEntries.single { it.pkg == pkg }.label) }
    }
    @Test fun environment_resume_refreshesPackagesMissedWhileHidden() {
        install("Before")
        Shadows.shadowOf(app.packageManager).setApplicationIcon(pkg, ColorDrawable(Color.RED))
        host()
        shared.request(setOf(pkg))
        assertEquals(Color.RED, shared.snapshot.value.info(pkg).icon!!.asAndroidBitmap().getPixel(48, 48))
        rule.runOnIdle {
            owner.stop()
            Shadows.shadowOf(app.packageManager).removeActivity(ComponentName(pkg, "$pkg.Main"))
            Shadows.shadowOf(app.packageManager).removePackage(pkg)
            install("After")
            installOther()
            Shadows.shadowOf(app.packageManager).setApplicationIcon(pkg, ColorDrawable(Color.GREEN))
            app.sendBroadcast(Intent(Intent.ACTION_PACKAGE_CHANGED, Uri.parse("package:$pkg")))
            app.sendBroadcast(Intent(Intent.ACTION_PACKAGE_ADDED, Uri.parse("package:example.other")))
        }
        rule.waitForIdle()
        rule.runOnIdle { owner.resume() }
        rule.runOnIdle {
            assertTrue("example.other" in result.installedPkgs)
            assertEquals("After", result.appListEntries.single { it.pkg == pkg }.label)
            assertEquals(Color.GREEN, shared.snapshot.value.info(pkg).icon!!.asAndroidBitmap().getPixel(48, 48))
        }
    }
    private fun installOther() {
        val cn = ComponentName("example.other", "example.other.Main")
        val pm = Shadows.shadowOf(app.packageManager)
        pm.addOrUpdateActivity(ActivityInfo().apply { name = cn.className; packageName = cn.packageName })
        pm.addIntentFilterForActivity(cn, IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
    }
}
