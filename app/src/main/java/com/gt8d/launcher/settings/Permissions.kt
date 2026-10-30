package com.gt8d.launcher.settings

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.net.Uri
import android.provider.Settings
import com.gt8d.launcher.diag.DiagLog
import com.gt8d.launcher.foreground.UsageStatsForegroundEvents
import com.gt8d.launcher.media.MediaListenerService
import com.gt8d.launcher.split.SplitMode

enum class PermissionItem(val label: String) {
    Home("既定のホーム"),
    Notification("通知へのアクセス"),
    Overlay("他のアプリの上に表示"),
    Browser("既定のブラウザ"),
    Bluetooth("Bluetooth 接続"),
    UsageAccess("使用状況へのアクセス"),
    Accessibility("アクセシビリティ"),
}

fun permissionItems(mode: SplitMode): List<PermissionItem> =
    PermissionItem.entries.filter { it != PermissionItem.Accessibility || mode == SplitMode.AppPair }

interface PermissionChecker { fun granted(item: PermissionItem): Boolean }

class AndroidPermissionChecker(private val context: Context) : PermissionChecker {
    override fun granted(item: PermissionItem): Boolean = runCatching {
        when (item) {
            PermissionItem.Home -> context.getSystemService(RoleManager::class.java)
                ?.isRoleHeld(RoleManager.ROLE_HOME) == true
            PermissionItem.Notification -> context.getSystemService(NotificationManager::class.java)
                ?.isNotificationListenerAccessGranted(ComponentName(context, MediaListenerService::class.java)) == true
            PermissionItem.Overlay -> Settings.canDrawOverlays(context)
            PermissionItem.Browser -> context.getSystemService(RoleManager::class.java)
                ?.isRoleHeld(RoleManager.ROLE_BROWSER) == true
            PermissionItem.Bluetooth -> context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PERMISSION_GRANTED
            PermissionItem.UsageAccess -> UsageStatsForegroundEvents(context).available()
            PermissionItem.Accessibility -> false
        }
    }.getOrDefault(false)
}

interface PermissionOpener { fun open(item: PermissionItem) }

class AndroidPermissionOpener(private val activity: Activity) : PermissionOpener {
    override fun open(item: PermissionItem) {
        runCatching {
            if (item == PermissionItem.Bluetooth) {
                activity.requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
                return@runCatching
            }
            val intent = when (item) {
                PermissionItem.Home -> Intent(Settings.ACTION_HOME_SETTINGS)
                PermissionItem.Notification -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                PermissionItem.Overlay -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${activity.packageName}"))
                PermissionItem.Browser -> activity.getSystemService(RoleManager::class.java)
                    .createRequestRoleIntent(RoleManager.ROLE_BROWSER)
                PermissionItem.UsageAccess -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                PermissionItem.Accessibility -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                PermissionItem.Bluetooth -> return@runCatching
            }
            activity.startActivity(intent)
        }.onFailure { DiagLog.d("Permissions", "open $item failed: $it") }
    }
}
