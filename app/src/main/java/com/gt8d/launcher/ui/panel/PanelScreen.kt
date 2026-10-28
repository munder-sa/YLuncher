package com.gt8d.launcher.ui.panel

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.gt8d.launcher.AppGraph
import com.gt8d.launcher.config.Defaults
import com.gt8d.launcher.media.MediaWatcher
import com.gt8d.launcher.media.positionAt
import kotlinx.coroutines.delay

@Composable
fun PanelScreen(
    inSplit: Boolean,
    onLaunchApp: (String) -> Unit,
) {
    val context = LocalContext.current
    val latestConfig by AppGraph.latestConfig.collectAsState()
    val config = latestConfig ?: remember { Defaults.build(AppGraph.catalog.installed()) }
    val now by MediaWatcher.nowPlaying.collectAsState()

    var positionMs by remember(now) {
        mutableLongStateOf(now?.positionAt(SystemClock.elapsedRealtime()) ?: 0L)
    }

    LaunchedEffect(now) {
        val current = now ?: run {
            positionMs = 0L
            return@LaunchedEffect
        }
        positionMs = current.positionAt(SystemClock.elapsedRealtime())
        while (current.playing) {
            delay(500L)
            positionMs = current.positionAt(SystemClock.elapsedRealtime())
        }
    }

    val catalog = AppGraph.catalog
    val idleApp = catalog.info(config.idleApp)
    val panelApps = config.panelApps.map { catalog.info(it) }
    val dockApps = config.dockApps.map { catalog.info(it) }

    val wifiOn = runCatching {
        context.getSystemService(WifiManager::class.java)?.isWifiEnabled == true
    }.getOrDefault(false)

    val btGranted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.BLUETOOTH_CONNECT,
    ) == PackageManager.PERMISSION_GRANTED
    val bluetoothOn = if (btGranted) {
        runCatching {
            context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled
        }.getOrNull()
    } else {
        null
    }
    val dockStatus = DockStatus(wifiOn = wifiOn, bluetoothOn = bluetoothOn)

    val notifEnabled = runCatching {
        context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
    }.getOrDefault(false)
    val overlayEnabled = runCatching {
        Settings.canDrawOverlays(context)
    }.getOrDefault(false)
    val usageEnabled = AppGraph.watcher.available()
    val needsPermission = !notifEnabled || !overlayEnabled || !usageEnabled

    val state = PanelState(
        now = now,
        idleApp = idleApp,
        positionMs = positionMs,
        panelApps = panelApps,
        dockApps = dockApps,
        dockStatus = dockStatus,
        showCar = AppGraph.bridge.carHomeAvailable,
        needsPermission = needsPermission,
    )

    val actions = PanelActions(
        onMediaAction = { action ->
            when (action) {
                MediaAction.Play -> MediaWatcher.play()
                MediaAction.Pause -> MediaWatcher.pause()
                MediaAction.Next -> MediaWatcher.next()
                MediaAction.Prev -> MediaWatcher.previous()
                MediaAction.Favorite -> MediaWatcher.favorite()
            }
        },
        onSeek = { ms -> MediaWatcher.seekTo(ms) },
        onLaunch = onLaunchApp,
        onAdd = { /* Task 15b で接続 */ },
        onHome = { AppGraph.runner.home() },
        onSplit = { /* Task 15b で接続 */ },
        onCar = { AppGraph.bridge.switchToCarScreen() },
        onAppList = { /* Task 15b で接続 */ },
        onAppListLongPress = { /* Task 15b で接続 */ },
        onOpenPermissions = {
            runCatching {
                val intent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null),
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        },
    )

    PanelContent(
        state = state,
        actions = actions,
    )
}
