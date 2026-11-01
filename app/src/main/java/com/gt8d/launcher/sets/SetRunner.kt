package com.gt8d.launcher.sets

import com.gt8d.launcher.apps.OwnPackages
import com.gt8d.launcher.apps.SplitRedirect
import com.gt8d.launcher.apps.SplitRedirects
import com.gt8d.launcher.notice.Notice
import com.gt8d.launcher.ui.home.numberedSetLabel
import com.gt8d.launcher.config.LauncherConfig
import com.gt8d.launcher.config.ScreenSet
import com.gt8d.launcher.config.Side
import com.gt8d.launcher.config.panelSide
import com.gt8d.launcher.config.slotAt
import com.gt8d.launcher.config.Slot
import com.gt8d.launcher.config.SplitRatio
import com.gt8d.launcher.curtain.CurtainController
import com.gt8d.launcher.curtain.CurtainTarget
import com.gt8d.launcher.curtain.HideReason
import com.gt8d.launcher.curtain.SlotPreview
import com.gt8d.launcher.diag.DiagLog
import com.gt8d.launcher.foreground.ForegroundWatcher
import com.gt8d.launcher.foreground.WarmTracker
import com.gt8d.launcher.split.ActivityLauncher
import com.gt8d.launcher.split.FailReason
import com.gt8d.launcher.split.LaunchResult
import com.gt8d.launcher.split.SplitLauncher
import com.gt8d.launcher.split.SplitMode
import com.gt8d.launcher.split.SplitModeSelector
import com.gt8d.launcher.split.VendorBridge
import com.gt8d.launcher.split.StockLauncherWarmup
import com.gt8d.launcher.time.Cancellable
import com.gt8d.launcher.time.Clock
import com.gt8d.launcher.time.Scheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CoroutineScope
import com.gt8d.launcher.split.PanelPlacement
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Opening(val left: String, val right: String, val startedAtMs: Long)

interface LastIndexStore {
    var lastSetIndex: Int
}

interface Messenger {
    fun show(notice: Notice)
}

data class SetRunnerDeps(
    val config: () -> LauncherConfig?,
    val installed: () -> Set<String>,
    val lastIndex: LastIndexStore,
    val selector: SplitModeSelector,
    val vendor: SplitLauncher,
    val appPair: SplitLauncher?,
    val apps: ActivityLauncher,
    val watcher: ForegroundWatcher,
    val warm: WarmTracker,
    val stockLauncherWarmup: StockLauncherWarmup,
    val curtain: CurtainController,
    val scheduler: Scheduler,
    val clock: Clock,
    val ownPkg: String,
    val labelFor: (String) -> String,
    val startHome: () -> Unit,
    val messenger: Messenger,
    val panelHostPkg: String = OwnPackages.PANEL_HOST,
    val updateConfig: suspend ((LauncherConfig) -> LauncherConfig) -> Unit = {},
    val scope: CoroutineScope? = null,
    val homeVisible: () -> Boolean = { false },
    val redirects: SplitRedirects = SplitRedirects { null },
)

class SetRunner(private val d: SetRunnerDeps) {
    private val saver = d.scope?.let { scope ->
        PanelSwapSaver(scope, d.config, d.updateConfig, {
            lastOpenedPair = lastOpenedPair?.let { it.second to it.first }
        })
    }

    fun onPanelPlacementChanged(p: PanelPlacement?) { saver?.placementChanged(p) }
    private enum class WaitKind {
        None,
        Single,
        Both,
    }

    private val _opening = MutableStateFlow<Opening?>(null)
    val opening: StateFlow<Opening?> = _opening.asStateFlow()

    var currentIndex: Int = d.config()?.let {
        SetNavigator.clamp(d.lastIndex.lastSetIndex, it.screenSets.size)
    } ?: d.lastIndex.lastSetIndex
        private set(value) {
            field = value
            _currentIndex.value = value
        }

    private val _currentIndex = MutableStateFlow(currentIndex)
    val currentIndexFlow: StateFlow<Int> = _currentIndex.asStateFlow()

    var lastOpenedPair: Pair<String, String>? = null
        private set

    var lastOpenedAtMs: Long? = null
        private set

