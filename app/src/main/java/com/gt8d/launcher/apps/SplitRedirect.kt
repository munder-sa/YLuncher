package com.gt8d.launcher.apps

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

data class SplitRedirect(val targetPkg: String, val component: ComponentName?, val uri: Uri?)

fun interface SplitRedirects {
    fun forPackage(pkg: String): SplitRedirect?
}

class AndroidSplitRedirects(private val pm: PackageManager) : SplitRedirects {
    override fun forPackage(pkg: String): SplitRedirect? = try {
        val activities = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg),
            PackageManager.GET_META_DATA,
        )
        val metadata = activities.firstOrNull {
            it.activityInfo?.metaData?.containsKey(PACKAGE) == true
        }?.activityInfo?.metaData
        val target = metadata?.getString(PACKAGE)
        if (target.isNullOrEmpty() || target == pkg || pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(target), 0,
            ).isEmpty()) {
            null
        } else {
            val raw = metadata.getString(COMPONENT)
            val parts = raw?.split('/')
            val component = if (parts?.size == 2 && parts.all { it.isNotEmpty() } && parts[0] == target) {
                ComponentName.unflattenFromString(raw)?.takeIf { it.packageName == target }
            } else null
            SplitRedirect(target, component, metadata.getString(URI)?.let(Uri::parse))
        }
    } catch (_: Exception) {
        null
    }

    companion object {
        const val PACKAGE = "com.gt8d.launcher.split.PACKAGE"
        const val COMPONENT = "com.gt8d.launcher.split.COMPONENT"
        const val URI = "com.gt8d.launcher.split.URI"
    }
}
