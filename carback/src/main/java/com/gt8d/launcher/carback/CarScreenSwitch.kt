package com.gt8d.launcher.carback

import android.content.Context
import android.content.Intent

object CarScreenSwitch {
    const val ACTION = "com.suding.main.ACTION_EXECUTE_AT_CMD"
    const val TARGET_PKG = "com.carsyso.main"
    const val EXTRA_AT_CMD = "extra_at_cmd"
    const val AT_CMD_BACK = "AT#CB"

    fun buildIntent(): Intent = Intent(ACTION)
        .setPackage(TARGET_PKG)
        .putExtra(EXTRA_AT_CMD, AT_CMD_BACK)

    fun send(context: Context): Boolean = try {
        context.sendBroadcast(buildIntent())
        true
    } catch (_: Exception) {
        false
    }
}