    var lastReadyAtMs: Long? = null
        private set

    private var linkFallback: (() -> Unit)? = null
    private var linkReady: (() -> Unit)? = null
    private var linkWaitBoth = false

    private fun cancelLinkCallbacks() {
        linkFallback = null
        linkReady = null
        linkWaitBoth = false
    }

    var readyCount: Int = 0
        private set

    private data class PendingOpen(val index: Int, val forBoot: Boolean)
    private var pending: PendingOpen? = null
    private var waitKind: WaitKind = WaitKind.None
    private var panelResumed: Boolean = false
    private var panelSessionReady = false
    private var ownScreenSeen: Boolean = false
    private var bothProgress: (() -> Boolean)? = null
    private var openingForBoot = false
    private var monitoringAvailable: Boolean = true
    private var expectedDelayMs: Long = WarmTracker.COLD_MS
    private val activeTimers = mutableListOf<Cancellable>()
    private var cancelGuard: Cancellable? = null
    private var warmupTimer: Cancellable? = null
    private var preparing = false
    private var vendorWaiting = false
    private var operationGeneration = 0L

    private fun stopCancelGuard() {
        cancelGuard?.cancel()
        cancelGuard = null
    }

    private fun startCancelGuard(opening: Opening?, cancelledAtMs: Long) {
        stopCancelGuard()
        if (opening == null || !d.watcher.available()) return
        val packages = setOf(opening.left, opening.right, VendorBridge.VENDOR_PKG, STOCK_LAUNCHER_PKG)
        val deadline = cancelledAtMs + CANCEL_GUARD_MS
        var since = cancelledAtMs
        var returns = 0
        fun poll() {
            cancelGuard = d.scheduler.schedule(POLL_MS) {
                cancelGuard = null
                val now = d.clock.nowMs()
                if (now >= deadline || !d.watcher.available()) return@schedule
                val pkg = d.watcher.lastForegroundSince(since)
                if (!d.watcher.available()) return@schedule
                if (pkg in packages) {
                    DiagLog.d("SetRunner", "cancel guard home pkg=$pkg")
                    d.startHome()
                    since = d.clock.nowMs() + 1L
                    returns++
                }
                if (returns < 2) poll()
            }
        }
        poll()
    }

    fun cancelOpening() {
        if (d.curtain.isShowing) {
            d.curtain.cancel()
        } else if (_opening.value != null) {
            onCurtainHidden(HideReason.Cancelled)
        }
    }

    init {
        d.curtain.onHidden = ::onCurtainHidden
    }

    private fun configForOperation(): LauncherConfig? = d.config()

    fun next() {
        stopCancelGuard()
        val cfg = configForOperation() ?: return
        val base = pending?.index ?: currentIndex
        val setCount = cfg.screenSets.size
        val target = SetNavigator.next(base, setCount)
        DiagLog.d("SetRunner", "next base=$base -> $target")
        open(target, cfg)
    }

    // 設定でセットの並びが変わったとき、番号だけを付け替える(画面は切り替えない)。
    fun followIndex(index: Int) { currentIndex = index; d.lastIndex.lastSetIndex = index }

    fun prev() {
        stopCancelGuard()
        val cfg = configForOperation() ?: return
        val base = pending?.index ?: currentIndex
        val setCount = cfg.screenSets.size
        val target = SetNavigator.prev(base, setCount)
        DiagLog.d("SetRunner", "prev base=$base -> $target")
        open(target, cfg)
    }

    fun home() {
        stopCancelGuard()
        val cfg = configForOperation() ?: return
        home(cfg)
    }

    private fun home(cfg: LauncherConfig) {
        cancelLinkCallbacks()
        DiagLog.d("SetRunner", "home")
        if (_opening.value != null) {
            pending = PendingOpen(SetNavigator.HOME, false)
            d.curtain.update(targetFor(SetNavigator.HOME, cfg))
            return
        }
        panelSessionReady = false
        onSetEnded("home")
        currentIndex = SetNavigator.HOME
        d.lastIndex.lastSetIndex = SetNavigator.HOME
        lastOpenedPair = null
        lastOpenedAtMs = null
        lastReadyAtMs = null
        d.curtain.show(targetFor(SetNavigator.HOME, cfg), WarmTracker.WARM_MS, d.watcher.available())
        d.startHome()
        d.curtain.ready()
    }

