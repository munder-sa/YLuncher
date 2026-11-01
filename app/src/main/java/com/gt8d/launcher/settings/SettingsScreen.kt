package com.gt8d.launcher.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.gt8d.launcher.AppGraph
import com.gt8d.launcher.apps.AppCatalog
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
    initialRoute: SettingsRoute? = null,
    onOpenSet: (Int) -> Unit = {},
    setsStore: SetsStore? = null,
    labelFor: (String) -> String = { AppGraph.catalog.label(it) },
    launchablePackages: () -> List<String> = { AppGraph.catalog.launchables().map { it.pkg } },
    iconFor: ((String) -> ImageBitmap?)? = null,
) {
    var nav by remember { mutableStateOf(if (initialRoute == null) SettingsNav() else SettingsNav(listOf(SettingsRoute.Root, initialRoute))) }
    var confirmReset by remember { mutableStateOf(false) }
    val back = {
        val popped = nav.pop()
        if (popped == null || ((nav.current as? SettingsRoute.SetEdit)?.fromSplitButton == true && popped.current == SettingsRoute.Root)) {
            onExit()
        } else nav = popped
    }
    BackHandler(onBack = back)
    val dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    Column(modifier.fillMaxSize().background(Palette.Background0).padding(12.dp)) {
        SettingsTopBar(
            when (nav.current) {
                SettingsRoute.Root -> "設定"
                is SettingsRoute.SetEdit -> "画面セットの編集"
                else -> detailItems.first { it.route == nav.current }.title
            },
            onBack = {
                if (nav.current is SettingsRoute.SetEdit) dispatcher?.onBackPressed() ?: back()
                else back()
            }, onClose = onExit,
        )
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            if (nav.current == SettingsRoute.Root) {
                SettingsSection("画面") {
                    detailItems.take(5).forEach { item ->
                        SettingsRow(item.title, item.tag, { nav = nav.push(item.route) })
                    }
                }
                SettingsSection("システム") {
                    detailItems.drop(5).filter { it.route != SettingsRoute.AppPairPrep || actions.showAppPairPrep() }.forEach { item ->
                        SettingsRow(item.title, item.tag, { nav = nav.push(item.route) })
                    }
                    SettingsRow("純正ランチャーに戻す", "settings_item_stock", actions.openHomeChooser, kind = SettingsRowKind.External)
                }
                SettingsSection("元に戻す") {
                    SettingsRow("デフォルトに戻す", "settings_item_reset", { confirmReset = true }, kind = SettingsRowKind.Danger)
                    val safety = actions.safetyActive()
                    SettingsRow("安全モードを解除", "settings_item_safety", {
                        actions.clearSafety()
                        onSafetyCleared()
                    }, enabled = safety, subtitle = if (safety) null else "安全モードではありません")
                }
            } else if (nav.current == SettingsRoute.Sets || nav.current is SettingsRoute.SetEdit) {
                val context = LocalContext.current
                val store = setsStore ?: remember { SetsStore.forApp(context) }
                val catalog = remember(context) { AppCatalog(context.applicationContext) }
                SetsScreen(nav.current, store,
                    onEdit = { nav = nav.push(SettingsRoute.SetEdit(it)) },
                    onBack = back, onOpenSet = onOpenSet, labelFor = labelFor,
                    onMissing = { nav = SettingsNav(listOf(SettingsRoute.Root, SettingsRoute.Sets)) },
                    launchablePackages = launchablePackages, iconFor = iconFor ?: catalog::icon)
            } else if (nav.current == SettingsRoute.Permissions) {
                AppPermissionsScreen()
            } else if (nav.current == SettingsRoute.AppPairPrep) {
                AppPairPrepScreen()
            } else if (nav.current in listOf(SettingsRoute.Lists, SettingsRoute.Link, SettingsRoute.Gesture, SettingsRoute.Boot)) {
                val config by AppGraph.latestConfig.collectAsState()
                val loaded = config
                if (loaded == null) {
                    Text("読み込み中", color = Palette.Muted, fontSize = Dims.BodyText)
                } else {
                    val update: ((com.gt8d.launcher.config.LauncherConfig) -> com.gt8d.launcher.config.LauncherConfig) -> Unit = { transform ->
                        settingsScope.launch {
                            runCatching { AppGraph.config.update(transform) }
                                .onFailure { DiagLog.d("Settings", "update failed: $it") }
                        }
                    }
                    when (nav.current) {
                        SettingsRoute.Lists -> ListsScreen(loaded, update, labelFor, launchablePackages)
                        SettingsRoute.Link -> LinkScreen(loaded, update)
                        SettingsRoute.Gesture -> GestureScreen(loaded, update)
                        SettingsRoute.Boot -> BootSettingsScreen(loaded, update)
                        else -> Unit
                    }
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
                SettingsButton("はい", "settings_reset_yes", kind = SettingsButtonKind.Danger) {
                    confirmReset = false
                    actions.resetToDefaults()
                }
            },
            dismissButton = { SettingsButton("いいえ", "settings_reset_no") { confirmReset = false } },
        )
    }
}
