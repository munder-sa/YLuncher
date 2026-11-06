package com.gt8d.launcher.foreground

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun interface ForegroundPoll {
    // Called on main with an already prepared read; completion must run on main.
    fun launch(read: () -> ForegroundWatcher.ReadResult, onComplete: (ForegroundWatcher.ReadResult) -> Unit)

    companion object {
        // Compatibility for existing scheduler-driven tests. AppGraph explicitly
        // supplies AsyncForegroundRead with the application scope in production.
        val Immediate = ForegroundPoll { read, onComplete -> onComplete(read()) }
    }
}

class AsyncForegroundRead(
    private val scope: CoroutineScope,
    private val worker: CoroutineDispatcher = Dispatchers.IO,
    private val main: CoroutineDispatcher = Dispatchers.Main.immediate,
) : ForegroundPoll {
    override fun launch(read: () -> ForegroundWatcher.ReadResult, onComplete: (ForegroundWatcher.ReadResult) -> Unit) {
        scope.launch(main) {
            val result = withContext(worker) { read() }
            onComplete(result)
        }
    }
}