    fun open(index: Int) {
        stopCancelGuard()
        val cfg = configForOperation() ?: return
        open(index, cfg)
    }

    private fun open(index: Int, cfg: LauncherConfig) {
        cancelLinkCallbacks()
        DiagLog.d("SetRunner", "open index=$index")
        if (_opening.value != null) {
            pending = PendingOpen(index, false)
            d.curtain.update(targetFor(index, cfg))
            DiagLog.d("SetRunner", "open deferred pending=$index")
            return
        }
        if (index == SetNavigator.HOME) {
            home(cfg)
            return
        }
        openInternal(index, cfg, useCurtain = true)
    }

    fun openForBoot(index: Int) {
        stopCancelGuard()
        val cfg = configForOperation() ?: return
        cancelLinkCallbacks()
        DiagLog.d("SetRunner", "openForBoot index=$index")
        if (_opening.value != null) {
            pending = PendingOpen(index, true)
            return
        }
        if (index == SetNavigator.HOME) {
            panelSessionReady = false
            onSetEnded("boot home")
            currentIndex = SetNavigator.HOME
            d.lastIndex.lastSetIndex = SetNavigator.HOME
            lastOpenedPair = null
            lastOpenedAtMs = null
            lastReadyAtMs = null
            d.startHome()
            return
        }
        openInternal(index, cfg, useCurtain = false)
    }

    fun abortBootToHome(timedOut: Boolean = false) {
        if (_opening.value != null && !openingForBoot) return
        if (timedOut) requestRewarmOnTimeout()
        panelSessionReady = false
        onSetEnded("abort boot")
        cancelTimers()
        pending = null
        cancelLinkCallbacks()
        waitKind = WaitKind.None
        panelResumed = false
        openingForBoot = false
        currentIndex = SetNavigator.HOME
        d.lastIndex.lastSetIndex = SetNavigator.HOME
        lastOpenedPair = null
        lastOpenedAtMs = null
        lastReadyAtMs = null
        _opening.value = null
        d.startHome()
    }

    fun ownScreenResumed() {
        if (_opening.value != null && !preparing) ownScreenSeen = true
    }

    fun panelResumedInSplit() {
        DiagLog.d("SetRunner", "panelResumedInSplit")
        if (!preparing) panelResumed = true
    }

    fun panelLaunched(pkg: String) {
        if (!panelSessionReady) return
        val pair = lastOpenedPair ?: return
        lastOpenedPair = when (d.panelHostPkg) {
            pair.first -> pair.first to pkg
            pair.second -> pkg to pair.second
            else -> return
        }
        DiagLog.d("SetRunner", "panelLaunched pkg=$pkg pair=$lastOpenedPair")
        saver?.partnerChanged(pkg)
    }

    private fun onSetReady(set: ScreenSet, partner: String) {
        val side = set.panelSide
        if (side != null) {
            saver?.begin(set, partner)
        }
        DiagLog.d("SetRunner", "session ready id=${set.id}")
    }

    private fun onSetEnded(reason: String) {
        saver?.end(reason)
        DiagLog.d("SetRunner", "session ended reason=$reason")
    }

    fun openForLink(left: String, right: String, onReady: () -> Unit, onNotSplit: () -> Unit): Boolean {
        stopCancelGuard()
        cancelLinkCallbacks()
        val cfg = configForOperation() ?: return false
        if (_opening.value != null) return false
        linkFallback = onNotSplit
        linkReady = onReady
        openSet(
            ScreenSet("link", Slot.App(left), Slot.App(right), SplitRatio.R5_5),
            CurtainTarget(
                setName = "リンクを開く",
                left = SlotPreview.App(left, d.labelFor(left)),
                right = SlotPreview.App(right, d.labelFor(right)),
                ratio = SplitRatio.R5_5,
                position = currentIndex,
                count = cfg.screenSets.size + 1,
            ),
            useCurtain = true,
        )
        return true
    }

    private fun notifyLinkNotSplit() {
        val fallback = linkFallback
        cancelLinkCallbacks()
        fallback?.invoke()
    }

