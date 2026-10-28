package com.gt8d.launcher.time

class FakeScheduler : Scheduler, Clock {
    private data class ScheduledTask(
        val id: Long,
        val runAtMs: Long,
        val action: () -> Unit,
        var cancelled: Boolean = false,
    )

    private var currentMs: Long = 0L
    private var nextId: Long = 0L
    private val tasks = mutableListOf<ScheduledTask>()

    override fun nowMs(): Long = currentMs

    override fun schedule(delayMs: Long, action: () -> Unit): Cancellable {
        val runAt = currentMs + delayMs.coerceAtLeast(0L)
        val task = ScheduledTask(
            id = nextId++,
            runAtMs = runAt,
            action = action,
        )
        tasks.add(task)
        return Cancellable {
            task.cancelled = true
        }
    }

    fun advanceBy(ms: Long) {
        val targetMs = currentMs + ms.coerceAtLeast(0L)
        while (true) {
            tasks.removeAll { it.cancelled }
            val next = tasks
                .filter { !it.cancelled && it.runAtMs <= targetMs }
                .minWithOrNull(compareBy<ScheduledTask> { it.runAtMs }.thenBy { it.id })
                ?: break
            tasks.remove(next)
            currentMs = next.runAtMs
            if (!next.cancelled) {
                next.action()
            }
        }
        currentMs = targetMs
    }
}
