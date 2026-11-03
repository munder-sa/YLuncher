package com.gt8d.launcher.media

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
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
    private val FAVORITE_ACTION_IDS: Set<String> = setOf(
        "com.apple.android.music.playback.action.FAVORITE",
    )

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    private var appContext: Context? = null
    private var sessionManager: MediaSessionManager? = null
    private var activeControllers: List<MediaController> = emptyList()
    private val controllerCallbacks = mutableMapOf<MediaController, MediaController.Callback>()
    private var selectedController: MediaController? = null
    private var lastActivePkg: String? = null
    private var listenerRegistered = false

    private val sessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            onControllersChanged(controllers.orEmpty())
        }

    fun start(context: Context) {
        stop()
        val ctx = context.applicationContext
        appContext = ctx
        val manager = ctx.getSystemService(MediaSessionManager::class.java)
        if (manager == null) {
            Log.w(TAG, "MediaSessionManager is unavailable")
            _nowPlaying.value = null
            return
        }
        sessionManager = manager
        val component = ComponentName(ctx, MediaListenerService::class.java)
        try {
            manager.addOnActiveSessionsChangedListener(sessionsChangedListener, component)
            listenerRegistered = true
            val initial = manager.getActiveSessions(component)
            onControllersChanged(initial)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start MediaWatcher", e)
            _nowPlaying.value = null
        }
    }

    fun stop() {
        clearControllers()
        if (listenerRegistered) {
            try {
                sessionManager?.removeOnActiveSessionsChangedListener(sessionsChangedListener)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remove session listener", e)
            }
            listenerRegistered = false
        }
        sessionManager = null
        selectedController = null
        _nowPlaying.value = null
    }

    // PiP から戻して分割を組み直す間、再生中のアプリが裏に回ったと判断して PiP に入り直すのを防ぐ(実機で確認)。
    fun pauseIfPlaying(pkg: String): Boolean {
        val controller = activeControllers.firstOrNull {
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
        val controller = activeControllers.firstOrNull { it.packageName == pkg } ?: return
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
                ?.firstOrNull { it.action in FAVORITE_ACTION_IDS }
                ?: return@runTransport
            controller.transportControls.sendCustomAction(customAction, customAction.extras)
        }
    }

    private inline fun runTransport(name: String, block: (MediaController) -> Unit) {
        val controller = selectedController ?: return
        try {
            block(controller)
        } catch (e: Exception) {
            Log.w(TAG, "MediaWatcher.$name failed", e)
        }
    }

    private fun onControllersChanged(controllers: List<MediaController>) {
        clearControllers()
        activeControllers = controllers
        for (controller in controllers) {
            val callback = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    refreshSnapshots()
                }

                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    refreshSnapshots()
                }

                override fun onSessionDestroyed() {
                    refreshSnapshots()
                }
            }
            try {
                controller.registerCallback(callback)
                controllerCallbacks[controller] = callback
            } catch (e: Exception) {
                Log.w(TAG, "Failed to register MediaController callback for ${controller.packageName}", e)
            }
        }
        refreshSnapshots()
    }

    private fun clearControllers() {
        for ((controller, callback) in controllerCallbacks) {
            try {
                controller.unregisterCallback(callback)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unregister MediaController callback", e)
            }
        }
        controllerCallbacks.clear()
        activeControllers = emptyList()
    }

    private fun refreshSnapshots() {
        try {
            val pairs = activeControllers.map { controller ->
                controller to buildSnapshot(controller)
            }
            val snapshots = pairs.map { it.second }
            val picked = pickSession(snapshots, lastActivePkg)
            if (picked == null) {
                selectedController = null
                _nowPlaying.value = null
                return
            }
            if (picked.playing) {
                lastActivePkg = picked.pkg
            }
            selectedController = pairs.firstOrNull { it.second === picked }?.first
            _nowPlaying.value = picked.toNowPlaying()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to refresh media snapshots", e)
            selectedController = null
            _nowPlaying.value = null
        }
    }

    private fun buildSnapshot(controller: MediaController): SessionSnapshot {
        val pkg = controller.packageName
        val appLabel = resolveAppLabel(pkg)
        val metadata = controller.metadata
        val state = controller.playbackState

        val title = metadata?.getText(MediaMetadata.METADATA_KEY_TITLE)?.toString()
        val artist = metadata?.getText(MediaMetadata.METADATA_KEY_ARTIST)?.toString()
        val artwork = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        val durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val positionMs = state?.position ?: 0L
        val positionUpdatedAt = state?.lastPositionUpdateTime ?: 0L
        val speed = state?.playbackSpeed ?: 1f
        val playing = state?.state == PlaybackState.STATE_PLAYING
        val hasFavoriteAction =
            state?.customActions?.any { it.action in FAVORITE_ACTION_IDS } == true

        return SessionSnapshot(
            pkg = pkg,
            appLabel = appLabel,
            title = title,
            artist = artist,
            artwork = artwork,
            durationMs = durationMs,
            positionMs = positionMs,
            positionUpdatedAt = positionUpdatedAt,
            speed = speed,
            playing = playing,
            hasFavoriteAction = hasFavoriteAction,
            paused = state?.state == PlaybackState.STATE_PAUSED,
            album = metadata?.getText(MediaMetadata.METADATA_KEY_ALBUM)?.toString(),
        )
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