    private fun openInternal(index: Int, cfg: LauncherConfig, useCurtain: Boolean) {
        linkFallback = null
        val set = cfg.screenSets.getOrNull(index - 1)
        if (set == null) {
            if (useCurtain) {
                home(cfg)
            } else {
                panelSessionReady = false
                onSetEnded("boot home")
                currentIndex = SetNavigator.HOME
                d.lastIndex.lastSetIndex = SetNavigator.HOME
                lastOpenedPair = null
                lastOpenedAtMs = null
                lastReadyAtMs = null
                d.startHome()
            }
            return
        }

        currentIndex = index
        d.lastIndex.lastSetIndex = index

        openSet(set, targetFor(index, cfg), useCurtain)
    }

    private fun openSet(set: ScreenSet, target: CurtainTarget, useCurtain: Boolean) {
        panelSessionReady = false
        onSetEnded("open ${set.id}")
        val forLink = linkFallback != null
        val panelSide = set.panelSide
        val originalLeft = when (val l = set.left) {
            is Slot.App -> l.pkg
            Slot.Panel -> d.panelHostPkg
        }
        val originalRight = when (val r = set.right) {
            is Slot.App -> r.pkg
            is Slot.Panel -> d.panelHostPkg
        }
        var leftRedirect = if (!forLink && set.left is Slot.App) d.redirects.forPackage(originalLeft) else null
        var rightRedirect = if (!forLink && set.right is Slot.App) d.redirects.forPackage(originalRight) else null
        if ((leftRedirect?.targetPkg ?: originalLeft) == (rightRedirect?.targetPkg ?: originalRight)) {
            leftRedirect = null
            rightRedirect = null
        }
        val left = leftRedirect?.targetPkg ?: originalLeft
        val right = rightRedirect?.targetPkg ?: originalRight
        val appPkg = if (panelSide == Side.Left) originalRight else originalLeft
        val waitPkg = if (panelSide == Side.Left) right else left

        val startMs = d.clock.nowMs()
        d.watcher.foregroundPkgsSince(startMs)
        monitoringAvailable = d.watcher.available()
        expectedDelayMs = d.warm.expectedDelayMs(left, right)
        panelResumed = false
        ownScreenSeen = false
        openingForBoot = !useCurtain
        _opening.value = Opening(left, right, startMs)
        lastOpenedPair = left to right
        lastOpenedAtMs = startMs
        lastReadyAtMs = null

        operationGeneration++
        vendorWaiting = false
        fun showOrdinaryCurtain() {
            if (useCurtain) d.curtain.show(target, expectedDelayMs, monitoringAvailable)
        }

        val installedPkgs = d.installed()
        if (panelSide == Side.Left) {
            if (appPkg !in installedPkgs) {
                showOrdinaryCurtain()
                d.messenger.show(Notice.AppMissing(appLabel(appPkg)))
                DiagLog.d("SetRunner", "right uninstalled: $appPkg")
                fallbackHome(startMs, useCurtain)
                return
            }
            if (d.panelHostPkg !in installedPkgs) {
                showOrdinaryCurtain()
                d.messenger.show(Notice.AppMissing("YLuncher パネル"))
                DiagLog.d("SetRunner", "panel host missing")
                d.warm.recordAppLaunch(appPkg, d.apps.startApp(appPkg, false))
                waitSingle(waitPkg, startMs, useCurtain)
                return
            }
        }
        if (originalLeft !in installedPkgs) {
            showOrdinaryCurtain()
            d.messenger.show(Notice.AppMissing(appLabel(originalLeft)))
            DiagLog.d("SetRunner", "left uninstalled: $originalLeft")
            if (panelSide == null && (forLink || originalRight in installedPkgs)) {
                d.warm.recordAppLaunch(originalRight, d.apps.startApp(originalRight, false))
                waitSingle(right, startMs, useCurtain)
            } else {
                fallbackHome(startMs, useCurtain)
            }
            notifyLinkNotSplit()
            return
        }

        if (!forLink && originalRight !in installedPkgs) {
            showOrdinaryCurtain()
            d.messenger.show(Notice.AppMissing(if (panelSide == Side.Right) "YLuncher パネル" else appLabel(originalRight)))
            DiagLog.d("SetRunner", if (panelSide != null) "panel host missing" else "right uninstalled: $originalRight")
            d.warm.recordAppLaunch(originalLeft, d.apps.startApp(originalLeft, false))
            waitSingle(left, startMs, useCurtain)
            notifyLinkNotSplit()
            return
        }

        val leftResizeable = panelSide == Side.Left || d.apps.isResizeable(left)
        val rightResizeable = panelSide == Side.Right || d.apps.isResizeable(right)
        if (!leftResizeable || !rightResizeable) {
            showOrdinaryCurtain()
            DiagLog.d("SetRunner", "unresizeable left=$leftResizeable right=$rightResizeable")
            fullscreen(appPkg, waitPkg, Notice.SplitUnsupported(appLabel(if (!leftResizeable) originalLeft else originalRight)), startMs, useCurtain)
            notifyLinkNotSplit()
            return
        }

        val mode = d.selector.current()
        val launcher: SplitLauncher? = when (mode) {
            SplitMode.Vendor -> d.vendor
            SplitMode.AppPair -> d.appPair
            SplitMode.LeftOnly -> null
        }

        if (launcher == null) {
            showOrdinaryCurtain()
            DiagLog.d("SetRunner", "leftOnly mode=$mode")
            fullscreen(appPkg, waitPkg, Notice.SplitFailed(appLabel(appPkg)), startMs, useCurtain)
            notifyLinkNotSplit()
            return
        }

        if (mode == SplitMode.Vendor) {
            val id = operationGeneration
            fun requestSplit() {
                if (id != operationGeneration || _opening.value == null) return
                val wasPreparing = preparing
                preparing = false
                monitoringAvailable = d.watcher.available()
                val splitStartMs = d.clock.nowMs()
                ownScreenSeen = false
                panelResumed = false
                bothProgress = null
                vendorWaiting = true
                if (useCurtain) {
                    if (wasPreparing) d.curtain.beginWaiting(expectedDelayMs, monitoringAvailable)
                    else showOrdinaryCurtain()
                }
                if (id != operationGeneration || _opening.value == null) return
                waitBoth(left, right, panelSide, set, set.ratio, launcher, splitStartMs,
                    useCurtain, appPkg, listOfNotNull(leftRedirect, rightRedirect), vendor = true,
                    initialRequest = { launcher.open(left, right, set.ratio) })
            }
            if (d.stockLauncherWarmup.needsWarmup()) {
                preparing = true
                if (useCurtain) d.curtain.showPreparing(target)
                if (id != operationGeneration || _opening.value == null) return
                warmupTimer = d.stockLauncherWarmup.start(if (useCurtain) 200L else 0L) { requestSplit() }
            } else {
                requestSplit()
            }
            return
        }

        showOrdinaryCurtain()
        val result = launcher.open(left, right, set.ratio)
        DiagLog.d("SetRunner", "open result=$result mode=$mode")

        when (result) {
            is LaunchResult.Failed -> {
                handleLaunchFailure(result.reason, appPkg, waitPkg, useCurtain)
            }
            is LaunchResult.Started -> {
                waitBoth(
                    left = left,
                    right = right,
                    panelSide = panelSide,
                    set = set,
                    ratio = set.ratio,
                    launcher = launcher,
                    startMs = startMs,
                    useCurtain = useCurtain,
                    originalAppPkg = appPkg,
                    redirects = listOfNotNull(leftRedirect, rightRedirect),
                )
            }
        }
    }

