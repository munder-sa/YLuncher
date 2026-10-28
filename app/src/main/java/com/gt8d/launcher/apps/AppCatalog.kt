package com.gt8d.launcher.apps

import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import com.gt8d.launcher.ui.panel.AppInfo
import java.util.concurrent.ConcurrentHashMap

class AppCatalog(private val context: Context) {
    private val iconCache = ConcurrentHashMap<String, ImageBitmap>()

    fun installed(): Set<String> {
        return runCatching {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            context.packageManager.queryIntentActivities(intent, 0)
                .mapNotNull { it.activityInfo?.packageName }
                .toSet()
        }.getOrDefault(emptySet())
    }

    fun label(pkg: String): String {
        val pm = context.packageManager
        return runCatching {
            val appInfo = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(appInfo).toString().ifBlank { pkg }
        }.getOrDefault(pkg)
    }

    fun info(pkg: String): AppInfo {
        val installedPkgs = installed()
        return AppInfo(
            pkg = pkg,
            label = label(pkg),
            icon = loadIcon(pkg),
            installed = pkg in installedPkgs,
        )
    }

    private fun loadIcon(pkg: String): ImageBitmap? {
        iconCache[pkg]?.let { return it }
        return runCatching {
            val bitmap = context.packageManager
                .getApplicationIcon(pkg)
                .toBitmap(96, 96)
                .asImageBitmap()
            iconCache[pkg] = bitmap
            bitmap
        }.getOrNull()
    }
}
