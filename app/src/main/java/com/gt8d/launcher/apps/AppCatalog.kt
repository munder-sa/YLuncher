package com.gt8d.launcher.apps

import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import com.gt8d.launcher.ui.apps.AppListEntry
import com.gt8d.launcher.ui.panel.AppInfo
import java.util.concurrent.ConcurrentHashMap

class AppCatalog(context: Context) {
    private val context = context.applicationContext
    // 共有所有者では例外を空一覧に変えず、最後の成功と未取得を区別する。
    internal fun readEntries(): List<AppListEntry> {
        val pm = context.packageManager
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .mapNotNull { resolved ->
                val activity = resolved.activityInfo ?: return@mapNotNull null
                val pkg = activity.packageName ?: return@mapNotNull null
                val cls = activity.name ?: return@mapNotNull null
                AppListEntry(pkg, cls, resolved.loadLabel(pm)?.toString() ?: pkg)
            }
    }

    internal fun readInfo(pkg: String, installed: Set<String>): AppInfo {
        val pm = context.packageManager
        val label = try {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString().ifBlank { pkg }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (_: Exception) { pkg }
        val image = try {
            pm.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (_: Exception) { null }
        return AppInfo(pkg, label, image, pkg in installed)
    }

    private val labelCache = ConcurrentHashMap<String, String>()
    private val iconCache = ConcurrentHashMap<String, ImageBitmap>()

    fun invalidatePackage(pkg: String) { iconCache.remove(pkg); labelCache.remove(pkg) }

    fun invalidateAllIcons() { iconCache.clear(); labelCache.clear() }

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
        labelCache[pkg]?.let { return it }
        val pm = context.packageManager
        return runCatching {
            val appInfo = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(appInfo).toString().ifBlank { pkg }
        }.getOrDefault(pkg).also { labelCache[pkg] = it }
    }

    fun info(pkg: String, installedPkgs: Set<String>): AppInfo {
        if (pkg !in installedPkgs) invalidatePackage(pkg)
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
