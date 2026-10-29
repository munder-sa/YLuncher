package com.gt8d.launcher.edge

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.graphics.toArgb
import com.gt8d.launcher.R
import com.gt8d.launcher.config.GestureConfig
import com.gt8d.launcher.curtain.CurtainView
import com.gt8d.launcher.safety.SafetyGuard
import com.gt8d.launcher.ui.theme.Palette
import kotlin.math.hypot

class EdgeStripService : Service() {

    companion object {
        private const val CHANNEL_ID = "gt8d_edge_strip"
        private const val CHANNEL_NAME = "GT8D Launcher"
        private const val NOTIFICATION_TEXT = "GT8D Launcher 動作中"
        private const val NOTIFICATION_ID = 1
        private const val GLOW_FADE_MS = 100L
        private const val LONG_PRESS_SLOP_DP = 16f

        fun ensureRunning(context: Context) {
            if (SafetyGuard.isFallbackActive(context)) return
            context.startForegroundService(Intent(context, EdgeStripService::class.java))
        }

        @Volatile
        var listener: ((EdgeAction) -> Unit)? = null

        @Volatile
        var curtainView: CurtainView? = null

        @Volatile
        var gestureConfig: GestureConfig = GestureConfig()
    }

    private val handler = Handler(Looper.getMainLooper())
    private var wmFacade: WindowManagerFacade? = null
    private var curtainWindow: CurtainWindow? = null
    private var leftStrip: View? = null
    private var rightStrip: View? = null
    private var leftAttached = false
    private var rightAttached = false
    internal var overlayAllowed: () -> Boolean = { Settings.canDrawOverlays(this) }
    internal var windowManagerFactory: () -> WindowManagerFacade = {
        AndroidWindowManagerFacade(getSystemService(WindowManager::class.java))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (SafetyGuard.isFallbackActive(this)) {
            stopSelf()
            return
        }
        startForegroundNotification()

        val facade = windowManagerFactory()
        wmFacade = facade

        val window = CurtainWindow(
            context = this,
            wm = facade,
            readdStrips = ::readdStrips,
        )
        curtainWindow = window
        curtainView = window

        val left = createStripView(Side.Left)
        val right = createStripView(Side.Right)
        leftStrip = left
        rightStrip = right

        ensureStripsAttached()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (SafetyGuard.isFallbackActive(this)) {
            releaseOverlays()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        ensureStripsAttached()
        return START_STICKY
    }

    override fun onDestroy() {
        releaseOverlays()
        super.onDestroy()
    }

    private fun releaseOverlays() {
        handler.removeCallbacksAndMessages(null)
        val facade = wmFacade
        if (leftAttached) leftStrip?.let { view -> runCatching { facade?.removeView(view) } }
        if (rightAttached) rightStrip?.let { view -> runCatching { facade?.removeView(view) } }
        leftStrip = null
        rightStrip = null
        leftAttached = false
        rightAttached = false

        val window = curtainWindow
        runCatching { window?.removeImmediately() }
        if (curtainView === window) curtainView = null
        curtainWindow = null
        wmFacade = null
    }

    internal fun readdStrips() {
        if (SafetyGuard.isFallbackActive(this)) return
        val facade = wmFacade ?: return
        if (leftAttached) leftStrip?.let { view ->
            runCatching { facade.removeView(view) }.onSuccess { leftAttached = false }
        }
        if (rightAttached) rightStrip?.let { view ->
            runCatching { facade.removeView(view) }.onSuccess { rightAttached = false }
        }
        ensureStripsAttached()
    }

    private fun ensureStripsAttached() {
        if (!overlayAllowed()) return
        val facade = wmFacade ?: return
        if (!leftAttached) leftStrip?.let { view ->
            runCatching { facade.addView(view, buildStripParams(Side.Left)) }
                .onSuccess { leftAttached = true }
        }
        if (!rightAttached) rightStrip?.let { view ->
            runCatching { facade.addView(view, buildStripParams(Side.Right)) }
                .onSuccess { rightAttached = true }
        }
    }

    private fun startForegroundNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW,
        )
        nm.createNotificationChannel(channel)

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(CHANNEL_NAME)
            .setContentText(NOTIFICATION_TEXT)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()

        startForeground(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
    }

    private fun buildStripParams(side: Side): WindowManager.LayoutParams {
        val width = gestureConfig.stripWidthPx.coerceAtLeast(1)
        return WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = when (side) {
                Side.Left -> Gravity.LEFT or Gravity.TOP
                Side.Right -> Gravity.RIGHT or Gravity.TOP
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createStripView(side: Side): View {
        val view = View(this).apply {
            setBackgroundColor(Palette.Accent.copy(alpha = 0.3f).toArgb())
            alpha = 0f
        }
        var downX = 0f
        var downY = 0f
        var downTime = 0L
        var longPressFired = false
        var longPressRunnable: Runnable? = null

        fun startGlow(show: Boolean) {
            view.animate().cancel()
            if (!gestureConfig.glow && show) {
                view.alpha = 0f
                return
            }
            val targetAlpha = if (show) 1f else 0f
            view.animate()
                .alpha(targetAlpha)
                .setDuration(GLOW_FADE_MS)
                .start()
        }

        fun cancelLongPress() {
            longPressRunnable?.let { handler.removeCallbacks(it) }
            longPressRunnable = null
        }

        view.setOnTouchListener { _, event ->
            val density = resources.displayMetrics.density
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    downTime = event.downTime
                    longPressFired = false
                    startGlow(true)

                    val lpMs = gestureConfig.longPressMs.toLong()
                    val runnable = Runnable {
                        if (SafetyGuard.isFallbackActive(this)) return@Runnable
                        if (!longPressFired) {
                            longPressFired = true
                            startGlow(false)
                            listener?.invoke(EdgeAction.Home)
                        }
                    }
                    longPressRunnable = runnable
                    handler.postDelayed(runnable, lpMs)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!longPressFired && longPressRunnable != null) {
                        val distDp = hypot(event.rawX - downX, event.rawY - downY) /
                            (if (density > 0f) density else 1f)
                        if (distDp >= LONG_PRESS_SLOP_DP) {
                            cancelLongPress()
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    cancelLongPress()
                    startGlow(false)
                    if (!longPressFired) {
                        val durationMs = (event.eventTime - downTime).coerceAtLeast(0L)
                        val action = EdgeGesture.classify(
                            side = side,
                            downX = downX,
                            downY = downY,
                            upX = event.rawX,
                            upY = event.rawY,
                            durationMs = durationMs,
                            longPressMs = gestureConfig.longPressMs,
                            density = density,
                        )
                        if (action != EdgeAction.None) {
                            listener?.invoke(action)
                        }
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    cancelLongPress()
                    startGlow(false)
                    true
                }

                else -> false
            }
        }
        return view
    }
}
