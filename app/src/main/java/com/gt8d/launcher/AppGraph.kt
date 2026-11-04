package com.gt8d.launcher

import android.app.ActivityManager
import android.app.Application
import android.content.Intent
import android.os.SystemClock
import com.gt8d.launcher.media.MediaWatcher
import com.gt8d.launcher.media.lyrics.LyricsCache
import com.gt8d.launcher.media.lyrics.AmllCache
import com.gt8d.launcher.media.lyrics.AmllClient
import com.gt8d.launcher.media.lyrics.AmllIndexStore
import com.gt8d.launcher.media.lyrics.AmllIndexUrlConnectionTransport
import com.gt8d.launcher.media.lyrics.AmllUrlConnectionTransport
import com.gt8d.launcher.media.lyrics.AppleLyricsCache
import com.gt8d.launcher.media.lyrics.AppleLyricsClient
import com.gt8d.launcher.media.lyrics.AppleLyricsOkHttpTransport
import com.gt8d.launcher.media.lyrics.AppleMusicSession
import com.gt8d.launcher.media.lyrics.AndroidAppleCredentialStore
import com.gt8d.launcher.media.lyrics.observeAppleMusicRestore
import com.gt8d.launcher.settings.AppleMusicWebData
import com.gt8d.launcher.media.lyrics.AppleWebTokenProvider
import com.gt8d.launcher.media.lyrics.LyricsRepository
import com.gt8d.launcher.media.lyrics.LrclibClient
import com.gt8d.launcher.media.lyrics.UrlConnectionTransport
import com.gt8d.launcher.notice.NoticeOverlay
import com.gt8d.launcher.apps.AppCatalog
import com.gt8d.launcher.apps.AppCatalogState
import com.gt8d.launcher.apps.FrequentApp
import com.gt8d.launcher.apps.UsageHistory
import com.gt8d.launcher.apps.rankFrequent
import com.gt8d.launcher.apps.settingsComponent
import com.gt8d.launcher.apps.HiddenApps
import com.gt8d.launcher.apps.startStockLauncher
import com.gt8d.launcher.split.AndroidStockLauncherWarmupState
import com.gt8d.launcher.split.StockLauncherWarmup
import com.gt8d.launcher.apps.OwnPackages
import com.gt8d.launcher.boot.BootSequence
import com.gt8d.launcher.config.ConfigRepository
import com.gt8d.launcher.config.LauncherConfig
import com.gt8d.launcher.curtain.CurtainController
import com.gt8d.launcher.curtain.CurtainTarget
import com.gt8d.launcher.curtain.CurtainView
import com.gt8d.launcher.curtain.HideReason
import com.gt8d.launcher.diag.DiagLog
import com.gt8d.launcher.split.PanelPlacement
import com.gt8d.launcher.edge.EdgeAction
import com.gt8d.launcher.edge.EdgeStripService
import com.gt8d.launcher.foreground.ForegroundWatcher
import com.gt8d.launcher.foreground.UsageStatsForegroundEvents
import com.gt8d.launcher.foreground.WarmTracker
import com.gt8d.launcher.safety.SafetyGuard
import com.gt8d.launcher.sets.Opening
import com.gt8d.launcher.sets.SetRunner
import com.gt8d.launcher.sets.SetRunnerDeps
import com.gt8d.launcher.split.AndroidActivityLauncher
import com.gt8d.launcher.split.FileSplitModeStore
import com.gt8d.launcher.split.SplitModeSelector
import com.gt8d.launcher.split.VendorBridge
import com.gt8d.launcher.split.VendorSplitLauncher
import com.gt8d.launcher.time.ElapsedClock
import com.gt8d.launcher.time.MainThreadScheduler
import com.gt8d.launcher.ui.home.HomeActivity
import com.gt8d.launcher.ui.panel.PanelForwarder
import java.io.File
import java.io.IOException
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

object AppGraph {
    private val lyricsScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val appleMusic: AppleMusicSession by lazy {
        val transport = AppleLyricsOkHttpTransport()
        val tokens = AppleWebTokenProvider(transport, diagnosticsEnabled = true)
        val client = AppleLyricsClient(transport, tokens, diagnosticsEnabled = true)
        AppleMusicSession(client::connect, client::fetch, AndroidAppleCredentialStore(app), lyricsScope)
    }
    val appleMusicWebData: AppleMusicWebData by lazy { AppleMusicWebData(lyricsScope) }
    val lyrics: LyricsRepository by lazy {
        observeAppleMusicRestore(lyricsScope, MediaWatcher.nowPlaying,
            latestConfig.map { it?.lyricsEnabled == true }) { appleMusic.restoreIfNeeded() }
        val versionName = try {
            app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "?"
        } catch (_: Exception) {
            "?"
        }
        val userAgent = "YLuncher/$versionName (https://github.com/munder-sa/YLuncher)"
        val client = LrclibClient(UrlConnectionTransport(), userAgent)
        val index = AmllIndexStore(File(app.filesDir, "lyrics/amll-index"), AmllIndexUrlConnectionTransport(userAgent))
        val amll = AmllClient(AmllUrlConnectionTransport(), userAgent, index)
        LyricsRepository(
            scope = lyricsScope,
            nowPlaying = MediaWatcher.nowPlaying,
            enabled = latestConfig.map { it?.lyricsEnabled == true },
            cache = LyricsCache(File(app.filesDir, "lyrics"), System::currentTimeMillis),
            fetch = { q -> withContext(Dispatchers.IO) { client.fetch(q) } },
            clock = SystemClock::elapsedRealtime,
            io = Dispatchers.IO,
            amllCache = AmllCache(File(app.filesDir, "lyrics/amll"), System::currentTimeMillis),
            fetchAmll = amll::fetch,
            appleConnection = appleMusic.state,
            appleCache = AppleLyricsCache(SystemClock::elapsedRealtime),
            fetchApple = appleMusic::fetch,
        )
    }
    private val _frequentApps = MutableStateFlow<List<FrequentApp>?>(null)
    val frequentApps: StateFlow<List<FrequentApp>?> = _frequentApps.asStateFlow()
    private val _usageAvailable = MutableStateFlow(false)
    val usageAvailable: StateFlow<Boolean> = _usageAvailable.asStateFlow()
    private var frequentRefreshRunning = false
    private var frequentTestOverride = false

