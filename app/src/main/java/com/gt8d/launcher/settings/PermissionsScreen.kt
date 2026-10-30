package com.gt8d.launcher.settings

import android.app.Activity
import com.gt8d.launcher.ui.layout.OneLineText
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gt8d.launcher.AppGraph
import com.gt8d.launcher.diag.DiagLog
import com.gt8d.launcher.split.SplitMode
import com.gt8d.launcher.ui.theme.Dims
import com.gt8d.launcher.ui.theme.Palette

@Composable
internal fun AppPermissionsScreen() {
    val context = LocalContext.current
    val checker = remember(context) { AndroidPermissionChecker(context) }
    val opener = remember(context) {
        val activity = context.permissionActivity()
        if (activity != null) AndroidPermissionOpener(activity) else object : PermissionOpener {
            override fun open(item: PermissionItem) {
                DiagLog.d("Permissions", "open $item failed: context is not an Activity")
            }
        }
    }
    PermissionsScreen(checker, opener, { AppGraph.selector.current() }, { AppGraph.selector.restoreVendor() })
}

private tailrec fun Context.permissionActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext === this) null else baseContext.permissionActivity()
    else -> null
}

@Composable
fun PermissionsScreen(
    checker: PermissionChecker,
    opener: PermissionOpener,
    mode: () -> SplitMode,
    restoreVendor: () -> Unit,
) {
    var currentMode by remember(mode) { mutableStateOf(mode()) }
    var grants by remember(checker) {
        mutableStateOf(permissionItems(currentMode).associateWith(checker::granted))
    }
    val refresh = {
        currentMode = mode()
        grants = permissionItems(currentMode).associateWith(checker::granted)
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, checker, mode) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    Column(Modifier.fillMaxWidth().background(Palette.Background0), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        permissionItems(currentMode).forEach { item ->
            BoxWithConstraints(Modifier.fillMaxWidth().heightIn(min = Dims.MinTouch)
                .semantics(mergeDescendants = true) {}.testTag("perm_row_$item")) {
                val labels: @Composable (Modifier) -> Unit = { modifier ->
                    Column(modifier.padding(8.dp)) {
                        OneLineText(item.label)
                        OneLineText(if (grants[item] == true) "許可済み" else "未許可")
                    }
                }
                val button: @Composable () -> Unit = {
                    if (grants[item] != true) {
                        Button(onClick = { opener.open(item) }, modifier = Modifier
                            .sizeIn(minWidth = Dims.MinTouch, minHeight = Dims.MinTouch).testTag("perm_open_$item")) {
                            OneLineText("設定を開く", fontSize = Dims.BodyText, color = androidx.compose.material3.LocalContentColor.current)
                        }
                    }
                }
                if (maxWidth <= 516.dp) Column {
                    labels(Modifier.fillMaxWidth())
                    button()
                } else Row(verticalAlignment = Alignment.CenterVertically) {
                    labels(Modifier.weight(1f))
                    button()
                }
            }
        }
        if (grants[PermissionItem.UsageAccess] != true) {
            PermissionText("使用状況へのアクセスが未許可のため、失敗の判定は入口が無い場合と呼び出し時の例外だけになります。片側の欠けは検知できず、呼び直しません。幕は時間で外します。")
        }
        PermissionText("分割の方式")
        PermissionText(when (currentMode) {
            SplitMode.Vendor -> "メーカー方式で動作中"
            SplitMode.LeftOnly -> "分割が使えなくなりました"
            SplitMode.AppPair -> "予備方式で動作中"
        }, Modifier.testTag("split_status"))
        if (currentMode != SplitMode.Vendor) {
            Button(onClick = { restoreVendor(); refresh() }, modifier = Modifier
                .sizeIn(minWidth = Dims.MinTouch, minHeight = Dims.MinTouch).testTag("split_restore")) {
                OneLineText("メーカー方式に戻す", fontSize = Dims.BodyText, color = androidx.compose.material3.LocalContentColor.current)
            }
        }
    }
}

@Composable
private fun PermissionText(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, fontSize = Dims.BodyText, color = MaterialTheme.colorScheme.onBackground)
}
