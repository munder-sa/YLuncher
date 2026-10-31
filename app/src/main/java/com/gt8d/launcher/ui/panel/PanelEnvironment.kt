package com.gt8d.launcher.ui.panel

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.WifiInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gt8d.launcher.apps.AppCatalog
import com.gt8d.launcher.foreground.UsageStatsForegroundEvents
import com.gt8d.launcher.ui.apps.AppListEntry
import com.gt8d.launcher.ui.apps.buildAppList

data class PanelEnvironment(
    val dockStatus: DockStatus,
    val needsPermission: Boolean,
    val installedPkgs: Set<String>,
    val appListEntries: List<AppListEntry>,
    val revision: Long,
)

internal object WifiReader {
    var read: (Context) -> WifiIndicator = ::readWifiFromSystem
}

private fun readWifiFromSystem(context: Context): WifiIndicator {
    val wifiManager = runCatching { context.getSystemService(WifiManager::class.java) }.getOrNull()
    val enabled = runCatching {
        wifiManager?.isWifiEnabled == true
    }.getOrDefault(false)
    val info = runCatching {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivity?.getNetworkCapabilities(connectivity.activeNetwork)
        if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true)
            capabilities.transportInfo as? WifiInfo else null
    }.getOrNull()
    val signal = runCatching {
        if (info != null && wifiManager != null)
            wifiManager.calculateSignalLevel(info.rssi) to wifiManager.maxSignalLevel else null
    }.getOrNull()
    return wifiIndicatorOf(enabled, signal != null, signal?.first ?: 0, signal?.second ?: 0)
}

private fun readDockStatus(context: Context): DockStatus {
    val wifi = WifiReader.read(context)
    val bluetooth = if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
        runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled }.getOrNull()
    } else null
    return DockStatus(wifiOn = wifi !is WifiIndicator.Off, bluetoothOn = bluetooth, wifi = wifi)
}

private fun readNeedsPermission(context: Context): Boolean {
    val notification = runCatching {
        context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
    }.getOrDefault(false)
    val overlay = runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)
    return !notification || !overlay || !UsageStatsForegroundEvents(context).available()
}

private fun readEnvironment(context: Context, catalog: AppCatalog, revision: Long) = PanelEnvironment(
    readDockStatus(context), readNeedsPermission(context), catalog.installed(),
    buildAppList(catalog.launchables(), context.packageName), revision,
)

@Composable
fun rememberPanelEnvironment(catalog: AppCatalog): PanelEnvironment {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var environment by remember(context, catalog) { mutableStateOf(readEnvironment(context, catalog, 0L)) }
    DisposableEffect(context, owner, catalog) {
        var observing = false
        fun refreshRadios() {
            environment = environment.copy(
                dockStatus = readDockStatus(context), needsPermission = readNeedsPermission(context),
                revision = environment.revision + 1,
            )
        }
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = refreshRadios()
            override fun onLost(network: Network) = refreshRadios()
        }
        val callbackRegistered = runCatching {
            connectivity?.registerDefaultNetworkCallback(callback, Handler(Looper.getMainLooper()))
            connectivity != null
        }.getOrDefault(false)
        val observer = LifecycleEventObserver { _, event ->
            if (observing && event == Lifecycle.Event.ON_RESUME) {
                catalog.invalidateAllIcons()
                environment = readEnvironment(context, catalog, environment.revision + 1)
            }
        }
        val radios = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action !in setOf(WifiManager.WIFI_STATE_CHANGED_ACTION, WifiManager.RSSI_CHANGED_ACTION,
                        WifiManager.NETWORK_STATE_CHANGED_ACTION, BluetoothAdapter.ACTION_STATE_CHANGED)) return
                refreshRadios()
            }
        }
        val packages = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action !in setOf(Intent.ACTION_PACKAGE_ADDED, Intent.ACTION_PACKAGE_REMOVED, Intent.ACTION_PACKAGE_CHANGED)) return
                val pkg = intent.data?.takeIf { it.scheme == "package" }?.schemeSpecificPart ?: return
                catalog.invalidatePackage(pkg)
                environment = readEnvironment(context, catalog, environment.revision + 1)
            }
        }
        ContextCompat.registerReceiver(context, radios, IntentFilter().apply {
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(WifiManager.RSSI_CHANGED_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }, ContextCompat.RECEIVER_EXPORTED)
        ContextCompat.registerReceiver(context, packages, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }, ContextCompat.RECEIVER_EXPORTED)
        owner.lifecycle.addObserver(observer)
        observing = true
        environment = readEnvironment(context, catalog, environment.revision)
        onDispose {
            if (callbackRegistered) connectivity?.unregisterNetworkCallback(callback)
            owner.lifecycle.removeObserver(observer)
            context.unregisterReceiver(radios)
            context.unregisterReceiver(packages)
        }
    }
    return environment
}
