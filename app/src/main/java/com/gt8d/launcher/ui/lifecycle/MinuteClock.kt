package com.gt8d.launcher.ui.lifecycle

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.isActive
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

@Composable
internal fun rememberMinuteTime(now: () -> LocalDateTime = { LocalDateTime.now() }): State<LocalDateTime> {
    // The initial display read is separate from the one synchronization per START.
    // In particular, changing clocks while stopped must not read either clock.
    val time = remember { mutableStateOf(now().truncatedTo(ChronoUnit.MINUTES)) }
    val currentClock = rememberUpdatedState(now)
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val session = remember(context, lifecycle, now) { object { var disposed = false } }
    DisposableEffect(session) {
        onDispose { session.disposed = true }
    }
    VisibleUiEffect(session) {
        fun refresh() {
            // A cancelled START interval stays invalid even after the next START.
            if (isActive && !session.disposed && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                time.value = currentClock.value().truncatedTo(ChronoUnit.MINUTES)
            }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_TIME_TICK, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> refresh()
                }
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }, ContextCompat.RECEIVER_EXPORTED)
        try {
            refresh()
            awaitCancellation()
        } finally {
            context.unregisterReceiver(receiver)
        }
    }
    return time
}