    private fun fallbackHome(startMs: Long, useCurtain: Boolean) {
        val homeVisible = d.homeVisible()
        d.startHome()
        if (homeVisible) {
            if (useCurtain) d.curtain.ready() else finishWithoutCurtain()
        } else {
            waitSingle(d.ownPkg, startMs, useCurtain)
        }
    }

    private fun handleLaunchFailure(reason: FailReason, original: String, waitPkg: String, useCurtain: Boolean) {
        panelSessionReady = false
        onSetEnded("launch failure")
        waitKind = WaitKind.None
        cancelTimers()
        d.selector.onFailure(reason, countable = true)
        DiagLog.d("SetRunner", "launch failure original=$original wait=$waitPkg")
        fullscreen(original, waitPkg, Notice.SplitFailed(appLabel(original)), null, useCurtain)
        notifyLinkNotSplit()
        if (useCurtain) {
            d.curtain.fail()
        } else {
            finishWithoutCurtain()
        }
    }

    private fun appLabel(pkg: String): String = d.labelFor(pkg).ifBlank { pkg }

    private fun fullscreen(pkg: String, waitPkg: String, success: Notice, startMs: Long?, useCurtain: Boolean) {
        val started = d.apps.startApp(pkg, false)
        d.warm.recordAppLaunch(pkg, started)
        d.messenger.show(if (started) success else Notice.OpenFailed(appLabel(pkg)))
        // E の失敗は従来どおり即座に終了し、それ以外の退避では前面化を待つ。
        if (startMs != null) waitSingle(waitPkg, startMs, useCurtain)
    }

