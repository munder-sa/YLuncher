package com.gt8d.launcher.sets

import com.gt8d.launcher.config.LauncherConfig
import com.gt8d.launcher.config.ScreenSet
import com.gt8d.launcher.config.Side
import com.gt8d.launcher.config.panelSide
import com.gt8d.launcher.config.swapped
import com.gt8d.launcher.diag.DiagLog
import com.gt8d.launcher.split.PanelPlacement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PanelSwapSaver(
    private val scope: CoroutineScope,
    private val latestConfig: () -> LauncherConfig?,
    private val updateConfig: suspend ((LauncherConfig) -> LauncherConfig) -> Unit,
    private val onSaved: (ScreenSet) -> Unit,
    private val settleMs: Long = 500,
) {
    private data class Session(var snapshot: ScreenSet, val expectedPartner: String, var partner: String)
    @Volatile private var session: Session? = null
    @Volatile private var generation = 0L
    @Volatile private var placement: PanelPlacement? = null
    @Volatile private var settled = false
    private var waiting: Job? = null
    private val mutex = Mutex()

    fun begin(set: ScreenSet, partner: String) {
        end("begin")
        if (set.panelSide == null) return
        session = Session(set, partner, partner)
        schedule()
    }

    fun end(reason: String) {
        generation++
        session = null
        waiting?.cancel()
        waiting = null
        settled = false
    }

    fun partnerChanged(pkg: String) {
        val active = session ?: return
        if (active.partner == pkg) return
        generation++
        active.partner = pkg
        schedule()
    }

    fun placementChanged(p: PanelPlacement?) {
        if (p == PanelPlacement.Full) {
            placement = p
            end("full")
            return
        }
        if (placement == p) return
        placement = p
        schedule()
    }

    private fun schedule() {
        waiting?.cancel()
        settled = false
        if (session == null || placement == null || placement == PanelPlacement.Full) return
        val token = generation
        waiting = scope.launch {
            delay(settleMs)
            if (token != generation) return@launch
            settled = true
            // 保存の coroutine は debounce の取り消しから独立させる。
            scope.launch { mutex.withLock { converge() } }
        }
    }

    private suspend fun converge() {
        while (settled) {
            currentCoroutineContext().ensureActive()
            val active = session ?: return
            val token = generation
            val snapshot = active.snapshot
            val side = when (placement) {
                PanelPlacement.Left -> Side.Left
                PanelPlacement.Right -> Side.Right
                else -> return
            }
            if (side == snapshot.panelSide) return
            if (active.partner != active.expectedPartner) {
                DiagLog.d("SetRunner", "swapSkipped reason=partner")
                return
            }
            val swapped = snapshot.swapped()
            var saved = false
            try {
                val context = currentCoroutineContext()
                updateConfig { cfg ->
                    context.ensureActive()
                    if (token != generation) cfg
                    else if (cfg.screenSets.find { it.id == snapshot.id } != snapshot) {
                        DiagLog.d("SetRunner", "swapSkipped reason=edited")
                        end("edited")
                        cfg
                    } else {
                        saved = true
                        cfg.copy(screenSets = cfg.screenSets.map { if (it.id == snapshot.id) swapped else it })
                    }
                }
                currentCoroutineContext().ensureActive()
                if (token != generation || !saved) return
                active.snapshot = swapped
                DiagLog.d("SetRunner", "swapSaved id=${swapped.id} panel=${swapped.panelSide}")
                onSaved(swapped)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DiagLog.d("SetRunner", "swapSkipped reason=error")
                return
            }
        }
    }
}
