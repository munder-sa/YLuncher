package com.gt8d.launcher.spike

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

object SpikeOverlay {
    private const val TAG = "GT8D-SPIKE"
    private const val COLOR_LIME_BG = 0x4D8FE63B

    private var leftView: View? = null
    private var rightView: View? = null

    fun show(ctx: Context) {
        val appCtx = ctx.applicationContext
        if (leftView != null || rightView != null) {
            Log.i(TAG, "overlay already shown")
            return
        }
        val wm = appCtx.getSystemService(WindowManager::class.java)
        if (wm == null) {
            Log.w(TAG, "FAIL overlay WindowManager is null")
            return
        }

        val left = createEdgeView(appCtx, "L")
        val right = createEdgeView(appCtx, "R")

        val leftParams = createLayoutParams(Gravity.START or Gravity.TOP)
        val rightParams = createLayoutParams(Gravity.END or Gravity.TOP)

        try {
            wm.addView(left, leftParams)
            leftView = left
            wm.addView(right, rightParams)
            rightView = right
            Log.i(TAG, "overlay shown (L+R, width=8px)")
        } catch (e: Exception) {
            leftView?.let { runCatching { wm.removeView(it) } }
            rightView?.let { runCatching { wm.removeView(it) } }
            leftView = null
            rightView = null
            Log.w(TAG, "FAIL overlay ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    fun hide(ctx: Context) {
        val appCtx = ctx.applicationContext
        val wm = appCtx.getSystemService(WindowManager::class.java)
        var removed = 0
        leftView?.let { view ->
            runCatching {
                wm?.removeView(view)
                removed++
            }.onFailure { e ->
                Log.w(TAG, "FAIL overlay remove L ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        rightView?.let { view ->
            runCatching {
                wm?.removeView(view)
                removed++
            }.onFailure { e ->
                Log.w(TAG, "FAIL overlay remove R ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        leftView = null
        rightView = null
        Log.i(TAG, "overlay hidden (removed=$removed)")
    }

    private fun createLayoutParams(gravity: Int): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            8,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            this.gravity = gravity
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createEdgeView(ctx: Context, side: String): View {
        return View(ctx).apply {
            setBackgroundColor(COLOR_LIME_BG)
            setOnTouchListener { _, event ->
                val actionName = when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> "ACTION_DOWN"
                    MotionEvent.ACTION_MOVE -> "ACTION_MOVE"
                    MotionEvent.ACTION_UP -> "ACTION_UP"
                    MotionEvent.ACTION_CANCEL -> "ACTION_CANCEL"
                    else -> null
                }
                if (actionName != null) {
                    Log.i(
                        TAG,
                        "touch side=$side action=$actionName x=${event.rawX} y=${event.rawY} t=${event.eventTime} pointers=${event.pointerCount}",
                    )
                }
                true
            }
        }
    }
}
