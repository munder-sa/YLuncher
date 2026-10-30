package com.gt8d.launcher.ui.panel

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.gt8d.launcher.AppGraph
import com.gt8d.launcher.apps.stockLauncherIntent
import com.gt8d.launcher.config.Defaults
import com.gt8d.launcher.media.MediaWatcher
import com.gt8d.launcher.media.positionAt
import com.gt8d.launcher.split.AndroidActivityLauncher
import com.gt8d.launcher.ui.apps.AppListScreen
import com.gt8d.launcher.ui.apps.STOCK_LAUNCHER_ENTRY
import kotlinx.coroutines.delay

@Composable
fun PanelScreen(
    inSplit: Boolean,
    onLaunchComponent: ((ComponentName) -> Unit)? = null,
    onOpenSettings: () -> Unit = {},
    onNewSet: () -> Unit = {},
    onLaunchApp: (String) -> Unit,
) {
    val context = LocalContext.current
    val latestConfig by AppGraph.latestConfig.collectAsState()
    val config = latestConfig ?: remember { Defaults.build(AppGraph.catalog.installed()) }
    val now by MediaWatcher.nowPlaying.collectAsState()
    var showAppList by remember { mutableStateOf(false) }
    val environment = rememberPanelEnvironment(AppGraph.catalog)

    val appListGridState = rememberLazyGridState()

    if (showAppList) {
        AppListScreen(
            gridState = appListGridState,
            iconRevision = environment.revision.toInt(),
            entries = environment.appListEntries,
            iconFor = { pkg -> AppGraph.catalog.icon(pkg) },
            onLaunch = { entry ->
                if (entry == STOCK_LAUNCHER_ENTRY) {
                    runCatching {
                        context.startActivity(stockLauncherIntent())
                    }
                } else {
                    val component = ComponentName(entry.pkg, entry.cls)
                    if (onLaunchComponent != null) {
                        onLaunchComponent(component)
                    } else {
                        AndroidActivityLauncher(context).startComponent(component, adjacent = inSplit)
                    }
                }
                showAppList = false
            },
            onClose = { showAppList = false },
        )
        return
    }

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
    val installedPkgs = environment.installedPkgs
    val idleApp = catalog.info(config.idleApp, installedPkgs)
    val panelApps = config.panelApps.map { catalog.info(it, installedPkgs) }
    val dockApps = config.dockApps.map { catalog.info(it, installedPkgs) }

    val state = PanelState(
        now = now,
        idleApp = idleApp,
        positionMs = positionMs,
        panelApps = panelApps,
        dockApps = dockApps,
        dockStatus = environment.dockStatus,
        showCar = AppGraph.bridge.carHomeAvailable,
        needsPermission = environment.needsPermission,
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
        onAdd = { /* Task 18 で接続 */ },
        onHome = { AppGraph.runner.home() },
        onSplit = { if (AppGraph.runner.opening.value == null) onNewSet() },
        onCar = { AppGraph.bridge.switchToCarScreen() },
        onAppList = { showAppList = true },
        onAppListLongPress = onOpenSettings,
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
