package com.gt8d.launcher.sets

import com.gt8d.launcher.config.LauncherConfig
import com.gt8d.launcher.config.ScreenSet
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
import com.gt8d.launcher.time.Cancellable
import com.gt8d.launcher.time.Clock
import com.gt8d.launcher.time.Scheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Opening(val left: String, val right: String, val startedAtMs: Long)

interface LastIndexStore {
    var lastSetIndex: Int
}

interface Messenger {
    fun showSplitUnavailable()
    fun showSwitchFailed()
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
    val curtain: CurtainController,
    val scheduler: Scheduler,
    val clock: Clock,
    val ownPkg: String,
    val labelFor: (String) -> String,
    val startHome: () -> Unit,
    val messenger: Messenger,
)

class SetRunner(private val d: SetRunnerDeps) {
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
        private set

    var lastOpenedPair: Pair<String, String>? = null
        private set

    var lastOpenedAtMs: Long? = null
        private set

    private var linkFallback: (() -> Unit)? = null

    var readyCount: Int = 0
        private set

    private data class PendingOpen(val index: Int, val forBoot: Boolean)
    private var pending: PendingOpen? = null
    private var waitKind: WaitKind = WaitKind.None
    private var panelResumed: Boolean = false
    private var openingForBoot = false
    private var monitoringAvailable: Boolean = true
    private var expectedDelayMs: Long = WarmTracker.COLD_MS
    private val activeTimers = mutableListOf<Cancellable>()

    init {
        d.curtain.onHidden = ::onCurtainHidden
    }

    private fun configForOperation(): LauncherConfig? = d.config()

    fun next() {
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
        val cfg = configForOperation() ?: return
        val base = pending?.index ?: currentIndex
        val setCount = cfg.screenSets.size
        val target = SetNavigator.prev(base, setCount)
        DiagLog.d("SetRunner", "prev base=$base -> $target")
        open(target, cfg)
    }

    fun home() {
        val cfg = configForOperation() ?: return
        home(cfg)
    }

    private fun home(cfg: LauncherConfig) {
        DiagLog.d("SetRunner", "home")
        if (_opening.value != null) {
            pending = PendingOpen(SetNavigator.HOME, false)
            d.curtain.update(targetFor(SetNavigator.HOME, cfg))
            return
        }
        currentIndex = SetNavigator.HOME
        d.lastIndex.lastSetIndex = SetNavigator.HOME
        lastOpenedPair = null
        lastOpenedAtMs = null
        d.curtain.show(targetFor(SetNavigator.HOME, cfg), WarmTracker.WARM_MS, d.watcher.available())
        d.startHome()
        d.curtain.ready()
    }

    fun open(index: Int) {
        val cfg = configForOperation() ?: return
        open(index, cfg)
    }

    private fun open(index: Int, cfg: LauncherConfig) {
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
        val cfg = configForOperation() ?: return
        DiagLog.d("SetRunner", "openForBoot index=$index")
        if (_opening.value != null) {
            pending = PendingOpen(index, true)
            return
        }
        if (index == SetNavigator.HOME) {
            currentIndex = SetNavigator.HOME
            d.lastIndex.lastSetIndex = SetNavigator.HOME
            lastOpenedPair = null
            lastOpenedAtMs = null
            d.startHome()
            return
        }
        openInternal(index, cfg, useCurtain = false)
    }

    fun abortBootToHome() {
        if (_opening.value != null && !openingForBoot) return
        cancelTimers()
        pending = null
        linkFallback = null
        waitKind = WaitKind.None
        panelResumed = false
        openingForBoot = false
        currentIndex = SetNavigator.HOME
        d.lastIndex.lastSetIndex = SetNavigator.HOME
        lastOpenedPair = null
        lastOpenedAtMs = null
        _opening.value = null
        d.startHome()
    }

    fun panelResumedInSplit() {
        DiagLog.d("SetRunner", "panelResumedInSplit")
        panelResumed = true
    }