    private fun waitBoth(
        left: String,
        right: String,
        panelSide: Side?,
        set: ScreenSet,
        ratio: SplitRatio,
        launcher: SplitLauncher,
        startMs: Long,
        useCurtain: Boolean,
        originalAppPkg: String,
        redirects: List<SplitRedirect>,
        vendor: Boolean = false,
        initialRequest: (() -> LaunchResult)? = null,
    ) {
        val id = operationGeneration
        val sinceMs = startMs
        waitKind = WaitKind.Both
        linkWaitBoth = linkReady != null
        val appPkg = if (panelSide == Side.Left) right else left

        val hasProgress = {
            val seen = d.watcher.foregroundPkgsSince(sinceMs)
            ownScreenSeen || panelResumed ||
                appPkg in seen || (panelSide == null && right in seen) || STOCK_LAUNCHER_PKG in seen
        }
        bothProgress = hasProgress

        fun isBothReady(): Boolean {
            if (!monitoringAvailable) return false
            val resumed = if (panelSide != null) d.watcher.bothResumed(appPkg, appPkg, sinceMs)
                else d.watcher.bothResumed(left, right, sinceMs)
            return if (panelSide != null) {
                panelResumed && resumed
            } else {
                resumed
            }
        }

        fun schedulePoll() {
            val timer = d.scheduler.schedule(POLL_MS) {
                if (id != operationGeneration || _opening.value == null) return@schedule
                val ready = isBothReady()
                if (stopMonitoringIfUnavailable(startMs, useCurtain)) return@schedule
                if (ready) {
                    DiagLog.d("SetRunner", "waitBoth ready left=$left right=$right")
                    lastReadyAtMs = d.clock.nowMs()
                    waitKind = WaitKind.None
                    cancelTimers()
                    readyCount++
                    d.selector.onSuccess()
                    d.warm.markForeground(left)
                    d.warm.markForeground(right)
                    if (panelSide != null) {
                        panelSessionReady = true
                        onSetReady(set, appPkg)
                    }
                    redirects.forEach { redirect ->
                        if (redirect.component != null || redirect.uri != null) {
                            val sent = d.apps.startRedirect(redirect, adjacent = false)
                            DiagLog.d("SetRunner", "redirect target=${redirect.targetPkg} sent=$sent")
                        }
                    }
                    if (useCurtain) {
                        d.curtain.ready()
                    } else {
                        finishWithoutCurtain()
                    }
                } else {
                    schedulePoll()
                }
            }
            activeTimers.add(timer)
        }

        if (initialRequest != null) {
            val result = initialRequest()
            DiagLog.d("SetRunner", "open result=$result mode=Vendor")
            if (id != operationGeneration || _opening.value == null) return
            if (result is LaunchResult.Failed) {
                handleLaunchFailure(result.reason, originalAppPkg, appPkg, useCurtain)
                return
            }
        }

        if (monitoringAvailable) schedulePoll()

        val retryTimer = d.scheduler.schedule(if (vendor) (startMs + RETRY_AT_MS - d.clock.nowMs()).coerceAtLeast(0L) else RETRY_AT_MS) {
            if (id != operationGeneration || _opening.value == null || !monitoringAvailable) return@schedule
            val ready = isBothReady()
            if (stopMonitoringIfUnavailable(startMs, useCurtain)) return@schedule
            if (!ready) {
                val progressed = hasProgress()
                if (stopMonitoringIfUnavailable(startMs, useCurtain)) return@schedule
                if (progressed) {
                    DiagLog.d("SetRunner", "retry skipped: progress left=$left right=$right")
                    return@schedule
                }
                DiagLog.d("SetRunner", "retry at $RETRY_AT_MS left=$left right=$right")
                val result = launcher.open(left, right, ratio)
                if (id != operationGeneration || _opening.value == null) return@schedule
                if (result is LaunchResult.Failed) {
                    handleLaunchFailure(result.reason, originalAppPkg, appPkg, useCurtain)
                }
            }
        }
        activeTimers.add(retryTimer)

        if (!useCurtain) {
            val bootTimer = d.scheduler.schedule(if (vendor) (startMs + BOOT_CLEAR_MS - d.clock.nowMs()).coerceAtLeast(0L) else BOOT_CLEAR_MS) {
                if (id == operationGeneration && _opening.value != null) {
                    requestRewarmOnTimeout()
                    DiagLog.d("SetRunner", "boot clear at $BOOT_CLEAR_MS")
                    panelSessionReady = false
                    onSetEnded("boot timeout")
                    waitKind = WaitKind.None
                    cancelTimers()
                    finishWithoutCurtain()
                }
            }
            activeTimers.add(bootTimer)
        }
    }

