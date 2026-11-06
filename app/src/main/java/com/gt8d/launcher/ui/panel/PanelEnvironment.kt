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
import androidx.compose.runtime.collectAsState
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
import com.gt8d.launcher.apps.AppCatalogState
import com.gt8d.launcher.foreground.UsageStatsForegroundEvents
import com.gt8d.launcher.ui.apps.AppListEntry
import com.gt8d.launcher.ui.apps.buildAppList

enum class MissingPermission { Notification, Overlay, UsageStats }

data class PanelEnvironment(
    val dockStatus: DockStatus,
    val missingPermissions: List<MissingPermission>,
    val installedPkgs: Set<String>,
    val appListEntries: List<AppListEntry>,
    val revision: Long,
    val appRevision: Long = revision,
    val radioRevision: Long = 0,
) {
    val needsPermission: Boolean get() = missingPermissions.isNotEmpty()
}

@Composable
fun rememberPanelEnvironment(catalog: AppCatalogState): PanelEnvironment {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val apps by catalog.snapshot.collectAsState()
    var radios by remember(context) {
        mutableStateOf(PanelEnvironment(readDockStatus(context), readMissingPermissions(context),
            emptySet(), emptyList(), 0))
    }
    DisposableEffect(context, owner, catalog) {
        fun refreshRadios() {
            val status = readDockStatus(context)
            radios = radios.copy(dockStatus = status,
                radioRevision = radios.radioRevision + if (status != radios.dockStatus) 1 else 0)
        }
        fun refreshVisible() {
            refreshRadios()
            radios = radios.copy(missingPermissions = readMissingPermissions(context))
        }
        catalog.resume()
        val dispose = observeVisibleRadios(context, owner.lifecycle,
            onStart = ::refreshVisible,
            onResume = { refreshVisible(); catalog.resume() },
            onRadio = ::refreshRadios)
        onDispose { dispose() }
    }
    val entries = remember(apps.entries, context.packageName) { buildAppList(apps.entries, context.packageName) }
    return radios.copy(installedPkgs = apps.installed,
        appListEntries = entries,
        revision = apps.appRevision, appRevision = apps.appRevision)
}

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

private fun readMissingPermissions(context: Context): List<MissingPermission> {
    val notification = runCatching {
        context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
    }.getOrDefault(false)
    val overlay = runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)
    return buildList {
        if (!notification) add(MissingPermission.Notification)
        if (!overlay) add(MissingPermission.Overlay)
        if (!UsageStatsForegroundEvents(context).available()) add(MissingPermission.UsageStats)
    }
}

private fun readEnvironment(context: Context, catalog: AppCatalog, revision: Long) = PanelEnvironment(
    readDockStatus(context), readMissingPermissions(context), catalog.installed(),
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
                dockStatus = readDockStatus(context),
                revision = environment.revision + 1,
            )
        }
        val packages = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (!observing) return
                if (intent.action !in setOf(Intent.ACTION_PACKAGE_ADDED, Intent.ACTION_PACKAGE_REMOVED, Intent.ACTION_PACKAGE_CHANGED)) return
                val pkg = intent.data?.takeIf { it.scheme == "package" }?.schemeSpecificPart ?: return
                catalog.invalidatePackage(pkg)
                environment = readEnvironment(context, catalog, environment.revision + 1)
            }
        }
        ContextCompat.registerReceiver(context, packages, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }, ContextCompat.RECEIVER_EXPORTED)
        val disposeRadios = observeVisibleRadios(context, owner.lifecycle,
            onStart = {
                refreshRadios()
                environment = environment.copy(missingPermissions = readMissingPermissions(context))
            },
            onResume = {
                if (observing) {
                    catalog.invalidateAllIcons()
                    environment = readEnvironment(context, catalog, environment.revision + 1)
                }
            },
            onRadio = ::refreshRadios)
        observing = true
        environment = readEnvironment(context, catalog, environment.revision)
        onDispose {
            observing = false
            disposeRadios()
            context.unregisterReceiver(packages)
        }
    }
    return environment
}

/** One observer per environment; every visible interval owns fresh radio callbacks. */
private fun observeVisibleRadios(
    context: Context,
    lifecycle: Lifecycle,
    onStart: () -> Unit,
    onResume: () -> Unit,
    onRadio: () -> Unit,
): () -> Unit {
    var disposed = false
    var active = false
    var generation = 0L
    var release: () -> Unit = {}

    fun stop() {
        if (!active) return
        active = false
        generation++ // Invalidate queued events before unregistering either source.
        val unregister = release
        release = {}
        unregister()
    }

    fun start() {
        if (disposed || active) return
        active = true
        val token = ++generation
        fun refresh() {
            if (!disposed && active && token == generation &&
                lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) onRadio()
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action in radioActions) refresh()
            }
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = refresh()
            override fun onLost(network: Network) = refresh()
        }
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val callbackRegistered = runCatching {
            connectivity?.registerDefaultNetworkCallback(callback, Handler(Looper.getMainLooper()))
            connectivity != null
        }.getOrDefault(false)
        val receiverRegistered = runCatching {
            ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
                radioActions.forEach { addAction(it) }
            }, ContextCompat.RECEIVER_EXPORTED)
            true
        }.getOrDefault(false)
        release = {
            if (callbackRegistered) runCatching { connectivity?.unregisterNetworkCallback(callback) }
            if (receiverRegistered) runCatching { context.unregisterReceiver(receiver) }
        }
        onStart()
    }

    val observer = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> start()
            Lifecycle.Event.ON_RESUME -> if (!disposed && active) onResume()
            Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> stop()
            else -> Unit
        }
    }
    lifecycle.addObserver(observer)
    return {
        disposed = true
        lifecycle.removeObserver(observer)
        stop()
    }
}

private val radioActions = setOf(WifiManager.WIFI_STATE_CHANGED_ACTION, WifiManager.RSSI_CHANGED_ACTION,
    WifiManager.NETWORK_STATE_CHANGED_ACTION, BluetoothAdapter.ACTION_STATE_CHANGED)
