package com.gt8d.launcher.edge

import android.view.View
import android.view.WindowManager

interface WindowManagerFacade {
    fun addView(view: View, params: WindowManager.LayoutParams)
    fun removeView(view: View)
}

class AndroidWindowManagerFacade(private val wm: WindowManager) : WindowManagerFacade {
    override fun addView(view: View, params: WindowManager.LayoutParams) {
        wm.addView(view, params)
    }

    override fun removeView(view: View) {
        wm.removeView(view)
    }
}
