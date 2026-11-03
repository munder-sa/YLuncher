package com.gt8d.launcher.edge

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
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
import com.gt8d.launcher.ui.theme.screenSemantics
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

class CurtainWindow(
    private val context: Context,
    private val wm: WindowManagerFacade,
    private val contentFactory: (Context) -> ComposeView = { ComposeView(it) },
    private val readdStrips: () -> Unit = {},
    private val onCancel: () -> Unit = {},
) : CurtainView {

    companion object {
        private const val FADE_IN_MS = 120L
        private const val FADE_OUT_MS = 150L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val iconCache = mutableMapOf<String, ImageBitmap?>()

    internal fun iconFor(pkg: String): ImageBitmap? {
        if (!iconCache.containsKey(pkg)) {
            iconCache[pkg] = runCatching {
                // 表示は 96dp。密度に合わせた画素数で描き、ぼけないようにする。
                val px = (96 * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
                context.packageManager.getApplicationIcon(pkg).toBitmap(px, px).asImageBitmap()
            }.getOrNull()
        }
        return iconCache[pkg]
    }
    private class WindowSession {
        var view: ComposeView? = null
        var owner: OverlayLifecycleOwner? = null
        var removalAttempted = false
        var hideRunnable: Runnable? = null
    }

    private var currentSession: WindowSession? = null
    private var targetState = mutableStateOf<CurtainTarget?>(null)
    private var showProgressState = mutableStateOf(false)

    override fun show(target: CurtainTarget) {
        currentSession?.let { removeSession(it) }

        targetState.value = target
        showProgressState.value = false

        val session = WindowSession()
        currentSession = session
        try {
            val owner = OverlayLifecycleOwner()
            session.owner = owner
            owner.onCreate()

            val view = contentFactory(context)
            session.view = view
            view.setViewTreeLifecycleOwner(owner)
            view.setViewTreeSavedStateRegistryOwner(owner)
            view.setContent {
                Box(Modifier.fillMaxSize().screenSemantics()) {
                    val currentTarget = targetState.value
                    if (currentTarget != null) {
                        LauncherTheme {
                            CurtainContent(
                                target = currentTarget,
                                showProgress = showProgressState.value,
                                iconFor = ::iconFor,
                                onCancel = onCancel,
                            )
                        }
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

            wm.addView(view, params)

            view.animate()
                .alpha(1f)
                .setDuration(FADE_IN_MS)
                .start()

            readdStrips()
        } catch (e: Throwable) {
            removeSession(session)
            throw e
        }
    }

    override fun update(target: CurtainTarget) {
        targetState.value = target
    }

    override fun showProgress() {
        showProgressState.value = true
    }

    override fun hide(reason: HideReason) {
        val session = currentSession ?: return
        if (reason == HideReason.Safety) {
            removeSession(session)
            return
        }
        val view = session.view ?: return
        var scheduled = false
        try {
            session.hideRunnable?.let { handler.removeCallbacks(it) }
            session.hideRunnable = null
            view.animate().cancel()
            view.animate()
                .alpha(0f)
                .setDuration(FADE_OUT_MS)
                .start()
            val runnable = Runnable {
                try {
                    view.alpha = 0f
                } finally {
                    removeSession(session)
                }
            }
            session.hideRunnable = runnable
            scheduled = handler.postDelayed(runnable, FADE_OUT_MS)
        } finally {
            if (!scheduled) {
                removeSession(session)
            }
        }
    }

    internal fun removeImmediately() {
        currentSession?.let { removeSession(it) }
    }

    private fun removeSession(session: WindowSession) {
        if (session.removalAttempted) return
        session.removalAttempted = true
        session.hideRunnable?.let { handler.removeCallbacks(it) }
        session.hideRunnable = null
        val view = session.view
        runCatching { view?.animate()?.cancel() }
        if (currentSession === session) currentSession = null
        try {
            if (view != null) runCatching { wm.removeView(view) }
        } finally {
            runCatching { view?.disposeComposition() }
            runCatching { session.owner?.onDestroy() }
            session.view = null
            session.owner = null
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
