package com.gt8d.launcher.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.gt8d.launcher.AppGraph
import com.gt8d.launcher.diag.DiagLog
import com.gt8d.launcher.safety.SafetyGuard
import com.gt8d.launcher.split.SplitMode
import com.gt8d.launcher.ui.theme.Dims
import com.gt8d.launcher.ui.theme.Palette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private val settingsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

class SettingsActions(
    val resetToDefaults: () -> Unit,
    val openHomeChooser: () -> Unit,
    val safetyActive: () -> Boolean,
    val clearSafety: () -> Unit,
    val showAppPairPrep: () -> Boolean,
) {
    companion object {
        fun forApp(context: Context): SettingsActions = SettingsActions(
            resetToDefaults = {
                settingsScope.launch {
                    runCatching { AppGraph.config.reset() }
                        .onFailure { DiagLog.d("Settings", "reset failed: $it") }
                }
            },
            openHomeChooser = {
                runCatching {
                    context.startActivity(Intent(Settings.ACTION_HOME_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                }.onFailure { DiagLog.d("Settings", "home chooser failed: $it") }
            },
            safetyActive = { SafetyGuard.isFallbackActive(context) },
            clearSafety = { safetyRecover(context) },
            showAppPairPrep = { AppGraph.selector.current() != SplitMode.Vendor },
        )
    }
}

private data class SettingsItem(val route: SettingsRoute, val title: String, val tag: String)
private val detailItems = listOf(
    SettingsItem(SettingsRoute.Sets, "画面セット", "settings_item_sets"),
    SettingsItem(SettingsRoute.Lists, "パネルとホーム", "settings_item_lists"),
    SettingsItem(SettingsRoute.Link, "リンクの行き先", "settings_item_link"),
    SettingsItem(SettingsRoute.Gesture, "操作", "settings_item_gesture"),
    SettingsItem(SettingsRoute.Boot, "起動時", "settings_item_boot"),
    SettingsItem(SettingsRoute.Permissions, "許可のチェック", "settings_item_permissions"),
    SettingsItem(SettingsRoute.AppPairPrep, "予備方式の準備", "settings_item_apppair"),
)

@Composable
fun SettingsHost(
    actions: SettingsActions,
    onExit: () -> Unit,
    onSafetyCleared: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var nav by remember { mutableStateOf(SettingsNav()) }
    var confirmReset by remember { mutableStateOf(false) }
    val back = { nav.pop()?.let { nav = it } ?: onExit() }
    BackHandler(onBack = back)
    Column(modifier.fillMaxSize().background(Palette.Background0).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            SettingsButton("戻る", "settings_back", onClick = back)
            Text(
                if (nav.current == SettingsRoute.Root) "設定" else detailItems.first { it.route == nav.current }.title,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                fontSize = Dims.BodyText,
                color = MaterialTheme.colorScheme.onBackground,
            )
            SettingsButton("閉じる", "settings_close", onClick = onExit)
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            if (nav.current == SettingsRoute.Root) {
                detailItems.filter { it.route != SettingsRoute.AppPairPrep || actions.showAppPairPrep() }.forEach { item ->
                    SettingsButton(item.title, item.tag, Modifier.fillMaxWidth()) { nav = nav.push(item.route) }
                }
                SettingsButton("純正ランチャーに戻す", "settings_item_stock", Modifier.fillMaxWidth(), onClick = actions.openHomeChooser)
                SettingsButton("デフォルトに戻す", "settings_item_reset", Modifier.fillMaxWidth()) { confirmReset = true }
                val safety = actions.safetyActive()
                SettingsButton("安全モードを解除", "settings_item_safety", Modifier.fillMaxWidth(),
                    enabled = safety, subtitle = if (safety) null else "安全モードではありません") {
                    actions.clearSafety()
                    onSafetyCleared()
                }
            } else {
                Text("この画面は準備中です", Modifier.padding(16.dp).testTag("settings_placeholder"),
                    fontSize = Dims.BodyText, color = MaterialTheme.colorScheme.onBackground)
            }
        }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            text = { Text("設定をデフォルトに戻しますか?", fontSize = Dims.BodyText) },
            confirmButton = {
                SettingsButton("はい", "settings_reset_yes") {
                    confirmReset = false
                    actions.resetToDefaults()
                }
            },
            dismissButton = { SettingsButton("いいえ", "settings_reset_no") { confirmReset = false } },
        )
    }
}

@Composable
private fun SettingsButton(
    title: String,
    tag: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    Button(onClick, modifier.padding(vertical = 4.dp).sizeIn(minWidth = Dims.MinTouch, minHeight = Dims.MinTouch).testTag(tag),
        enabled = enabled) {
        Column {
            Text(title, fontSize = Dims.BodyText)
            if (subtitle != null) Text(subtitle, fontSize = Dims.BodyText)
        }
    }
}
