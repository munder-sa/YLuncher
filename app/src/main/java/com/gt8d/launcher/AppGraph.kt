package com.gt8d.launcher

import android.app.ActivityManager
import android.app.Application
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.gt8d.launcher.apps.AppCatalog
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
import com.gt8d.launcher.sets.Messenger
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object AppGraph {
    private val _panelPlacement = MutableStateFlow<PanelPlacement?>(null)
    val panelPlacement: StateFlow<PanelPlacement?> = _panelPlacement.asStateFlow()

    fun reportPanelPlacement(p: PanelPlacement?) {
        if (_panelPlacement.value == p) return
        _panelPlacement.value = p
        DiagLog.d("Panel", "placement=$p")
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

    val runner: SetRunner by lazy {
        val mainHandler = Handler(Looper.getMainLooper())
        val scheduler = MainThreadScheduler()
        val apps = AndroidActivityLauncher(app)
        val messenger = object : Messenger {
            override fun showSplitUnavailable() {
                mainHandler.post {
                    Toast.makeText(app, "分割が使えなくなりました", Toast.LENGTH_SHORT).show()
                }
            }

            override fun showSwitchFailed() {
                mainHandler.post {
                    Toast.makeText(app, "切り替えに失敗しました", Toast.LENGTH_SHORT).show()
                }
            }
        }
        SetRunner(
            SetRunnerDeps(
                config = { latestConfig.value },
                installed = catalog::installed,
                lastIndex = config,
                selector = selector,
                vendor = VendorSplitLauncher(bridge),
                appPair = null,
                apps = apps,
                watcher = watcher,
                warm = warm,
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
                messenger = messenger,
            ),
        ).also { r ->
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
            BootSequence(
                scheduler = MainThreadScheduler(),
                safetyActive = { SafetyGuard.isFallbackActive(app) },
                openLast = {
                    readyAtOpen = runner.readyCount
                    runner.openForBoot(config.lastSetIndex)
                },
                splitAppeared = { runner.readyCount > readyAtOpen },
                goHome = { runner.abortBootToHome() },
                record = { msg -> DiagLog.d("Boot", msg) },
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
        this.app = app
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
            EdgeStripService.curtainView?.hide(reason)
        }
    }
}
