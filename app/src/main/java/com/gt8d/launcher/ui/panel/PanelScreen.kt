package com.gt8d.launcher.ui.panel

import android.content.ComponentName
import android.os.SystemClock
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gt8d.launcher.config.LauncherConfig
import com.gt8d.launcher.config.PanelView
import com.gt8d.launcher.ui.home.HomeClockHeader
import com.gt8d.launcher.ui.home.HomeMiniPlayer
import com.gt8d.launcher.ui.home.HomeSetCards
import androidx.compose.material3.Text
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import com.gt8d.launcher.ui.theme.Dims
import com.gt8d.launcher.ui.theme.Palette
import com.gt8d.launcher.ui.layout.PanelLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.gt8d.launcher.AppGraph
import com.gt8d.launcher.apps.stockLauncherIntent
import com.gt8d.launcher.apps.dockAppsFor
import com.gt8d.launcher.apps.settingsComponent
import com.gt8d.launcher.config.Defaults
import com.gt8d.launcher.media.MediaWatcher
import com.gt8d.launcher.media.positionAt
import com.gt8d.launcher.split.AndroidActivityLauncher
import com.gt8d.launcher.split.PanelPlacement
import com.gt8d.launcher.ui.apps.AppListScreen
import com.gt8d.launcher.ui.apps.AppListEntry
import com.gt8d.launcher.ui.apps.registeredEntries
import com.gt8d.launcher.ui.apps.STOCK_LAUNCHER_ENTRY
import com.gt8d.launcher.ui.apps.seedAppList
import com.gt8d.launcher.ui.apps.toggleRegistered
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.compose.runtime.rememberCoroutineScope
import com.gt8d.launcher.diag.DiagLog

