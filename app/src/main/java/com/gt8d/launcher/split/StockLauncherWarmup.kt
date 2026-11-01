package com.gt8d.launcher.split

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.Settings
import com.gt8d.launcher.diag.DiagLog
import com.gt8d.launcher.foreground.ForegroundWatcher
import com.gt8d.launcher.time.Cancellable
import com.gt8d.launcher.time.Clock
import com.gt8d.launcher.time.Scheduler
import kotlin.math.roundToLong

interface StockLauncherWarmupState {
    val bootId: String
    var warmedBootId: String?
    var retryWarmupBootId: String?

    fun recordWarmed() {
        warmedBootId = bootId
        retryWarmupBootId = null
    }
}

class AndroidStockLauncherWarmupState(context: Context) : StockLauncherWarmupState {
    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences("stock_launcher_warmup", Context.MODE_PRIVATE)
    override val bootId = identifyBoot(
        { Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT, -1) },
        { System.currentTimeMillis() },
        { SystemClock.elapsedRealtime() },
    )
    override var warmedBootId: String? = preferences.getString("warmed_boot_id", null)
        set(value) { field = value; save() }
    override var retryWarmupBootId: String? = preferences.getString("retry_warmup_boot_id", null)
        set(value) { field = value; save() }

    override fun recordWarmed() {
        // Update both in memory before a single durable write.
        saveTogether = true
        warmedBootId = bootId
        retryWarmupBootId = null
        saveTogether = false
        save()
    }

    private var saveTogether = false
    private fun save() {
        if (saveTogether) return
        try {
            val saved = preferences.edit()
                .putString("warmed_boot_id", warmedBootId)
                .putString("retry_warmup_boot_id", retryWarmupBootId).commit()
            if (!saved) DiagLog.d("StockWarmup", "persistence commit failed")
        } catch (e: Exception) {
            DiagLog.d("StockWarmup", "persistence failed: $e")
        }
    }

    companion object {
        internal fun identifyBoot(count: () -> Int, wall: () -> Long, elapsed: () -> Long): String {
            val value = try { count() } catch (_: Exception) { -1 }
            return if (value >= 0) "count:$value"
            else "time:${((wall() - elapsed()) / 1000.0).roundToLong()}"
        }
    }
}

class StockLauncherWarmup(
    private val clock: Clock,
    private val scheduler: Scheduler,
    private val watcher: ForegroundWatcher,
    private val state: StockLauncherWarmupState,
    private val startStockLauncher: () -> Boolean,
) {
    companion object {
        const val POLL_MS = 100L
        const val SETTLE_MS = 300L
        const val LIMIT_MS = 1500L
        private const val STOCK_PKG = "com.android.launcher3"
    }

    private var generation = 0L
    private var active: Cancellable? = null

    fun needsWarmup() = state.warmedBootId != state.bootId || state.retryWarmupBootId == state.bootId

    fun requestRewarm() {
        state.retryWarmupBootId = state.bootId
        DiagLog.d("StockWarmup", "rewarm requested boot=${state.bootId}")
    }

    fun start(coverDelayMs: Long, onFinished: () -> Unit): Cancellable {
        active?.cancel()
        val id = ++generation
        val startedAt = clock.nowMs()
        val deadline = startedAt + LIMIT_MS
        val timers = mutableListOf<Cancellable>()
        var done = false
        var launched = false
        var reason = "timeout"
        fun valid() = !done && generation == id
        fun clear() { timers.forEach { it.cancel() }; timers.clear() }
        fun finish() {
            if (!valid()) return
            done = true
            clear()
            active = null
            if (launched) state.recordWarmed()
            DiagLog.d("StockWarmup", "finished reason=$reason elapsed=${clock.nowMs() - startedAt}")
            onFinished()
        }
        val cancellation = Cancellable {
            if (valid()) {
                done = true
                generation++
                clear()
                active = null
                DiagLog.d("StockWarmup", "cancel elapsed=${clock.nowMs() - startedAt}")
            }
        }
        active = cancellation
        DiagLog.d("StockWarmup", "start cover=$coverDelayMs boot=${state.bootId}")
        timers.add(scheduler.schedule(LIMIT_MS) { finish() })
        timers.add(scheduler.schedule(coverDelayMs) {
            if (!valid()) return@schedule
            val launchAttemptMs = clock.nowMs()
            launched = startStockLauncher()
            DiagLog.d("StockWarmup", "launch result=$launched at=$launchAttemptMs")
            if (!valid()) return@schedule
            if (!launched) { reason = "startFailed"; return@schedule }
            if (!watcher.available()) { reason = "unavailable"; return@schedule }
            fun poll() {
                timers.add(scheduler.schedule(POLL_MS) {
                    if (!valid()) return@schedule
                    if (!watcher.available()) { reason = "unavailable"; return@schedule }
                    val seen = watcher.foregroundPkgsSince(launchAttemptMs)
                    if (!watcher.available()) { reason = "unavailable"; return@schedule }
                    if (STOCK_PKG in seen) {
                        DiagLog.d("StockWarmup", "resume observed at=${clock.nowMs()}")
                        timers.add(scheduler.schedule(minOf(SETTLE_MS, (deadline - clock.nowMs()).coerceAtLeast(0))) {
                            if (clock.nowMs() < deadline) reason = "resumed"
                            finish()
                        })
                    } else poll()
                })
            }
            poll()
        })
        return cancellation
    }
}
