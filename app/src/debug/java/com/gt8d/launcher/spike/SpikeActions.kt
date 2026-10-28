package com.gt8d.launcher.spike

import android.accessibilityservice.AccessibilityService
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
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
            "toggle_split" -> runToggleSplit()
            "info" -> runInfo(appCtx)
            "freeform" -> runFreeform(appCtx, extras)
            "freeform_hidden" -> runFreeformHidden(appCtx, extras)
            "home_dump" -> runHomeDump()
            "click_label" -> runClickLabel(extras)
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

    private fun runToggleSplit() {
        val service = SpikeAccessibilityService.instance
        if (service == null) {
            Log.w(TAG, "FAIL toggle_split service-not-enabled")
            return
        }
        val result = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
        Log.i(TAG, "toggle_split result=$result")
    }

    private fun runInfo(ctx: Context) {
        val contentResolver = ctx.contentResolver
        val packageManager = ctx.packageManager
        val wm = ctx.getSystemService(WindowManager::class.java)
        Log.i(TAG, "info enable_freeform_support=${Settings.Global.getInt(contentResolver, "enable_freeform_support", 0)}")
        Log.i(TAG, "info force_resizable_activities=${Settings.Global.getInt(contentResolver, "force_resizable_activities", 0)}")
        Log.i(TAG, "info FEATURE_FREEFORM_WINDOW_MANAGEMENT=${packageManager.hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT)}")
        Log.i(TAG, "info FEATURE_PICTURE_IN_PICTURE=${packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)}")
        Log.i(TAG, "info currentWindowMetrics.bounds=${wm.currentWindowMetrics.bounds}")
    }

    private fun runFreeform(ctx: Context, extras: Bundle?) {
        val left = extras?.getString("left").orEmpty()
        val right = extras?.getString("right").orEmpty()
        val ratio = readInt(extras, "ratio", 50)
        val delay = readDelayMs(extras)

        if (left.isBlank() || right.isBlank()) {
            Log.w(TAG, "FAIL freeform missing args left='$left' right='$right'")
            return
        }

        val bounds = ctx.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        val w = bounds.width()
        val h = bounds.height()
        val splitX = w * ratio / 100
        val leftRect = Rect(0, 0, splitX, h)
        val rightRect = Rect(splitX, 0, w, h)

        val leftIntent = ctx.packageManager.getLaunchIntentForPackage(left)
        if (leftIntent == null) {
            Log.w(TAG, "FAIL not-installed $left")
            return
        }
        leftIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val leftOptions = ActivityOptions.makeBasic().setLaunchBounds(leftRect)
        startSafe(ctx, "left:$left", leftIntent, leftOptions.toBundle())

        mainHandler.postDelayed({
            val rightIntent = ctx.packageManager.getLaunchIntentForPackage(right)
            if (rightIntent == null) {
                Log.w(TAG, "FAIL not-installed $right")
                return@postDelayed
            }
            rightIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            val rightOptions = ActivityOptions.makeBasic().setLaunchBounds(rightRect)
            startSafe(ctx, "right:$right", rightIntent, rightOptions.toBundle())

            mainHandler.postDelayed({
                Log.i(TAG, "freeform bounds requested left=$leftRect right=$rightRect")
            }, 1000L)
        }, delay)
    }

    private fun runFreeformHidden(ctx: Context, extras: Bundle?) {
        val left = extras?.getString("left").orEmpty()
        val right = extras?.getString("right").orEmpty()
        val ratio = readInt(extras, "ratio", 50)
        val delay = readDelayMs(extras)
        val mode = readInt(extras, "mode", 5)

        val contentResolver = ctx.contentResolver
        Log.i(TAG, "hidden_api_policy=${Settings.Global.getString(contentResolver, "hidden_api_policy")}")

        if (left.isBlank() || right.isBlank()) {
            Log.w(TAG, "FAIL freeform_hidden missing args left='$left' right='$right'")
            return
        }

        val bounds = ctx.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        val w = bounds.width()
        val h = bounds.height()
        val splitX = w * ratio / 100
        val leftRect = Rect(0, 0, splitX, h)
        val rightRect = Rect(splitX, 0, w, h)

        val leftIntent = ctx.packageManager.getLaunchIntentForPackage(left)
        if (leftIntent == null) {
            Log.w(TAG, "FAIL not-installed $left")
            return
        }
        leftIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val leftOptions = makeHiddenFreeformOptions(leftRect, mode)
        startSafe(ctx, "left:$left", leftIntent, leftOptions.toBundle())

        mainHandler.postDelayed({
            val rightIntent = ctx.packageManager.getLaunchIntentForPackage(right)
            if (rightIntent == null) {
                Log.w(TAG, "FAIL not-installed $right")
                return@postDelayed
            }
            rightIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            val rightOptions = makeHiddenFreeformOptions(rightRect, mode)
            startSafe(ctx, "right:$right", rightIntent, rightOptions.toBundle())

            mainHandler.postDelayed({
                Log.i(TAG, "freeform_hidden bounds requested left=$leftRect right=$rightRect mode=$mode")
            }, 1000L)
        }, delay)
    }

    private fun makeHiddenFreeformOptions(rect: Rect, mode: Int): ActivityOptions {
        val options = ActivityOptions.makeBasic().setLaunchBounds(rect)
        try {
            ActivityOptions::class.java
                .getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                .invoke(options, mode)
            Log.i(TAG, "reflect setLaunchWindowingMode($mode) -> ok")
        } catch (e: Throwable) {
            Log.w(TAG, "reflect setLaunchWindowingMode($mode) -> FAIL ${e.javaClass.simpleName}: ${e.message}")
        }
        return options
    }

    private fun runHomeDump() {
        val service = SpikeAccessibilityService.instance
        if (service == null) {
            Log.w(TAG, "FAIL home_dump service-not-enabled")
            return
        }
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        mainHandler.postDelayed({
            val s = SpikeAccessibilityService.instance
            if (s == null) {
                Log.w(TAG, "FAIL home_dump service-not-enabled")
                return@postDelayed
            }
            val root = s.rootInActiveWindow
            if (root == null) {
                Log.w(TAG, "FAIL home_dump root-null")
            } else {
                var count = 0
                fun dfs(node: AccessibilityNodeInfo?) {
                    if (node == null || count >= 300) return
                    val text = node.text?.toString().orEmpty()
                    val desc = node.contentDescription?.toString().orEmpty()
                    if (text.isNotEmpty() || desc.isNotEmpty()) {
                        val bounds = Rect()
                        node.getBoundsInScreen(bounds)
                        Log.i(
                            TAG,
                            "node text=$text desc=$desc cls=${node.className} clickable=${node.isClickable} bounds=$bounds",
                        )
                        count++
                    }
                    val childCount = node.childCount
                    for (i in 0 until childCount) {
                        if (count >= 300) break
                        dfs(node.getChild(i))
                    }
                }
                dfs(root)
            }
            val windows = s.windows
            if (windows != null) {
                for (w in windows) {
                    Log.i(TAG, "window type=${w.type} title=${w.title} layer=${w.layer}")
                }
            }
        }, 1200L)
    }

    private fun runClickLabel(extras: Bundle?) {
        val service = SpikeAccessibilityService.instance
        if (service == null) {
            Log.w(TAG, "FAIL click_label service-not-enabled")
            return
        }
        val label = extras?.getString("label").orEmpty()
        if (label.isBlank()) {
            Log.w(TAG, "FAIL click_label missing label")
            return
        }
        val wait = readLong(extras, "wait", 1200L)
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        mainHandler.postDelayed({
            val s = SpikeAccessibilityService.instance
            if (s == null) {
                Log.w(TAG, "FAIL click_label service-not-enabled")
                return@postDelayed
            }
            val root = s.rootInActiveWindow
            if (root == null) {
                Log.w(TAG, "FAIL click_label not-found $label")
                return@postDelayed
            }
            val candidates = mutableListOf<AccessibilityNodeInfo>()
            fun dfs(node: AccessibilityNodeInfo?) {
                if (node == null) return
                val text = node.text?.toString().orEmpty()
                val desc = node.contentDescription?.toString().orEmpty()
                if (text.contains(label, ignoreCase = true) || desc.contains(label, ignoreCase = true)) {
                    candidates.add(node)
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)
                    Log.i(TAG, "candidate text=$text desc=$desc clickable=${node.isClickable} bounds=$bounds")
                }
                val childCount = node.childCount
                for (i in 0 until childCount) {
                    dfs(node.getChild(i))
                }
            }
            dfs(root)
            if (candidates.isEmpty()) {
                Log.w(TAG, "FAIL click_label not-found $label")
                return@postDelayed
            }
            val first = candidates.first()
            val firstText = first.text?.toString().orEmpty()
            val firstDesc = first.contentDescription?.toString().orEmpty()
            var target: AccessibilityNodeInfo? = first
            while (target != null && !target.isClickable) {
                target = target.parent
            }
            val clickNode = target ?: first
            val result = clickNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.i(TAG, "click_label result=$result on=$firstText/$firstDesc")
        }, wait)
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

    fun startSafe(ctx: Context, target: String, intent: Intent, options: Bundle?) {
        val appCtx = ctx.applicationContext
        val flagsHex = "0x${Integer.toHexString(intent.flags)}"
        try {
            appCtx.startActivity(intent, options)
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

    private fun readLong(extras: Bundle?, key: String, default: Long): Long {
        if (extras == null || !extras.containsKey(key)) return default
        return when (val v = extras.get(key)) {
            is Number -> v.toLong()
            is String -> v.toLongOrNull() ?: default
            else -> default
        }
    }

    private fun readInt(extras: Bundle?, key: String, default: Int): Int {
        if (extras == null || !extras.containsKey(key)) return default
        return when (val v = extras.get(key)) {
            is Number -> v.toInt()
            is String -> v.toIntOrNull() ?: default
            else -> default
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