@Composable
fun PanelScreen(
    inSplit: Boolean,
    onLaunchComponent: ((ComponentName) -> Unit)? = null,
    onOpenSettings: () -> Unit = {},
    onNewSet: () -> Unit = {},
    onEditLists: () -> Unit = {},
    homeWide: Boolean = false,
    homeConfig: LauncherConfig? = null,
    placement: PanelPlacement? = null,
    onOpenPermissionSettings: () -> Unit = {},
    onLaunchApp: (String) -> Unit,
) {
    val context = LocalContext.current
    val latestConfig by AppGraph.latestConfig.collectAsState()
    val config = homeConfig ?: latestConfig ?: remember { Defaults.build(AppGraph.catalog.installed()) }
    var pending by rememberSaveable { mutableStateOf<PanelView?>(null) }
    val scope = rememberCoroutineScope()
    val viewSaveMutex = remember { Mutex() }
    LaunchedEffect(config.panelView, pending) {
        if (pending == config.panelView) pending = null
    }
    val now by MediaWatcher.nowPlaying.collectAsState()
    var showAppList by rememberSaveable { mutableStateOf(false) }
    val environment = rememberPanelEnvironment(AppGraph.catalog)
    val frequent by AppGraph.frequentApps.collectAsState()
    val settings = remember(context, environment.revision) { settingsComponent(context.packageManager) }
    val settingsApp = remember(settings, environment.revision) {
        settings?.let { AppInfo(it.packageName, "設定", AppGraph.catalog.icon(it.packageName), true) }
    }

    val appListGridState = rememberLazyGridState()
    val homeAppRowState = rememberLazyListState()
    val launchEntry: (AppListEntry) -> Unit = { entry ->
        if (entry == STOCK_LAUNCHER_ENTRY) {
            runCatching { context.startActivity(stockLauncherIntent()) }
        } else {
            val component = ComponentName(entry.pkg, entry.cls)
            if (onLaunchComponent != null) onLaunchComponent(component)
            else AndroidActivityLauncher(context).startComponent(component, adjacent = inSplit)
        }
    }

    val launchable = environment.appListEntries.map { it.pkg }.toSet()
    val seed = seedAppList(config, emptySet(), launchable)
    val registered: Set<String> = (config.appListPkgs ?: seed).toSet()
    LaunchedEffect(homeConfig != null || latestConfig != null, config.appListPkgs) {
        if ((homeConfig != null || latestConfig != null) && config.appListPkgs == null) {
            try {
                val recent = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.gt8d.launcher.apps.UsageHistory(context)
                        .launchesSince(System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000)
                        ?.map { it.pkg }?.toSet() ?: emptySet()
                }
                AppGraph.config.update { cur ->
                    if (cur.appListPkgs != null) cur
                    else cur.copy(appListPkgs = seedAppList(cur, recent, launchable))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DiagLog.d("Panel", "app list seed save failed: $e")
            }
        }
    }

    if (showAppList) {
        AppListScreen(
            registered = registered,
            onToggle = { pkg ->
                scope.launch {
                    try {
                        AppGraph.config.update { it.copy(appListPkgs = toggleRegistered(it.appListPkgs ?: seed, pkg)) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        DiagLog.d("Panel", "app list toggle save failed: $e")
                    }
                }
            },
            gridState = appListGridState,
            iconRevision = environment.revision.toInt(),
            entries = environment.appListEntries,
            iconFor = { pkg -> AppGraph.catalog.icon(pkg) },
            onLaunch = { entry ->
                launchEntry(entry)
                showAppList = false
            },
            onClose = { showAppList = false },
            onOpenSettings = { showAppList = false; onOpenSettings() },
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
    val dockApps = dockAppsFor(config.dockMode, config.dockApps, frequent, installedPkgs,
        setOfNotNull(settings?.packageName)).map { catalog.info(it, installedPkgs) }

    val state = PanelState(
        placement = placement,
        now = now,
        idleApp = idleApp,
        positionMs = positionMs,
        panelApps = panelApps,
        dockApps = dockApps,
        dockStatus = environment.dockStatus,
        showCar = AppGraph.bridge.carHomeAvailable,
        needsPermission = environment.needsPermission,
        missingPermissions = environment.missingPermissions,
        settingsApp = settingsApp,
        homeEntries = registeredEntries(environment.appListEntries, registered),
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
        onLaunchEntry = launchEntry,
        onAdd = onEditLists,
        onHome = { AppGraph.runner.home() },
        onSplit = { if (AppGraph.runner.opening.value == null) onNewSet() },
        onCar = { AppGraph.bridge.switchToCarScreen() },
        onAppList = { showAppList = true },
        onAppListLongPress = onOpenSettings,
        onOpenPermissions = onOpenPermissionSettings,
        onSettings = {
            settings?.let { component ->
                if (onLaunchComponent != null) onLaunchComponent(component)
                else AndroidActivityLauncher(context).startComponent(component, adjacent = inSplit)
            }
        },
    )

    if (homeWide) {
        val currentIndex by AppGraph.runner.currentIndexFlow.collectAsState()
        HomeWideContent(state, actions, config, { catalog.info(it, installedPkgs) }, currentIndex,
            onOpen = { AppGraph.runner.open(it) }, onNewSet = onNewSet, homeAppRowState = homeAppRowState)
    } else {
        val currentIndex by AppGraph.runner.currentIndexFlow.collectAsState()
        PanelContent(state = state.copy(view = pending ?: config.panelView,
            sets = config.screenSets, currentSetIndex = currentIndex,
            setAppInfo = { catalog.info(it, installedPkgs) }),
            actions = actions.copy(onSelectView = { view ->
                pending = view
                scope.launch {
                    try {
                        viewSaveMutex.withLock {
                            AppGraph.config.update { it.copy(panelView = view) }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        DiagLog.d("Panel", "panel view save failed: $e")
                    }
                }
            }, onOpenSet = { if (AppGraph.runner.opening.value == null) AppGraph.runner.open(it) },
                onNewSet = { if (AppGraph.runner.opening.value == null) onNewSet() }))
    }
}

@Composable
internal fun HomeWideContent(
    state: PanelState,
    actions: PanelActions,
    config: LauncherConfig,
    appInfo: (String) -> AppInfo,
    currentIndex: Int,
    onOpen: (Int) -> Unit,
    onNewSet: () -> Unit,
    homeAppRowState: LazyListState = rememberLazyListState(),
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val playerWidth = PanelLayout.homePlayerWidthDp(maxWidth.value)
        Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                val clockHeight = maxHeight * 0.22f
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (state.needsPermission) {
                        PermissionBanner(state.missingPermissions, actions.onOpenPermissions)
                    }
                    HomeClockHeader(clockHeight)
                    HomeSetCards(config.screenSets, appInfo, currentIndex, onOpen, onNewSet,
                        Modifier.fillMaxWidth().weight(1f))
                    if (state.homeEntries.isNotEmpty()) {
                        com.gt8d.launcher.ui.home.HomeAppRow(
                            state.homeEntries, { appInfo(it).icon }, actions.onLaunchEntry,
                            Modifier.fillMaxWidth(), state = homeAppRowState)
                    }
                }
            }
            HomeMiniPlayer(state.now, state.idleApp, state.positionMs, appInfo, actions.onMediaAction,
                actions.onSeek, actions.onLaunch, Modifier.width(playerWidth.dp).fillMaxHeight())
            Dock(state.dockStatus, state.dockApps, state.showCar, actions.onHome, actions.onSplit,
                actions.onCar, actions.onAppList, actions.onAppListLongPress, actions.onLaunch,
                state.settingsApp, actions.onSettings)
        }
    }
}
