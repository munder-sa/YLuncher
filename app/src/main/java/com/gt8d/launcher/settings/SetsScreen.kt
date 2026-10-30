package com.gt8d.launcher.settings

import android.app.Application
import android.content.Context
import androidx.activity.compose.BackHandler
import com.gt8d.launcher.ui.layout.OneLineText
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.gt8d.launcher.AppGraph
import com.gt8d.launcher.apps.OwnPackages
import com.gt8d.launcher.config.ScreenSet
import com.gt8d.launcher.config.Slot
import com.gt8d.launcher.config.SplitRatio
import com.gt8d.launcher.config.allowedFor
import com.gt8d.launcher.ui.theme.Dims
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class SetsStore(
    val sets: Flow<List<ScreenSet>>,
    private val update: suspend ((List<ScreenSet>) -> List<ScreenSet>) -> Pair<List<ScreenSet>, List<ScreenSet>>,
    private val currentIndex: () -> Int,
    private val setCurrentIndex: (Int) -> Unit,
) {
    suspend fun change(transform: (List<ScreenSet>) -> List<ScreenSet>): List<ScreenSet> =
        withContext(NonCancellable) {
            val (before, after) = update(transform)
            withContext(Dispatchers.Main.immediate) {
                setCurrentIndex(SetsEdit.remapIndex(before, after, currentIndex()))
            }
            after
        }

    companion object {
        fun forApp(context: Context): SetsStore {
            AppGraph.init(context.applicationContext as Application)
            return SetsStore(
                AppGraph.config.config.map { it.screenSets },
                update = { transform ->
                    var result: Pair<List<ScreenSet>, List<ScreenSet>>? = null
                    AppGraph.config.update { cfg ->
                        val after = transform(cfg.screenSets)
                        result = cfg.screenSets to after
                        cfg.copy(screenSets = after)
                    }
                    val changed = checkNotNull(result)
                    // runner が読むスナップショットにも保存結果が届いてから、新規セットを開く。
                    // 別の書き換えが重なって一致しないときに止まらないよう、上限を設ける。
                    withTimeoutOrNull(2_000L) { AppGraph.latestConfig.first { it?.screenSets == changed.second } }
                    changed
                },
                currentIndex = { AppGraph.runner.currentIndex },
                setCurrentIndex = { AppGraph.runner.followIndex(it) },
            )
        }
    }
}

