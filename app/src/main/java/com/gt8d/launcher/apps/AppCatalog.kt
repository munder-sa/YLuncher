package com.gt8d.launcher.apps

import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import com.gt8d.launcher.ui.apps.AppListEntry
import com.gt8d.launcher.ui.panel.AppInfo
import java.util.concurrent.ConcurrentHashMap

class AppCatalog(private val context: Context) {
    private val iconCache = ConcurrentHashMap<String, ImageBitmap>()

    fun invalidatePackage(pkg: String) { iconCache.remove(pkg) }

    fun invalidateAllIcons() { iconCache.clear() }

    fun installed(): Set<String> {
        return runCatching {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            context.packageManager.queryIntentActivities(intent, 0)
                .mapNotNull { it.activityInfo?.packageName }
                .toSet()
        }.getOrDefault(emptySet())
    }

    fun launchables(): List<AppListEntry> {
        val pm = context.packageManager
        return runCatching {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(intent, 0).mapNotNull { resolveInfo ->
                val ai = resolveInfo.activityInfo ?: return@mapNotNull null
                val pkg = ai.packageName ?: return@mapNotNull null
                val cls = ai.name ?: return@mapNotNull null
                val label = resolveInfo.loadLabel(pm)?.toString() ?: pkg
                AppListEntry(pkg = pkg, cls = cls, label = label)
            }
        }.getOrDefault(emptyList())
    }

    fun label(pkg: String): String {
        val pm = context.packageManager
        return runCatching {
            val appInfo = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(appInfo).toString().ifBlank { pkg }
        }.getOrDefault(pkg)
    }

    fun info(pkg: String, installedPkgs: Set<String>): AppInfo {
        return AppInfo(
            pkg = pkg,
            label = label(pkg),
            icon = icon(pkg),
            installed = pkg in installedPkgs,
        )
    }

    fun icon(pkg: String): ImageBitmap? {
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
