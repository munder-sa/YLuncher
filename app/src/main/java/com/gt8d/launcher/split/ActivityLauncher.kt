package com.gt8d.launcher.split

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.gt8d.launcher.apps.SplitRedirect
import com.gt8d.launcher.pip.PipDetector
import com.gt8d.launcher.pip.AndroidPipDetector
import com.gt8d.launcher.diag.DiagLog

data class LaunchSpec(val pkg: String, val cls: String, val extras: Map<String, String>)

interface ActivityLauncher {
    fun canResolve(pkg: String, cls: String): Boolean
    fun start(spec: LaunchSpec)
    fun startApp(pkg: String, adjacent: Boolean): Boolean
    fun startRedirect(redirect: SplitRedirect, adjacent: Boolean): Boolean = false
    fun isResizeable(pkg: String): Boolean
    fun isInstalled(pkg: String): Boolean = false
    fun sendBroadcast(action: String, pkg: String, extras: Map<String, String>): Boolean = false
}

class AndroidActivityLauncher(
    private val context: Context,
    private val pipDetector: PipDetector = AndroidPipDetector,
) : ActivityLauncher {
    private fun launchAdjacent(pkg: String, adjacent: Boolean): Boolean {
        if (adjacent && pipDetector.isInPip(pkg)) {
            DiagLog.d("Launch", "pip pkg=$pkg -> fullscreen")
            return false
        }
        return adjacent
    }
    override fun startRedirect(redirect: SplitRedirect, adjacent: Boolean): Boolean = try {
        val intent = Intent(if (redirect.uri != null) Intent.ACTION_VIEW else Intent.ACTION_MAIN)
            .setData(redirect.uri)
        if (redirect.component != null) intent.component = redirect.component
        else intent.setPackage(redirect.targetPkg)
        // 分割の中にある行き先の画面にそのまま渡す(冷えた ChatGPT で、新しい画面が分割の外に出るのを防ぐ。実機で確認)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (launchAdjacent(redirect.targetPkg, adjacent)) intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        context.startActivity(intent)
        true
    } catch (_: Exception) {
        false
    }

    private fun launchFlags(pkg: String, adjacent: Boolean): Int =
        if (launchAdjacent(pkg, adjacent)) {
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
        } else Intent.FLAG_ACTIVITY_NEW_TASK

    fun startComponent(component: ComponentName, adjacent: Boolean): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(component)
            .addFlags(launchFlags(component.packageName, adjacent))
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

    override fun isInstalled(pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: Exception) {
        DiagLog.d("Vendor", "isInstalled pkg=$pkg exception: ${e.javaClass.simpleName}: ${e.message}")
        false
    }

    override fun sendBroadcast(action: String, pkg: String, extras: Map<String, String>): Boolean = try {
        val intent = Intent(action).setPackage(pkg)
        for ((key, value) in extras) {
            intent.putExtra(key, value)
        }
        context.sendBroadcast(intent)
        true
    } catch (e: Exception) {
        DiagLog.d("Vendor", "sendBroadcast action=$action pkg=$pkg exception: ${e.javaClass.simpleName}: ${e.message}")
        false
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
        intent.addFlags(launchFlags(pkg, adjacent))
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