    private fun waitSingle(pkg: String, startMs: Long, useCurtain: Boolean) {
        panelSessionReady = false
        onSetEnded("single fallback")
        waitKind = WaitKind.Single

        fun schedulePoll() {
            val timer = d.scheduler.schedule(POLL_MS) {
                if (_opening.value == null) return@schedule
                val foreground = d.watcher.lastForegroundSince(startMs)
                if (stopMonitoringIfUnavailable(startMs, useCurtain)) return@schedule
                if (foreground == pkg) {
                    DiagLog.d("SetRunner", "waitSingle ready pkg=$pkg")
                    waitKind = WaitKind.None
                    cancelTimers()
                    readyCount++
                    d.warm.markForeground(pkg)
                    if (useCurtain) {
                        d.curtain.ready()
                    } else {
                        finishWithoutCurtain()
                    }
                } else {
                    schedulePoll()
                }
            }
            activeTimers.add(timer)
        }

        if (monitoringAvailable) schedulePoll()

        if (!useCurtain) {
            val bootTimer = d.scheduler.schedule(BOOT_CLEAR_MS) {
                if (_opening.value != null) {
                    DiagLog.d("SetRunner", "boot clear single at $BOOT_CLEAR_MS")
                    waitKind = WaitKind.None
                    cancelTimers()
                    finishWithoutCurtain()
                }
            }
            activeTimers.add(bootTimer)
        }
    }

    private fun stopMonitoringIfUnavailable(startMs: Long, useCurtain: Boolean): Boolean {
        if (!monitoringAvailable) return true
        if (d.watcher.available()) return false
        monitoringAvailable = false
        panelSessionReady = false
        onSetEnded("monitoring unavailable")
        waitKind = WaitKind.None
        if (useCurtain) {
            val remainingMs = startMs + expectedDelayMs - d.clock.nowMs()
            if (remainingMs <= 0) {
                notifyLinkNotSplit()
                d.curtain.ready()
            } else {
                activeTimers.add(d.scheduler.schedule(remainingMs) {
                    waitKind = WaitKind.None
                    notifyLinkNotSplit()
                    d.curtain.ready()
                })
            }
        }
        return true
    }

