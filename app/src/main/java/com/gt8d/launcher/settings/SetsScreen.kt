package com.gt8d.launcher.settings

import android.app.Application
import android.content.Context
import androidx.activity.compose.BackHandler
import com.gt8d.launcher.ui.layout.OneLineText
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
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
import com.gt8d.launcher.ui.theme.Palette
import com.gt8d.launcher.ui.home.PanelIcon
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
    iconFor: (String) -> ImageBitmap? = { null },
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
    if (error) Text("保存できませんでした。もう一度お試しください", fontSize = Dims.BodyText, color = Palette.Danger)
    when (route) {
        SettingsRoute.Sets -> {
            var deleting by remember { mutableStateOf<String?>(null) }
            if (loaded.isEmpty()) Text("セットがありません", fontSize = Dims.BodyText, color = Color.White)
            loaded.forEachIndexed { index, set ->
                key(set.id) {
                    var menu by remember { mutableStateOf(false) }
                    fun name(slot: Slot) = if (slot is Slot.App) labelFor(slot.pkg) else "パネル"
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp))
                        .background(Palette.Panel), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f).heightIn(min = Dims.MinTouch)
                            .testTag("set_row_${set.id}").clickable(enabled = !busy) { onEdit(set.id) }
                            .padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OneLineText("${index + 1}", color = Palette.Muted, fontSize = Dims.BodyText)
                            listOf(set.left, set.right).forEachIndexed { side, slot ->
                                val modifier = Modifier.padding(start = 4.dp).testTag("set_icon_${set.id}_$side")
                                if (slot == Slot.Panel) PanelIcon(modifier.size(48.dp), mirrored = side == 0)
                                else SettingsAppIcon(name(slot), iconFor((slot as Slot.App).pkg), modifier)
                            }
                            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                OneLineText("${name(set.left)} | ${name(set.right)}", color = Color.White, fontSize = Dims.BodyText)
                                OneLineText(set.ratio.label, color = Palette.Muted, fontSize = Dims.BodyText)
                            }
                        }
                        Box {
                            IconButton({ menu = true }, Modifier.size(Dims.MinTouch).testTag("set_menu_${set.id}"), enabled = !busy) {
                                Icon(Icons.Default.MoreVert, "メニュー", tint = Color.White)
                            }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(text = { Text("上へ") }, modifier = Modifier.heightIn(min = Dims.MinTouch).testTag("set_up_${set.id}"), enabled = !busy && index > 0,
                                    onClick = { menu = false; change({ SetsEdit.moveUp(it, set.id) }, {}) })
                                DropdownMenuItem(text = { Text("下へ") }, modifier = Modifier.heightIn(min = Dims.MinTouch).testTag("set_down_${set.id}"), enabled = !busy && index < loaded.lastIndex,
                                    onClick = { menu = false; change({ SetsEdit.moveDown(it, set.id) }, {}) })
                                DropdownMenuItem(text = { Text("削除", color = Palette.Danger) }, modifier = Modifier.heightIn(min = Dims.MinTouch).testTag("set_delete_${set.id}"), enabled = !busy,
                                    onClick = { menu = false; deleting = set.id })
                            }
                        }
                    }
                }
            }
            SetButton("セットを追加", "set_add", Modifier.fillMaxWidth(), enabled = !busy, kind = SettingsButtonKind.Outlined) { onEdit(null) }
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
                val initial = remember { original?.let(SetDraft::from) ?: SetDraft.new(UUID.randomUUID().toString()) }
                var draft by remember { mutableStateOf(initial) }
                var pickingLeft by remember { mutableStateOf<Boolean?>(null) }
                var confirming by remember { mutableStateOf(false) }
                val save = {
                    val saved = draft.toScreenSet()
                    confirming = false
                    change({ SetsEdit.upsert(it, saved) }) { after ->
                        if (route.fromSplitButton) onOpenSet(after.indexOfFirst { it.id == saved.id } + 1) else onBack()
                    }
                }
                BackHandler {
                    if (!busy) {
                        if (pickingLeft != null) pickingLeft = null
                        else if (draft != initial) confirming = true
                        else onBack()
                    }
                }
                if (confirming) AlertDialog(
                    onDismissRequest = { confirming = false },
                    title = { Text("変更を保存しますか?") },
                    text = { Text("保存していない変更があります。") },
                    dismissButton = {
                        Row {
                            TextButton({ confirming = false }, Modifier.testTag("set_unsaved_continue")) { Text("続ける") }
                            TextButton({ confirming = false; onBack() }, Modifier.testTag("set_unsaved_discard")) { Text("破棄", color = Palette.Danger) }
                        }
                    },
                    confirmButton = { SettingsButton("保存", "set_unsaved_save", kind = SettingsButtonKind.Primary,
                        enabled = !busy && draft.canSave, onClick = save) },
                )
                if (pickingLeft != null) {
                    val ownPkg = LocalContext.current.packageName
                    SetButton("アプリの選択を戻る", "set_pick_back") { pickingLeft = null }
                    launchablePackages().distinct().filterNot { it == ownPkg || it == OwnPackages.PANEL_HOST }.sortedBy(labelFor).forEach { pkg ->
                        SettingsRow(labelFor(pkg), "set_pick_$pkg", onClick = {
                            draft = if (pickingLeft == true) draft.withLeft(Slot.App(pkg)) else draft.withRight(Slot.App(pkg))
                            pickingLeft = null
                        })
                    }
                } else {
                    val sideEditor: @Composable (Boolean) -> Unit = { left ->
                        val slot = if (left) draft.left else draft.right
                        val title = if (left) "左" else "右"
                        val tag = if (left) "set_left" else "set_right"
                        Text(title, fontSize = Dims.BodyText, color = Palette.Muted)
                        val highlighted = slot == null && (if (left) draft.right else draft.left) == Slot.Panel
                        val choices: @Composable () -> Unit = {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SetButton("パネル", "${tag}_panel", enabled = !busy, selected = slot == Slot.Panel) {
                                    draft = if (left) draft.withLeft(Slot.Panel) else draft.withRight(Slot.Panel)
                                }
                                SetButton("アプリ", "${tag}_app", enabled = !busy, selected = slot != Slot.Panel) { pickingLeft = left }
                            }
                        }
                        val app: @Composable () -> Unit = {
                            if (slot != Slot.Panel) Column {
                                val pkg = (slot as? Slot.App)?.pkg
                                SettingsAppRow(pkg?.let(labelFor) ?: "", pkg?.let(iconFor), tag,
                                    placeholder = if (pkg == null) "アプリを選んでください" else null,
                                    highlighted = highlighted, onClick = { if (!busy) pickingLeft = left })
                                if (highlighted) OneLineText("反対側のアプリを選んでください", fontSize = Dims.BodyText, color = Palette.Muted)
                            }
                        }
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            if (maxWidth < 600.dp) Column { choices(); app() }
                            else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                choices()
                                Box(Modifier.weight(1f)) { app() }
                            }
                        }
                    }
                    sideEditor(true)
                    Button(onClick = { draft = draft.swapped() }, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = Dims.MinTouch).testTag("set_swap"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.Panel,
                            contentColor = androidx.compose.ui.graphics.Color.White)) {
                        Icon(Icons.Default.SwapHoriz, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        OneLineText("左右を入れ替える")
                    }
                    sideEditor(false)
                    Text("比率", fontSize = Dims.BodyText, color = Palette.Muted)
                    val ratios = SplitRatio.entries.filter { it.allowedFor(draft.left ?: Slot.App(""), draft.right ?: Slot.App("")) }
                    SettingsSegments(ratios.map { it.label to "set_ratio_${it.label}" }, ratios.indexOf(draft.ratio),
                        { draft = draft.copy(ratio = ratios[it]) }, enabled = !busy)
                    Text("分割に対応していないアプリは、分割できないことがあります",
                        Modifier.padding(vertical = 8.dp).testTag("set_unresizeable_note"), fontSize = Dims.BodyText, color = Palette.Muted)
                    SettingsButton("保存", "set_save", Modifier.fillMaxWidth(), kind = SettingsButtonKind.Primary,
                        enabled = !busy && draft.canSave, onClick = save)
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