@Composable
fun SetsScreen(
    route: SettingsRoute,
    store: SetsStore,
    onEdit: (String?) -> Unit,
    onBack: () -> Unit,
    onMissing: () -> Unit,
    onOpenSet: (Int) -> Unit,
    labelFor: (String) -> String,
    launchablePackages: () -> List<String>,
) {
    // null は読み込み中。既存 ID の有無は最初の読み込みが終わってから調べる。
    val sets by store.sets.collectAsState(initial = null)
    val loaded = sets ?: return
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val change: ((List<ScreenSet>) -> List<ScreenSet>, (List<ScreenSet>) -> Unit) -> Unit = { transform, done ->
        if (!busy) {
            busy = true
            error = false
            scope.launch {
                try {
                    done(store.change(transform))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    error = true
                } finally {
                    busy = false
                }
            }
        }
    }
    if (error) Text("保存できませんでした。もう一度お試しください", fontSize = Dims.BodyText)
    when (route) {
        SettingsRoute.Sets -> {
            var deleting by remember { mutableStateOf<String?>(null) }
            if (loaded.isEmpty()) Text("セットがありません", fontSize = Dims.BodyText)
            loaded.forEachIndexed { index, set ->
                key(set.id) {
                    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        val stacked = maxWidth <= 516.dp
                        val name: @Composable (Modifier) -> Unit = { nameModifier ->
                            SettingsRow("${labelFor(set.left.pkg)} | ${when (val right = set.right) {
                                Slot.Panel -> "パネル"
                                is Slot.App -> labelFor(right.pkg)
                            }}", "set_row_${set.id}", { onEdit(set.id) }, modifier = nameModifier, enabled = !busy,
                                subtitle = set.ratio.label)
                        }
                        val controls: @Composable () -> Unit = {
                            SetButton("▲", "set_up_${set.id}", enabled = !busy && index > 0) {
                                change({ SetsEdit.moveUp(it, set.id) }, {})
                            }
                            SetButton("▼", "set_down_${set.id}", enabled = !busy && index < loaded.lastIndex) {
                                change({ SetsEdit.moveDown(it, set.id) }, {})
                            }
                            SetButton("削除", "set_delete_${set.id}", enabled = !busy, kind = SettingsButtonKind.Danger) { deleting = set.id }
                        }
                        if (stacked) Column {
                            name(Modifier.fillMaxWidth())
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { controls() }
                        } else Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            name(Modifier.weight(1f))
                            controls()
                        }
                    }
                }
            }
            SetButton("セットを追加", "set_add", Modifier.fillMaxWidth(), enabled = !busy) { onEdit(null) }
            deleting?.let { id ->
                AlertDialog(onDismissRequest = { deleting = null },
                    text = { Text("このセットを削除しますか?", fontSize = Dims.BodyText) },
                    confirmButton = { SettingsButton("はい", "set_delete_yes", kind = SettingsButtonKind.Danger) {
                        deleting = null
                        change({ SetsEdit.delete(it, id) }, {})
                    } },
                    dismissButton = { SetButton("いいえ", "set_delete_no") { deleting = null } })
            }
        }
        is SettingsRoute.SetEdit -> {
            val original = loaded.firstOrNull { it.id == route.setId }
            if (route.setId != null && original == null) {
                LaunchedEffect(route) { onMissing() }
            } else key(route) {
                var draft by remember { mutableStateOf(original?.let(SetDraft::from) ?: SetDraft.new(UUID.randomUUID().toString())) }
                var pickingLeft by remember { mutableStateOf<Boolean?>(null) }
                if (pickingLeft != null) {
                    BackHandler { pickingLeft = null }
                    val ownPkg = LocalContext.current.packageName
                    SetButton("アプリの選択を戻る", "set_pick_back") { pickingLeft = null }
                    launchablePackages().distinct().filterNot { it == ownPkg || it == OwnPackages.PANEL_HOST }.sortedBy(labelFor).forEach { pkg ->
                        SettingsRow(labelFor(pkg), "set_pick_$pkg", onClick = {
                            draft = if (pickingLeft == true) draft.copy(left = pkg) else draft.withRight(Slot.App(pkg))
                            pickingLeft = null
                        })
                    }
                } else {
                    SetButton("左のアプリ", "set_left", Modifier.fillMaxWidth(), enabled = !busy,
                        subtitle = draft.left?.let(labelFor) ?: "選んでください") { pickingLeft = true }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SetButton("パネル", "set_right_panel", enabled = !busy, selected = draft.right == Slot.Panel) {
                            draft = draft.withRight(Slot.Panel)
                        }
                        SetButton("アプリ", "set_right_app", enabled = !busy, selected = draft.right is Slot.App) { pickingLeft = false }
                    }
                    (draft.right as? Slot.App)?.let { OneLineText("右のアプリ: ${labelFor(it.pkg)}", fontSize = Dims.BodyText) }
                    SplitRatio.entries.filter { it.allowedFor(draft.right) }.chunked(3).forEach { ratios ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ratios.forEach { ratio ->
                                SetButton(ratio.label, "set_ratio_${ratio.label}", enabled = !busy, selected = draft.ratio == ratio) {
                                    draft = draft.copy(ratio = ratio)
                                }
                            }
                        }
                    }
                    Text("分割に対応していないアプリは、分割できないことがあります",
                        Modifier.padding(vertical = 8.dp).testTag("set_unresizeable_note"), fontSize = Dims.BodyText)
                    SettingsButton("保存", "set_save", Modifier.fillMaxWidth(), kind = SettingsButtonKind.Primary, enabled = !busy && draft.canSave) {
                        val saved = draft.toScreenSet()
                        change({ SetsEdit.upsert(it, saved) }) { after ->
                            if (route.fromSplitButton) onOpenSet(after.indexOfFirst { it.id == saved.id } + 1) else onBack()
                        }
                    }
                }
            }
        }
        else -> Unit
    }
}

@Composable
private fun SetButton(
    text: String,
    tag: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean? = null,
    subtitle: String? = null,
    kind: SettingsButtonKind = SettingsButtonKind.Secondary,
    onClick: () -> Unit,
) {
    when {
        selected != null -> SettingsChoice(text, tag, selected, onClick, modifier, enabled)
        subtitle != null -> SettingsRow(text, tag, onClick, subtitle, enabled, modifier = modifier)
        else -> SettingsButton(text, tag, modifier, enabled = enabled, kind = kind, onClick = onClick)
    }
}
