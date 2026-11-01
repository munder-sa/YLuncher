package com.gt8d.launcher.apps

import android.content.ComponentName
import android.content.Intent
import android.content.Context
import com.gt8d.launcher.diag.DiagLog

fun startStockLauncher(context: Context): Boolean = try {
    context.startActivity(stockLauncherIntent())
    true
} catch (e: Exception) {
    DiagLog.d("StockLauncher", "start failed: $e")
    false
}

fun stockLauncherIntent(): Intent {
    return Intent(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_HOME)
        component = ComponentName(
            "com.android.launcher3",
            "com.android.launcher3.uioverrides.QuickstepLauncher",
        )
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
