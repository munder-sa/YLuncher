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
import com.gt8d.launcher.config.MAX_PANEL_APPS
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gt8d.launcher.ui.lifecycle.VisibleUiEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.gt8d.launcher.AppGraph
import com.gt8d.launcher.apps.HiddenApps
import com.gt8d.launcher.apps.dockAppsFor
import com.gt8d.launcher.config.Slot
import com.gt8d.launcher.media.MediaWatcher
import com.gt8d.launcher.media.positionAt
import com.gt8d.launcher.split.AndroidActivityLauncher
import com.gt8d.launcher.split.PanelPlacement
import com.gt8d.launcher.ui.apps.AppListScreen
import com.gt8d.launcher.ui.apps.AppListEntry
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
    val latestConfig by AppGraph.latestConfig.collectAsStateWithLifecycle()
    val catalog = AppGraph.appCatalogState
    val apps by catalog.snapshot.collectAsStateWithLifecycle()
    val environment = rememberPanelEnvironment(catalog)
    val config = homeConfig ?: latestConfig ?: return
    var pending by rememberSaveable { mutableStateOf<PanelView?>(null) }
    val scope = rememberCoroutineScope()
    val viewSaveMutex = remember { Mutex() }
    LaunchedEffect(config.panelView, pending) {
        if (pending == config.panelView) pending = null
    }
    val now by MediaWatcher.nowPlaying.collectAsStateWithLifecycle()
    val lyrics by AppGraph.lyrics.state.collectAsStateWithLifecycle()
    var showAppList by rememberSaveable { mutableStateOf(false) }
    val iconPage = rememberSaveable { mutableIntStateOf(0) }
    val frequent by AppGraph.frequentApps.collectAsStateWithLifecycle()
    val settings = apps.settings
    val settingsApp = remember(settings, apps.appRevision) {
        settings?.let { AppInfo(it.packageName, "設定", apps.info(it.packageName).icon, true) }
    }
    val required = remember(config, now?.pkg, frequent, apps.installed, settings) {
        (config.homeApps + config.panelApps + config.dockApps + config.idleApp +
            config.screenSets.flatMap { listOfNotNull((it.left as? Slot.App)?.pkg, (it.right as? Slot.App)?.pkg) } +
            frequent.orEmpty().map { it.pkg } + listOfNotNull(now?.pkg, settings?.packageName))
            .filter { it.isNotBlank() }.toSet()
    }
    LaunchedEffect(required) { catalog.request(required) }
    var prepared by remember(catalog) { mutableStateOf(apps.ready && apps.infos.keys.containsAll(required)) }
    LaunchedEffect(apps.ready, apps.infos.keys, required) {
        if (apps.ready && apps.infos.keys.containsAll(required)) prepared = true
    }
    if (!apps.ready || !prepared) return

    val appListGridState = rememberLazyGridState()
    val homeAppRowState = rememberLazyListState()
    val launchEntry: (AppListEntry) -> Unit = { entry ->
        val component = ComponentName(entry.pkg, entry.cls)
        if (onLaunchComponent != null) onLaunchComponent(component)
        else AndroidActivityLauncher(context).startComponent(component, adjacent = inSplit)
    }

    val launchable = environment.appListEntries.map { it.pkg }.toSet()
    val seed = seedAppList(config, emptySet(), launchable)
    val registered: Set<String> = (config.appListPkgs ?: seed).toSet()
    LaunchedEffect(homeConfig != null || latestConfig != null, config.appListPkgs, apps.ready) {
        if (apps.ready && (homeConfig != null || latestConfig != null) && config.appListPkgs == null) {
            try {
                val recent = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.gt8d.launcher.apps.UsageHistory(context)
                        .launchesSince(System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000)
                        ?.map { it.pkg }?.toSet().orEmpty() - setOf(context.packageName,
                        com.gt8d.launcher.apps.OwnPackages.PANEL_HOST) - HiddenApps.packages
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
        LaunchedEffect(apps.entries) { catalog.request(apps.entries.map { it.pkg }.toSet()) }
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
            iconRevision = environment.appRevision.toInt(),
            entries = environment.appListEntries,
            iconFor = { pkg -> apps.info(pkg).icon },
            onLaunch = { entry ->
                launchEntry(entry)
                showAppList = false
            },
            onClose = { showAppList = false },
            onOpenSettings = { showAppList = false; onOpenSettings() },
        )
        return
    }

    val positionState = remember(now) {
        mutableLongStateOf(now?.positionAt(SystemClock.elapsedRealtime()) ?: 0L)
    }

    VisibleUiEffect(now) {
        val current = now ?: run {
            positionState.longValue = 0L
            return@VisibleUiEffect
        }
        positionState.longValue = current.positionAt(SystemClock.elapsedRealtime())
        while (current.playing) {
            delay(500L)
            positionState.longValue = current.positionAt(SystemClock.elapsedRealtime())
        }
    }

    val installedPkgs = environment.installedPkgs
    val idleApp = remember(config.idleApp, installedPkgs, environment.appRevision) {
        apps.info(config.idleApp)
    }
    val panelApps = remember(config.panelApps, installedPkgs, environment.appRevision) {
        config.panelApps.map { apps.info(it) }
    }
    val dockApps = remember(config.dockMode, config.dockApps, frequent, installedPkgs,
        settings?.packageName, environment.appRevision) {
        dockAppsFor(config.dockMode, config.dockApps, frequent, installedPkgs,
        setOfNotNull(settings?.packageName) + HiddenApps.packages).map { apps.info(it) }
    }

    val state = PanelState(
        placement = placement,
        now = now,
        idleApp = idleApp,
        position = { positionState.longValue },
        panelApps = panelApps,
        dockApps = dockApps,
        dockStatus = environment.dockStatus,
        showCar = AppGraph.bridge.carHomeAvailable,
        needsPermission = environment.needsPermission,
        missingPermissions = environment.missingPermissions,
        settingsApp = settingsApp,
        lyrics = lyrics,
        lyricsEnabled = config.lyricsEnabled,
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
        onToggleLyrics = {
            scope.launch {
                try {
                    AppGraph.config.update { it.copy(lyricsEnabled = !it.lyricsEnabled) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DiagLog.d("Panel", "lyrics toggle save failed: $e")
                }
            }
        },
        onLaunch = onLaunchApp,
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
        val currentIndex by AppGraph.runner.currentIndexFlow.collectAsStateWithLifecycle()
        HomeWideContent(state, actions, config, { apps.info(it) }, currentIndex,
            onOpen = { AppGraph.runner.open(it) }, onNewSet = onNewSet, homeAppRowState = homeAppRowState)
    } else {
        val currentIndex by AppGraph.runner.currentIndexFlow.collectAsStateWithLifecycle()
        PanelContent(iconPage = iconPage, state = state.copy(view = pending ?: config.panelView,
            sets = config.screenSets, currentSetIndex = currentIndex,
            setAppInfo = { apps.info(it) }),
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

internal val LocalClockCompositionObserver = androidx.compose.runtime.staticCompositionLocalOf<() -> Unit> { {} }

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
                    val clockCompositionObserver = LocalClockCompositionObserver.current
                    androidx.compose.runtime.SideEffect { clockCompositionObserver() }
                    HomeClockHeader(clockHeight)
                    HomeSetCards(config.screenSets, appInfo, currentIndex, onOpen, onNewSet,
                        Modifier.fillMaxWidth().weight(1f))
                    com.gt8d.launcher.ui.home.HomeAppRow(
                        state.panelApps.take(MAX_PANEL_APPS), actions.onLaunch, actions.onAdd,
                        Modifier.fillMaxWidth(), state = homeAppRowState)
                }
            }
            HomeMiniPlayer(state.now, state.idleApp, state.position, appInfo, actions.onMediaAction,
                actions.onSeek, actions.onLaunch, Modifier.width(playerWidth.dp).fillMaxHeight(),
                lyrics = state.lyrics, lyricsEnabled = state.lyricsEnabled, onToggleLyrics = actions.onToggleLyrics)
            Dock(state.dockStatus, state.dockApps, state.showCar, actions.onHome, actions.onSplit,
                actions.onCar, actions.onAppList, actions.onAppListLongPress, actions.onLaunch,
                state.settingsApp, actions.onSettings)
        }
    }
}
