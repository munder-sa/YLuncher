package com.gt8d.launcher.spike

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.gt8d.launcher.media.MediaListenerService

object SpikeActions {
    private const val TAG = "GT8D-SPIKE"
    private val mainHandler = Handler(Looper.getMainLooper())

    fun run(ctx: Context, step: String, extras: Bundle?) {
        val appCtx = ctx.applicationContext
        Log.i(TAG, "run step=$step")
        when (step) {
            "split" -> runSplit(appCtx, extras)
            "home" -> runHome(appCtx)
            "media_dump" -> runMediaDump(appCtx)
            "overlay_on" -> SpikeOverlay.show(appCtx)
            "overlay_off" -> SpikeOverlay.hide(appCtx)
            else -> Log.w(TAG, "unknown step: $step")
        }
    }

    private fun runSplit(ctx: Context, extras: Bundle?) {
        val left = extras?.getString("left").orEmpty()
        val right = extras?.getString("right").orEmpty()
        val delay = readDelayMs(extras)
        val homeFirst = readBoolean(extras, "home_first", false)

        if (left.isBlank() || right.isBlank()) {
            Log.w(TAG, "FAIL split missing args left='$left' right='$right'")
            return
        }

        val startSplitSequence = Runnable {
            val leftIntent = ctx.packageManager.getLaunchIntentForPackage(left)
            if (leftIntent == null) {
                Log.w(TAG, "FAIL not-installed $left")
                return@Runnable
            }
            leftIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startSafe(ctx, "left:$left", leftIntent)

            mainHandler.postDelayed({
                val rightFlags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or
                    Intent.FLAG_ACTIVITY_MULTIPLE_TASK

                if (right == "PANEL") {
                    val panelIntent = Intent(ctx, SpikePanelActivity::class.java).apply {
                        addFlags(rightFlags)
                    }
                    startSafe(ctx, "right:PANEL", panelIntent)
                } else {
                    val rightIntent = ctx.packageManager.getLaunchIntentForPackage(right)
                    if (rightIntent == null) {
                        Log.w(TAG, "FAIL not-installed $right")
                        return@postDelayed
                    }
                    rightIntent.addFlags(rightFlags)
                    startSafe(ctx, "right:$right", rightIntent)
                }
            }, delay)
        }

        if (homeFirst) {
            runHome(ctx)
            mainHandler.postDelayed(startSplitSequence, 500L)
        } else {
            startSplitSequence.run()
        }
    }

    private fun runHome(ctx: Context) {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startSafe(ctx, "HOME", homeIntent)
    }

    private fun runMediaDump(ctx: Context) {
        val manager = ctx.getSystemService(MediaSessionManager::class.java)
        if (manager == null) {
            Log.w(TAG, "FAIL media_dump MediaSessionManager is null")
            return
        }
        val component = ComponentName(ctx, MediaListenerService::class.java)
        val controllers = try {
            manager.getActiveSessions(component)
        } catch (e: SecurityException) {
            Log.w(TAG, "FAIL media_dump ${e.javaClass.simpleName}: ${e.message}")
            return
        } catch (e: Exception) {
            Log.w(TAG, "FAIL media_dump ${e.javaClass.simpleName}: ${e.message}")
            return
        }

        if (controllers.isEmpty()) {
            Log.i(TAG, "media_dump: sessions=0")
            return
        }

        Log.i(TAG, "media_dump: sessions=${controllers.size}")
        for ((idx, controller) in controllers.withIndex()) {
            val pkg = controller.packageName
            val state = controller.playbackState
            val meta = controller.metadata

            val stateCode = state?.state
            val actionsHex = state?.actions?.let { "0x${java.lang.Long.toHexString(it)}" } ?: "null"
            val customActionsStr = state?.customActions?.joinToString(prefix = "[", postfix = "]") { ca ->
                "${ca.action}(${ca.name})"
            } ?: "[]"
            val pos = state?.position
            val lastUpdate = state?.lastPositionUpdateTime
            val speed = state?.playbackSpeed

            val title = meta?.getText(MediaMetadata.METADATA_KEY_TITLE)?.toString()
            val artist = meta?.getText(MediaMetadata.METADATA_KEY_ARTIST)?.toString()
            val duration = if (meta != null && meta.containsKey(MediaMetadata.METADATA_KEY_DURATION)) {
                meta.getLong(MediaMetadata.METADATA_KEY_DURATION)
            } else {
                null
            }
            val hasArt = (meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ART)) != null

            Log.i(
                TAG,
                "media_dump[$idx] pkg=$pkg state=$stateCode actions=$actionsHex customActions=$customActionsStr " +
                    "title=$title artist=$artist duration=$duration hasArt=$hasArt " +
                    "position=$pos lastPositionUpdateTime=$lastUpdate playbackSpeed=$speed",
            )
        }
    }

    fun startSafe(ctx: Context, target: String, intent: Intent) {
        val appCtx = ctx.applicationContext
        val flagsHex = "0x${Integer.toHexString(intent.flags)}"
        try {
            appCtx.startActivity(intent)
            Log.i(TAG, "START $target flags=$flagsHex -> ok")
        } catch (e: Exception) {
            Log.w(TAG, "START $target flags=$flagsHex -> FAIL ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun readDelayMs(extras: Bundle?): Long {
        if (extras == null || !extras.containsKey("delay")) return 300L
        return when (val v = extras.get("delay")) {
            is Number -> v.toLong()
            is String -> v.toLongOrNull() ?: 300L
            else -> 300L
        }
    }

    private fun readBoolean(extras: Bundle?, key: String, default: Boolean): Boolean {
        if (extras == null || !extras.containsKey(key)) return default
        return when (val v = extras.get(key)) {
            is Boolean -> v
            is String -> v.toBoolean()
            else -> default
        }
    }
}