    fun openForLink(left: String, onNotSplit: () -> Unit): Boolean {
        val cfg = configForOperation() ?: return false
        if (_opening.value != null) return false
        linkFallback = onNotSplit
        openSet(
            ScreenSet("link", Slot.App(left), Slot.Panel, SplitRatio.R5_5),
            CurtainTarget(
                setName = "リンクを開く",
                left = SlotPreview.App(left, d.labelFor(left)),
                right = SlotPreview.Panel,
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
        linkFallback = null
        fallback?.invoke()
    }

    private fun openInternal(index: Int, cfg: LauncherConfig, useCurtain: Boolean) {
        linkFallback = null
        val set = cfg.screenSets.getOrNull(index - 1)
        if (set == null) {
            if (useCurtain) {
                home(cfg)
            } else {
                currentIndex = SetNavigator.HOME
                d.lastIndex.lastSetIndex = SetNavigator.HOME
                lastOpenedPair = null
                lastOpenedAtMs = null
                d.startHome()
            }
            return
        }

        currentIndex = index
        d.lastIndex.lastSetIndex = index

        openSet(set, targetFor(index, cfg), useCurtain)
    }

    private fun openSet(set: ScreenSet, target: CurtainTarget, useCurtain: Boolean) {
        val left = set.left.pkg
        val isPanelRight = set.right is Slot.Panel
        val right = when (val r = set.right) {
            is Slot.App -> r.pkg
            is Slot.Panel -> d.ownPkg
        }

        val startMs = d.clock.nowMs()
        d.watcher.foregroundPkgsSince(startMs)
        monitoringAvailable = d.watcher.available()
        expectedDelayMs = d.warm.expectedDelayMs(left, right)
        panelResumed = false
        openingForBoot = !useCurtain
        _opening.value = Opening(left, right, startMs)
        lastOpenedPair = left to right
        lastOpenedAtMs = startMs

        if (useCurtain) {
            d.curtain.show(target, expectedDelayMs, monitoringAvailable)
        }

        val installedPkgs = d.installed()
        if (left !in installedPkgs) {
            DiagLog.d("SetRunner", "left uninstalled: $left")
            if (!isPanelRight && right in installedPkgs) {
                d.warm.recordAppLaunch(right, d.apps.startApp(right, false))
                waitSingle(right, startMs, useCurtain)
            } else {
                d.startHome()
                waitSingle(d.ownPkg, startMs, useCurtain)
            }
            notifyLinkNotSplit()
            return
        }

        if (!isPanelRight && right !in installedPkgs) {
            DiagLog.d("SetRunner", "right uninstalled: $right")
            d.warm.recordAppLaunch(left, d.apps.startApp(left, false))
            waitSingle(left, startMs, useCurtain)
            notifyLinkNotSplit()
            return
        }

        val leftResizeable = d.apps.isResizeable(left)
        val rightResizeable = if (isPanelRight) true else d.apps.isResizeable(right)
        if (!leftResizeable || !rightResizeable) {
            DiagLog.d("SetRunner", "unresizeable left=$leftResizeable right=$rightResizeable")
            d.warm.recordAppLaunch(left, d.apps.startApp(left, false))
            d.messenger.showSplitUnavailable()
            waitSingle(left, startMs, useCurtain)
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
            DiagLog.d("SetRunner", "leftOnly mode=$mode")
            d.warm.recordAppLaunch(left, d.apps.startApp(left, false))
            d.messenger.showSplitUnavailable()
            waitSingle(left, startMs, useCurtain)
            notifyLinkNotSplit()
            return
        }

        val result = launcher.open(left, right, set.ratio)
        DiagLog.d("SetRunner", "open result=$result mode=$mode")

        when (result) {
            is LaunchResult.Failed -> {
                handleLaunchFailure(result.reason, left, useCurtain)
            }
            is LaunchResult.Started -> {
                waitBoth(
                    left = left,
                    right = right,
                    isPanelRight = isPanelRight,
                    ratio = set.ratio,
                    launcher = launcher,
                    startMs = startMs,
                    useCurtain = useCurtain,
                )
            }
        }
    }

    private fun handleLaunchFailure(reason: FailReason, left: String, useCurtain: Boolean) {
        waitKind = WaitKind.None
        cancelTimers()
        d.selector.onFailure(reason, countable = true)
        d.warm.recordAppLaunch(left, d.apps.startApp(left, false))
        d.messenger.showSplitUnavailable()
        notifyLinkNotSplit()
        if (useCurtain) {
            d.curtain.fail()
        } else {
            finishWithoutCurtain()
        }
    }

    private fun waitBoth(
        left: String,
        right: String,
        isPanelRight: Boolean,
        ratio: SplitRatio,
        launcher: SplitLauncher,
        startMs: Long,
        useCurtain: Boolean,
    ) {
        waitKind = WaitKind.Both

        fun isBothReady(): Boolean {
            if (!monitoringAvailable) return false
            val resumed = d.watcher.bothResumed(left, right.takeUnless { isPanelRight } ?: left, startMs)
            return if (isPanelRight) {
                panelResumed && resumed
            } else {
                resumed
            }
        }

        fun schedulePoll() {
            val timer = d.scheduler.schedule(POLL_MS) {
                if (_opening.value == null) return@schedule
                val ready = isBothReady()
                if (stopMonitoringIfUnavailable(startMs, useCurtain)) return@schedule
                if (ready) {
                    linkFallback = null
                    DiagLog.d("SetRunner", "waitBoth ready left=$left right=$right")
                    waitKind = WaitKind.None
                    cancelTimers()
                    readyCount++
                    d.selector.onSuccess()
                    d.warm.markForeground(left)
                    d.warm.markForeground(right)
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

        val retryTimer = d.scheduler.schedule(RETRY_AT_MS) {
            if (_opening.value == null || !monitoringAvailable) return@schedule
            val ready = isBothReady()
            if (stopMonitoringIfUnavailable(startMs, useCurtain)) return@schedule
            if (!ready) {
                DiagLog.d("SetRunner", "retry at $RETRY_AT_MS left=$left right=$right")
                val result = launcher.open(left, right, ratio)
                if (result is LaunchResult.Failed) {
                    handleLaunchFailure(result.reason, left, useCurtain)
                }
            }
        }
        activeTimers.add(retryTimer)

        if (!useCurtain) {
            val bootTimer = d.scheduler.schedule(BOOT_CLEAR_MS) {
                if (_opening.value != null) {
                    DiagLog.d("SetRunner", "boot clear at $BOOT_CLEAR_MS")
                    waitKind = WaitKind.None
                    cancelTimers()
                    finishWithoutCurtain()
                }
            }
            activeTimers.add(bootTimer)
        }
    }

    private fun waitSingle(pkg: String, startMs: Long, useCurtain: Boolean) {
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
        DiagLog.d("SetRunner", "onCurtainHidden reason=$reason waitKind=$waitKind")
        val kind = waitKind
        val fallback = linkFallback
        linkFallback = null
        waitKind = WaitKind.None
        cancelTimers()
        openingForBoot = false
        _opening.value = null
        if (!monitoringAvailable) {
            fallback?.invoke()
        } else if (reason == HideReason.Safety && kind == WaitKind.Both) {
            fallback?.invoke()
            d.selector.onFailure(FailReason.Timeout, countable = d.watcher.available())
            d.messenger.showSwitchFailed()
        }
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
        activeTimers.forEach { it.cancel() }
        activeTimers.clear()
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
            )
        }
        val set = sets.getOrNull(index - 1) ?: return CurtainTarget(
            setName = "ホーム",
            left = SlotPreview.App("", "ホーム"),
            right = SlotPreview.Panel,
            ratio = SplitRatio.R6_4,
            position = 0,
            count = count,
        )
        val leftPkg = set.left.pkg
        val leftPreview = SlotPreview.App(leftPkg, d.labelFor(leftPkg))
        val rightPreview = when (val r = set.right) {
            is Slot.Panel -> SlotPreview.Panel
            is Slot.App -> SlotPreview.App(r.pkg, d.labelFor(r.pkg))
        }
        return CurtainTarget(
            setName = "セット$index",
            left = leftPreview,
            right = rightPreview,
            ratio = set.ratio,
            position = index,
            count = count,
        )
    }

    companion object {
        const val POLL_MS = 200L
        const val RETRY_AT_MS = 3000L
        const val BOOT_CLEAR_MS = 10000L
    }
}
