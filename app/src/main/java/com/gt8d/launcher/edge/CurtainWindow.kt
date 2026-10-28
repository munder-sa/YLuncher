package com.gt8d.launcher.edge

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.gt8d.launcher.curtain.CurtainContent
import com.gt8d.launcher.curtain.CurtainTarget
import com.gt8d.launcher.curtain.CurtainView
import com.gt8d.launcher.curtain.HideReason
import com.gt8d.launcher.ui.theme.LauncherTheme
import java.util.Collections
import java.util.IdentityHashMap

class CurtainWindow(
    private val context: Context,
    private val wm: WindowManagerFacade,
    private val contentFactory: (Context) -> ComposeView = { ComposeView(it) },
    private val readdStrips: () -> Unit = {},
) : CurtainView {

    companion object {
        private const val FADE_IN_MS = 120L
        private const val FADE_OUT_MS = 150L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val removedViews: MutableSet<View> =
        Collections.newSetFromMap(IdentityHashMap())

    private var currentView: ComposeView? = null
    private var currentLifecycleOwner: OverlayLifecycleOwner? = null
    private var targetState = mutableStateOf<CurtainTarget?>(null)
    private var showProgressState = mutableStateOf(false)
    private var hideRunnable: Runnable? = null

    override fun show(target: CurtainTarget) {
        currentView?.let { removeViewSafely(it) }

        targetState.value = target
        showProgressState.value = false

        val owner = OverlayLifecycleOwner()
        owner.onCreate()
        currentLifecycleOwner = owner

        val view = contentFactory(context)
        view.setViewTreeLifecycleOwner(owner)
        view.setViewTreeSavedStateRegistryOwner(owner)
        view.setContent {
            val currentTarget = targetState.value
            if (currentTarget != null) {
                LauncherTheme {
                    CurtainContent(
                        target = currentTarget,
                        showProgress = showProgressState.value,
                    )
                }
            }
        }
        view.alpha = 0f

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        )

        currentView = view
        wm.addView(view, params)

        view.animate()
            .alpha(1f)
            .setDuration(FADE_IN_MS)
            .start()

        readdStrips()
    }

    override fun update(target: CurtainTarget) {
        targetState.value = target
    }

    override fun showProgress() {
        showProgressState.value = true
    }

    override fun hide(reason: HideReason) {
        val view = currentView ?: return
        var scheduled = false
        try {
            view.animate().cancel()
            view.animate()
                .alpha(0f)
                .setDuration(FADE_OUT_MS)
                .start()
            val runnable = Runnable {
                try {
                    view.alpha = 0f
                } finally {
                    removeViewSafely(view)
                }
            }
            hideRunnable = runnable
            handler.postDelayed(runnable, FADE_OUT_MS)
            scheduled = true
        } finally {
            if (!scheduled) {
                removeViewSafely(view)
            }
        }
    }

    internal fun removeImmediately() {
        currentView?.let { removeViewSafely(it) }
    }

    private fun removeViewSafely(view: View) {
        hideRunnable?.let { handler.removeCallbacks(it) }
        hideRunnable = null
        runCatching { view.animate().cancel() }
        if (!removedViews.add(view)) return
        if (currentView === view) {
            currentView = null
            currentLifecycleOwner?.let { runCatching { it.onDestroy() } }
            currentLifecycleOwner = null
        }
        try {
            wm.removeView(view)
        } catch (_: Throwable) {
            // 例外を握りつぶして必ず試みる
        }
    }

    private class OverlayLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {
        private val lifecycleRegistry = LifecycleRegistry(this)
        private val savedStateRegistryController = SavedStateRegistryController.create(this)

        override val lifecycle: Lifecycle
            get() = lifecycleRegistry

        override val savedStateRegistry: SavedStateRegistry
            get() = savedStateRegistryController.savedStateRegistry

        fun onCreate() {
            savedStateRegistryController.performRestore(null)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }

        fun onDestroy() {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
    }
}
