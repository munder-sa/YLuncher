package com.gt8d.launcher.notice

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.compose.ui.graphics.toArgb
import com.gt8d.launcher.curtain.HideReason
import com.gt8d.launcher.diag.DiagLog
import com.gt8d.launcher.sets.Messenger
import com.gt8d.launcher.settings.SettingsActivity
import com.gt8d.launcher.time.Cancellable
import com.gt8d.launcher.time.MainThreadScheduler
import com.gt8d.launcher.time.Scheduler
import com.gt8d.launcher.ui.theme.Dims
import com.gt8d.launcher.ui.theme.Palette
import kotlin.math.roundToInt

fun noticeWidthPx(screenWidthPx: Int, density: Float): Int =
    minOf(screenWidthPx * 0.5f, 720f * density,
        screenWidthPx - 2 * (104f * density + 48f)).toInt().coerceAtLeast(1)

fun noticeActionIntent(context: Context, action: NoticeAction): Intent? = when (action) {
    NoticeAction.Home -> null
    else -> Intent(context, SettingsActivity::class.java)
        .putExtra("open_route", if (action == NoticeAction.EditSets) "sets" else "link")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

class NoticeOverlay(
    private val context: Context,
    private val curtainShowing: () -> Boolean,
    private val home: () -> Unit,
    private val scheduler: Scheduler = MainThreadScheduler(),
    private val canOverlay: () -> Boolean = { Settings.canDrawOverlays(context) },
    private val startActivity: (Intent) -> Unit = context::startActivity,
) : Messenger {
    private val windows = context.getSystemService(WindowManager::class.java)
    private var pending: Notice? = null
    private var pendingTimer: Cancellable? = null
    private var expiry: Cancellable? = null
    private var toast: Toast? = null
    internal var visibleView: LinearLayout? = null
        private set

    override fun show(notice: Notice) {
        DiagLog.d("Notice", "show ${notice::class.simpleName} text=${notice.text}")
        pendingTimer?.cancel()
        pendingTimer = null
        pending = null
        if (curtainShowing()) {
            pending = notice
            pendingTimer = scheduler.schedule(5_000L) {
                val next = pending
                pending = null
                pendingTimer = null
                if (next != null && !curtainShowing()) display(next)
            }
        } else display(notice)
    }

    fun onCurtainHidden(reason: HideReason) {
        DiagLog.d("Notice", "curtain hidden reason=$reason")
        val next = pending ?: return
        pending = null
        pendingTimer?.cancel()
        pendingTimer = null
        if (reason != HideReason.Cancelled) display(next)
    }

    private fun dismiss() {
        expiry?.cancel()
        expiry = null
        visibleView?.let { view -> runCatching { windows.removeView(view) } }
        visibleView = null
        toast?.cancel()
        toast = null
    }

    private fun display(notice: Notice) {
        dismiss()
        if (!canOverlay()) {
            toast = Toast.makeText(context, notice.text, Toast.LENGTH_SHORT).also { it.show() }
            return
        }
        val density = context.resources.displayMetrics.density
        fun dp(value: Float) = (value * density).roundToInt()
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
            background = GradientDrawable().apply {
                setColor(Palette.Panel.toArgb())
                cornerRadius = dp(16f).toFloat()
            }
        }
        row.addView(TextView(context).apply {
            text = notice.text
            textSize = Dims.BodyText.value
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        notice.action?.let { action ->
            row.addView(Button(context).apply {
                text = action.label
                textSize = Dims.BodyText.value
                setTextColor(Color.WHITE)
                minimumHeight = dp(Dims.MinTouch.value)
                minimumWidth = dp(Dims.MinTouch.value)
                isAllCaps = false
                gravity = Gravity.CENTER
                setPadding(dp(24f), 0, dp(24f), 0)
                backgroundTintList = null
                background = GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    setStroke(dp(2f), Color.WHITE)
                    cornerRadius = dp(36f).toFloat()
                }
                stateListAnimator = null
                elevation = 0f
                setOnClickListener {
                    if (visibleView !== row) return@setOnClickListener
                    dismiss()
                    val intent = noticeActionIntent(context, action)
                    if (intent == null) home() else startActivity(intent)
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val params = WindowManager.LayoutParams(
            noticeWidthPx(context.resources.displayMetrics.widthPixels, density),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(24f)
        }
        try {
            windows.addView(row, params)
            visibleView = row
            expiry = scheduler.schedule(6_000L) { dismiss() }
        } catch (e: RuntimeException) {
            DiagLog.d("Notice", "overlay failed: $e")
            toast = Toast.makeText(context, notice.text, Toast.LENGTH_SHORT).also { it.show() }
        }
    }
}
