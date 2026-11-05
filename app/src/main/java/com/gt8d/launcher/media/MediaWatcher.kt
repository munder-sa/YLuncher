package com.gt8d.launcher.media

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object MediaWatcher {
    private const val TAG = "GT8D"
    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    private var appContext: Context? = null
    private var sessionManager: MediaSessionManager? = null
    private var sessionsChangedListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private var labelReceiver: BroadcastReceiver? = null
    private val cache = MediaSessionCache(object : MediaSessionCache.Access<MediaController> {
        override fun packageName(controller: MediaController) = controller.packageName
        override fun metadata(controller: MediaController) = controller.metadata
        override fun playbackState(controller: MediaController) = controller.playbackState
        override fun appLabel(pkg: String) = resolveAppLabel(pkg)
        override fun register(controller: MediaController, callback: MediaController.Callback) =
            controller.registerCallback(callback)
        override fun unregister(controller: MediaController, callback: MediaController.Callback) =
            controller.unregisterCallback(callback)
    }) { _nowPlaying.value = it }

    fun start(context: Context) {
        stop()
        val ctx = context.applicationContext
        appContext = ctx
        val manager = ctx.getSystemService(MediaSessionManager::class.java)
        if (manager == null) {
            Log.w(TAG, "MediaSessionManager is unavailable")
            appContext = null
            return
        }
        sessionManager = manager
        val component = ComponentName(ctx, MediaListenerService::class.java)
        val listener = object : MediaSessionManager.OnActiveSessionsChangedListener {
            override fun onActiveSessionsChanged(controllers: MutableList<MediaController>?) {
                if (sessionsChangedListener !== this) return
                cache.replaceControllers(controllers.orEmpty())
            }
        }
        sessionsChangedListener = listener
        try {
            registerLabelReceiver(ctx)
            manager.addOnActiveSessionsChangedListener(listener, component)
            val initial = manager.getActiveSessions(component)
            cache.replaceControllers(initial)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start MediaWatcher", e)
            stop()
        }
    }

    fun stop() {
        val listener = sessionsChangedListener
        sessionsChangedListener = null
        val receiver = labelReceiver
        labelReceiver = null
        cache.clear()
        if (listener != null) {
            try {
                sessionManager?.removeOnActiveSessionsChangedListener(listener)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remove session listener", e)
            }
        }
        if (receiver != null) {
            try {
                appContext?.unregisterReceiver(receiver)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remove media label receiver", e)
            }
        }
        sessionManager = null
        appContext = null
    }

    // PiP から戻して分割を組み直す間、再生中のアプリが裏に回ったと判断して PiP に入り直すのを防ぐ(実機で確認)。
    fun pauseIfPlaying(pkg: String): Boolean {
        val controller = cache.controllers.firstOrNull {
            it.packageName == pkg && it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: return false
        return try {
            controller.transportControls.pause()
            true
        } catch (e: Exception) {
            Log.w(TAG, "MediaWatcher.pauseIfPlaying failed", e)
            false
        }
    }

    fun playPackage(pkg: String) {
        val controller = cache.controllers.firstOrNull { it.packageName == pkg } ?: return
        try {
            controller.transportControls.play()
        } catch (e: Exception) {
            Log.w(TAG, "MediaWatcher.playPackage failed", e)
        }
    }

    fun play() {
        runTransport("play") { it.transportControls.play() }
    }

    fun pause() {
        runTransport("pause") { it.transportControls.pause() }
    }

    fun next() {
        runTransport("next") { it.transportControls.skipToNext() }
    }

    fun previous() {
        runTransport("previous") { it.transportControls.skipToPrevious() }
    }

    fun seekTo(ms: Long) {
        runTransport("seekTo") { it.transportControls.seekTo(ms) }
    }

    fun favorite() {
        runTransport("favorite") { controller ->
            val customAction = controller.playbackState?.customActions
                ?.firstOrNull { it.action in MediaSessionCache.FAVORITE_ACTION_IDS }
                ?: return@runTransport
            controller.transportControls.sendCustomAction(customAction, customAction.extras)
        }
    }

    private inline fun runTransport(name: String, block: (MediaController) -> Unit) {
        val controller = cache.selectedController ?: return
        try {
            block(controller)
        } catch (e: Exception) {
            Log.w(TAG, "MediaWatcher.$name failed", e)
        }
    }

    private fun registerLabelReceiver(ctx: Context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (labelReceiver !== this) return
                when (intent.action) {
                    Intent.ACTION_LOCALE_CHANGED, Intent.ACTION_CONFIGURATION_CHANGED -> cache.invalidateLabels()
                    Intent.ACTION_PACKAGE_ADDED, Intent.ACTION_PACKAGE_CHANGED,
                    Intent.ACTION_PACKAGE_REMOVED, Intent.ACTION_PACKAGE_REPLACED -> {
                        intent.data?.schemeSpecificPart?.let { cache.invalidateLabels(it) }
                    }
                }
            }
        }
        ctx.registerReceiver(receiver, IntentFilter().apply {
            addAction(Intent.ACTION_LOCALE_CHANGED)
            addAction(Intent.ACTION_CONFIGURATION_CHANGED)
        }, Context.RECEIVER_EXPORTED)
        labelReceiver = receiver
        ctx.registerReceiver(receiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }, Context.RECEIVER_EXPORTED)
    }

    private fun resolveAppLabel(pkg: String): String {
        val pm = appContext?.packageManager ?: return pkg
        return try {
            val info = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(info).toString().ifBlank { pkg }
        } catch (_: Exception) {
            pkg
        }
    }
}

class MediaListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        MediaWatcher.start(applicationContext)
    }

    override fun onListenerDisconnected() {
        MediaWatcher.stop()
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // 通知の内容は読まない・保存しない
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // 通知の内容は読まない・保存しない
    }
}
