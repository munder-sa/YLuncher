package com.gt8d.launcher.apps

import android.os.SystemClock
import com.gt8d.launcher.diag.DiagLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** 呼出時だけ更新する。publish はこの所有者のロック内から呼び、close 後には呼ばない。 */
internal class FrequentAppsRefresher(
    private val available: suspend () -> Boolean,
    private val launchesSince: suspend (Long) -> List<UsageLaunch>?,
    private val eligible: suspend () -> Set<String>,
    private val publish: (List<FrequentApp>?, Boolean) -> Unit,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
    private val wallTime: () -> Long = System::currentTimeMillis,
    private val log: (Int?, List<FrequentApp>?) -> Unit = { count, ranked ->
        DiagLog.d("Frequent", "events=$count top=$ranked")
    },
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var closed = false
    private var generation = 0L
    private var requestSequence = 0L
    private var checkedSequence = 0L
    private var permission: Boolean? = null
    private var successAt: Long? = null
    private var failureAt: Long? = null
    private var query: Job? = null

    @Synchronized fun refresh() {
        if (closed) return
        val request = ++requestSequence
        scope.launch {
            val granted = try { available()
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { false }
            currentCoroutineContext().ensureActive()
            synchronized(this@FrequentAppsRefresher) {
                // 遅い権限確認が、より新しい確認結果を巻き戻さない。
                if (closed || request < checkedSequence) return@launch
                checkedSequence = request
                if (permission != granted) {
                    generation++
                    query?.cancel()
                    query = null
                    successAt = null
                    failureAt = null
                    permission = granted
                    publish(null, granted)
                }
                if (!granted || query != null) return@launch
                val now = elapsedRealtime()
                if (successAt?.let { now - it < SUCCESS_TTL_MS } == true) return@launch
                if (failureAt?.let { now - it < FAILURE_RETRY_MS } == true) return@launch
                startQuery()
            }
        }
    }

    // 呼出元は所有者のロックを保持する。登録してから開始し、即時実行でも重複を防ぐ。
    private fun startQuery() {
        val token = generation
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var count: Int? = null
            val ranked = try {
                val launches = launchesSince(wallTime() - HISTORY_MS)
                currentCoroutineContext().ensureActive()
                count = launches?.size
                launches?.let { rankFrequent(it, eligible()) }
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { null }
            val context = currentCoroutineContext()
            context.ensureActive()
            synchronized(this@FrequentAppsRefresher) {
                context.ensureActive()
                if (!closed && generation == token) {
                    if (ranked == null) {
                        failed()
                    } else {
                        successAt = elapsedRealtime()
                        failureAt = null
                        publish(ranked, true)
                    }
                }
            }
            log(count, ranked)
        }
        query = job
        job.invokeOnCompletion { cause ->
            synchronized(this@FrequentAppsRefresher) {
                if (!closed && generation == token && query === job) {
                    query = null
                    // キャンセルは成功にせず伝播させる。同世代の再照会も完了から5秒待つ。
                    if (cause is CancellationException) failed()
                }
            }
        }
        job.start()
    }

    private fun failed() {
        successAt = null
        failureAt = elapsedRealtime()
        publish(null, permission == true)
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        generation++
        query = null
        scope.cancel()
    }

    private companion object {
        const val SUCCESS_TTL_MS = 60_000L
        const val FAILURE_RETRY_MS = 5_000L
        const val HISTORY_MS = 7L * 24 * 60 * 60 * 1000
    }
}
