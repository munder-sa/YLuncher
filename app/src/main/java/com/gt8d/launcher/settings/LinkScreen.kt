package com.gt8d.launcher.settings

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.gt8d.launcher.config.LauncherConfig
import com.gt8d.launcher.config.LinkMode
import com.gt8d.launcher.link.BrowserChooser
import com.gt8d.launcher.ui.theme.Dims

@Composable
fun LinkScreen(config: LauncherConfig, onUpdate: ((LauncherConfig) -> LauncherConfig) -> Unit) {
    SettingsAutoSaveNote()
    val pm = LocalContext.current.packageManager
    val browsers = BrowserChooser.installedBrowsers(pm)
    val handlers = pm.queryIntentActivities(
        Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")), PackageManager.MATCH_ALL)
    fun label(pkg: String): String = handlers.firstOrNull { it.activityInfo?.packageName == pkg }
        ?.loadLabel(pm)?.toString() ?: pkg
    val actual = BrowserChooser.choose(config.link.browserPkg, browsers)
    SettingsSection("開き方") {
        SettingsChoice("ブラウザで分割", "link_mode_split", config.link.mode == LinkMode.InAppSplit,
            modifier = Modifier.fillMaxWidth(), onClick = {
                onUpdate { it.copy(link = it.link.copy(mode = LinkMode.InAppSplit)) }
            })
        SettingsChoice("全画面で開く", "link_mode_full", config.link.mode == LinkMode.Chrome,
            modifier = Modifier.fillMaxWidth(), onClick = {
                onUpdate { it.copy(link = it.link.copy(mode = LinkMode.Chrome)) }
            })
    }
    SettingsSection("開くブラウザ") {
        if (config.link.browserPkg !in browsers) {
            SettingsChoice("${config.link.browserPkg} (入っていません)",
                "link_browser_${config.link.browserPkg}", selected = true, enabled = false,
                modifier = Modifier.fillMaxWidth(), onClick = {})
        }
        browsers.forEach { pkg ->
            val icon = remember(pm, pkg) {
                runCatching {
                    handlers.first { it.activityInfo.packageName == pkg }
                        .loadIcon(pm).toBitmap(96, 96).asImageBitmap()
                }.getOrNull()
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                icon?.let { Image(it, contentDescription = null, modifier = Modifier.padding(8.dp).size(48.dp)) }
                SettingsChoice(label(pkg), "link_browser_$pkg", config.link.browserPkg == pkg,
                    modifier = Modifier.weight(1f), onClick = {
                        onUpdate { it.copy(link = it.link.copy(browserPkg = pkg)) }
                    })
            }
        }
        if (browsers.isEmpty()) Text("ブラウザが入っていません", fontSize = Dims.BodyText)
        if (actual != null && actual != config.link.browserPkg) {
            Text("実際に使うブラウザ: ${label(actual)}", fontSize = Dims.BodyText)
        }
    }
}