    private fun onCurtainHidden(reason: HideReason) {
        val progressed = if (monitoringAvailable && reason == HideReason.Safety && waitKind == WaitKind.Both) {
            bothProgress?.invoke() == true
        } else null
        if (progressed != null) requestRewarmOnTimeout(progressChecked = true)
        if (reason != HideReason.Ready) {
            panelSessionReady = false
            onSetEnded("curtain $reason")
        }
        DiagLog.d("SetRunner", "onCurtainHidden reason=$reason waitKind=$waitKind")
        val kind = waitKind
        val fallback = linkFallback
        val ready = linkReady.takeIf { linkWaitBoth }
        cancelLinkCallbacks()
        waitKind = WaitKind.None
        cancelTimers()
        openingForBoot = false
        val cancelledOpening = _opening.value
        _opening.value = null
        when (reason) {
            HideReason.Cancelled -> {
                val cancelledAtMs = d.clock.nowMs()
                DiagLog.d("SetRunner", "opening cancelled")
                pending = null
                currentIndex = SetNavigator.HOME
                d.lastIndex.lastSetIndex = SetNavigator.HOME
                lastOpenedPair = null
                lastOpenedAtMs = null
                lastReadyAtMs = null
                d.startHome()
                startCancelGuard(cancelledOpening, cancelledAtMs)
                return
            }
            HideReason.Ready, HideReason.Timed -> ready?.invoke()
            HideReason.Failed, HideReason.Safety -> fallback?.invoke()
        }
        if (monitoringAvailable && reason == HideReason.Safety && kind == WaitKind.Both) {
            if (progressed == true) {
                DiagLog.d("SetRunner", "timeout not counted: progress")
            } else {
                d.selector.onFailure(FailReason.Timeout, countable = d.watcher.available())
            }
            d.messenger.show(Notice.Slow)
        }
        bothProgress = null
        runPendingIfNeeded()
    }

    private fun finishWithoutCurtain() {
        waitKind = WaitKind.None
        cancelTimers()
        openingForBoot = false
        _opening.value = null
        runPendingIfNeeded()
    }

    private fun runPendingIfNeeded() {
        val p = pending
        pending = null
        if (p != null && p.index != currentIndex) {
            if (p.forBoot) openForBoot(p.index) else open(p.index)
        }
    }

    private fun cancelTimers() {
        operationGeneration++
        warmupTimer?.cancel()
        warmupTimer = null
        preparing = false
        vendorWaiting = false
        activeTimers.forEach { it.cancel() }
        activeTimers.clear()
    }

    private fun requestRewarmOnTimeout(progressChecked: Boolean = false) {
        if (!vendorWaiting || waitKind != WaitKind.Both || !monitoringAvailable || !d.watcher.available()) return
        if (!progressChecked) bothProgress?.invoke() ?: return
        if (d.watcher.available()) d.stockLauncherWarmup.requestRewarm()
    }

    fun targetFor(index: Int): CurtainTarget {
        return targetFor(index, configForOperation())
    }

    private fun targetFor(index: Int, cfg: LauncherConfig?): CurtainTarget {
        val sets = cfg?.screenSets.orEmpty()
        val count = sets.size + 1
        if (index == SetNavigator.HOME) {
            return CurtainTarget(
                setName = "ホーム",
                left = SlotPreview.App("", "ホーム"),
                right = SlotPreview.Panel,
                ratio = SplitRatio.R6_4,
                position = 0,
                count = count,
                cancellable = false,
            )
        }
        val set = sets.getOrNull(index - 1) ?: return CurtainTarget(
            setName = "ホーム",
            left = SlotPreview.App("", "ホーム"),
            right = SlotPreview.Panel,
            ratio = SplitRatio.R6_4,
            position = 0,
            count = count,
            cancellable = false,
        )
        val leftPreview = when (val l = set.slotAt(Side.Left)) {
            is Slot.App -> SlotPreview.App(l.pkg, d.labelFor(l.pkg))
            Slot.Panel -> SlotPreview.Panel
        }
        val rightPreview = when (val r = set.slotAt(Side.Right)) {
            is Slot.Panel -> SlotPreview.Panel
            is Slot.App -> SlotPreview.App(r.pkg, d.labelFor(r.pkg))
        }
        return CurtainTarget(
            setName = numberedSetLabel(index, set, d.labelFor),
            left = leftPreview,
            right = rightPreview,
            ratio = set.ratio,
            position = index,
            count = count,
        )
    }

    companion object {
        const val STOCK_LAUNCHER_PKG = "com.android.launcher3"
        const val POLL_MS = 200L
        const val RETRY_AT_MS = 3000L
        const val BOOT_CLEAR_MS = 10000L
        const val CANCEL_GUARD_MS = 5000L
    }
}
