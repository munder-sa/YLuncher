package com.gt8d.launcher.split

import android.content.ComponentName
import android.content.Context
import android.content.Intent

data class LaunchSpec(val pkg: String, val cls: String, val extras: Map<String, String>)

interface ActivityLauncher {
    fun canResolve(pkg: String, cls: String): Boolean
    fun start(spec: LaunchSpec)
    fun startApp(pkg: String, adjacent: Boolean): Boolean
    fun isResizeable(pkg: String): Boolean
}

class AndroidActivityLauncher(private val context: Context) : ActivityLauncher {
    fun startComponent(component: ComponentName, adjacent: Boolean): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(component)
            .addFlags(if (adjacent) {
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
            } else Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: Throwable) {
            false
        }
    }

    override fun canResolve(pkg: String, cls: String): Boolean {
        val intent = Intent().setComponent(ComponentName(pkg, cls))
        return context.packageManager.resolveActivity(intent, 0) != null
    }

    override fun start(spec: LaunchSpec) {
        val intent = Intent().setComponent(ComponentName(spec.pkg, spec.cls))
        for ((key, value) in spec.extras) {
            intent.putExtra(key, value)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    override fun startApp(pkg: String, adjacent: Boolean): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        val flags = if (adjacent) {
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or
                Intent.FLAG_ACTIVITY_MULTIPLE_TASK
        } else {
            Intent.FLAG_ACTIVITY_NEW_TASK
        }
        intent.addFlags(flags)
        return try {
            context.startActivity(intent)
            true
        } catch (_: Throwable) {
            false
        }
    }

    // ActivityInfo.resizeMode は非公開 API のため読めない。分割非対応の判定は実機確認(計画 Task 20 Step 6b)で要否を決める
    override fun isResizeable(pkg: String): Boolean = true
}