    @Synchronized
    internal fun setFrequentAppsForTest(ranked: List<FrequentApp>?, available: Boolean) {
        frequentTestOverride = true
        _frequentApps.value = ranked
        _usageAvailable.value = available
    }

    @Synchronized
    internal fun clearFrequentAppsForTest() {
        frequentTestOverride = false
        _frequentApps.value = null
        _usageAvailable.value = false
    }

    @Synchronized
    fun refreshFrequentApps() {
        if (!::app.isInitialized || frequentRefreshRunning || frequentTestOverride) return
        frequentRefreshRunning = true
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val history = UsageHistory(app)
                val available = history.available()
                val launches = history.launchesSince(System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000)
                val excluded = setOfNotNull(app.packageName, OwnPackages.PANEL_HOST,
                    settingsComponent(app.packageManager)?.packageName) + HiddenApps.packages
                val ranked = launches?.let { rankFrequent(it, catalog.installed() - excluded) }
                synchronized(this@AppGraph) {
                    if (!frequentTestOverride) {
                        _usageAvailable.value = available
                        _frequentApps.value = ranked
                    }
                }
                DiagLog.d("Frequent", "events=${launches?.size} top=$ranked")
            } finally {
                synchronized(this@AppGraph) { frequentRefreshRunning = false }
            }
        }
    }

    // 場面テストの受け口からパネルに起動を頼む
    val panelLaunchRequests = MutableSharedFlow<String>(extraBufferCapacity = 4)
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _panelPlacement = MutableStateFlow<PanelPlacement?>(null)
    val panelPlacement: StateFlow<PanelPlacement?> = _panelPlacement.asStateFlow()

    fun reportPanelPlacement(p: PanelPlacement?) {
        if (_panelPlacement.value == p) return
        _panelPlacement.value = p
        DiagLog.d("Panel", "placement=$p")
        runner.panelPlacementChanged(p)
    }

    @Volatile var homeResumed: Boolean = false
    @Volatile var panelInSplit: Boolean = false
    @Volatile var panelAlive: Boolean = false
    @Volatile var homePausedAtMs: Long? = null
    @Volatile var panelSplitPausedAtMs: Long? = null

    private lateinit var app: Application

    val catalog: AppCatalog by lazy {
        AppCatalog(app)
    }

    internal var appCatalogStateForTest: AppCatalogState? = null
    private var sharedAppCatalogState: AppCatalogState? = null
    val appCatalogState: AppCatalogState
        @Synchronized get() = appCatalogStateForTest ?: sharedAppCatalogState
            ?: AppCatalogState(app.applicationContext, AppCatalog(app)).also {
                sharedAppCatalogState = it
                it.start()
            }

    val config: ConfigRepository by lazy {
        ConfigRepository(
            file = File(app.filesDir, "config.json"),
            installed = catalog::installed,
        )
    }

    val bridge: VendorBridge by lazy {
        VendorBridge(AndroidActivityLauncher(app))
    }

    val selector: SplitModeSelector by lazy {
        SplitModeSelector(
            store = FileSplitModeStore(File(app.filesDir, "split_mode.json")),
            dReady = { false },
        )
    }

    val watcher: ForegroundWatcher by lazy {
        ForegroundWatcher(UsageStatsForegroundEvents(app))
    }

    val warm: WarmTracker by lazy {
        WarmTracker(
            ownPkg = app.packageName,
            ownTaskExists = {
                val am = app.getSystemService(ActivityManager::class.java)
                am?.appTasks?.isNotEmpty() == true
            },
        )
    }

    val curtain: CurtainController by lazy {
        CurtainController(
            scheduler = MainThreadScheduler(),
            view = CurtainViewProxy,
        )
    }

    val noticeOverlay: NoticeOverlay by lazy {
        NoticeOverlay(app, { curtain.isShowing }, { runner.home() })
    }

    val runner: SetRunner by lazy {
        val scheduler = MainThreadScheduler()
        val apps = AndroidActivityLauncher(app)
        SetRunner(
            SetRunnerDeps(
                config = { latestConfig.value },
                installed = catalog::installed,
                lastIndex = config,
                selector = selector,
                vendor = VendorSplitLauncher(bridge),
                appPair = null,
                apps = apps,
                redirects = com.gt8d.launcher.apps.AndroidSplitRedirects(app.packageManager),
                watcher = watcher,
                warm = warm,
                stockLauncherWarmup = StockLauncherWarmup(
                    ElapsedClock, scheduler, watcher, AndroidStockLauncherWarmupState(app),
                    { startStockLauncher(app) },
                ),
                curtain = curtain,
                scheduler = scheduler,
                clock = ElapsedClock,
                ownPkg = app.packageName,
                panelHostPkg = OwnPackages.PANEL_HOST,
                labelFor = catalog::label,
                startHome = {
                    val intent = Intent(app, HomeActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    app.startActivity(intent)
                },
                messenger = noticeOverlay,
                homeVisible = { AppGraph.homeResumed },
                updateConfig = { f -> config.update(f) },
                scope = applicationScope,
            ),
        ).also { r ->
            applicationScope.launch {
                panelPlacement.collect { r.onPanelPlacementChanged(it) }
            }
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).launch {
                var prev: Opening? = r.opening.value
                r.opening.collect { curr ->
                    val wasNonNull = prev != null
                    prev = curr
                    if (wasNonNull && curr == null) {
                        notifyOpeningCleared()
                    }
                }
            }
        }
    }

    private val _latestConfig = MutableStateFlow<LauncherConfig?>(null)
    private val _configError = MutableStateFlow(false)
    val configError: StateFlow<Boolean> = _configError.asStateFlow()
    internal val configSnapshotForTest: LauncherConfig?
        get() = _latestConfig.value

    internal fun resetConfigStateForTest() {
        _latestConfig.value = null
        _configError.value = false
    }

    internal suspend fun collectConfig(source: () -> Flow<LauncherConfig>) {
        try {
            source().collect { cfg ->
                _latestConfig.value = cfg
                EdgeStripService.applyGesture(cfg.gesture)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            DiagLog.d("Config", "load failed: $e")
            _configError.value = true
        }
    }

    internal var configFlowForHomeTest: StateFlow<LauncherConfig?>? = null
    internal val configForHome: StateFlow<LauncherConfig?>
        get() = configFlowForHomeTest ?: latestConfig

    val latestConfig: StateFlow<LauncherConfig?> by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            collectConfig { config.config }
        }
        _latestConfig.asStateFlow()
    }

    private val forwarders = CopyOnWriteArrayList<WeakReference<PanelForwarder>>()

    var bootHandled: Boolean = false

    private var bootInstance: BootSequence? = null

    val boot: BootSequence
        get() = bootInstance ?: run {
            var readyAtOpen = 0
            var deadlineReached = false
            BootSequence(
                scheduler = MainThreadScheduler(),
                safetyActive = { SafetyGuard.isFallbackActive(app) },
                openLast = {
                    readyAtOpen = runner.readyCount
                    runner.openForBoot(config.lastSetIndex)
                },
                splitAppeared = { runner.readyCount > readyAtOpen },
                goHome = { runner.abortBootToHome(timedOut = deadlineReached) },
                record = { msg ->
                    if (msg == "boot_timeout") deadlineReached = true
                    DiagLog.d("Boot", msg)
                },
            )
        }.also { bootInstance = it }

    internal fun resetBootForTest() {
        bootInstance = null
    }

    val forwarderFactory: () -> PanelForwarder = {
        PanelForwarder(
            opening = { runner.opening.value },
            scheduler = MainThreadScheduler(),
        ).also { forwarders.add(WeakReference(it)) }
    }

    @Synchronized
    fun init(app: Application) {
        if (::app.isInitialized && this.app !== app) {
            sharedAppCatalogState?.close()
            sharedAppCatalogState = null
        }
        this.app = app
        refreshFrequentApps()
        EdgeStripService.listener = { action ->
            when (action) {
                EdgeAction.Next -> runner.next()
                EdgeAction.Prev -> runner.prev()
                EdgeAction.Home -> runner.home()
                EdgeAction.None -> Unit
            }
        }
    }

    private fun notifyOpeningCleared() {
        for (ref in forwarders) {
            val forwarder = ref.get()
            if (forwarder == null) {
                forwarders.remove(ref)
            } else {
                forwarder.onOpeningCleared()
            }
        }
    }

    private object CurtainViewProxy : CurtainView {
        override fun show(target: CurtainTarget) {
            EdgeStripService.curtainView?.show(target)
        }

        override fun update(target: CurtainTarget) {
            EdgeStripService.curtainView?.update(target)
        }

        override fun showProgress() {
            EdgeStripService.curtainView?.showProgress()
        }

        override fun hide(reason: HideReason) {
            try {
                EdgeStripService.curtainView?.hide(reason)
            } finally {
                noticeOverlay.onCurtainHidden(reason)
            }
        }
    }
}
