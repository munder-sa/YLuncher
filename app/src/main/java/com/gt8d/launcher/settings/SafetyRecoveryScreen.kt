package com.gt8d.launcher.settings

import android.content.Context
import com.gt8d.launcher.ui.layout.OneLineText
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.gt8d.launcher.AppGraph
import com.gt8d.launcher.safety.SafetyGuard
import com.gt8d.launcher.ui.theme.Dims
import com.gt8d.launcher.ui.theme.Palette

fun safetyRecover(context: Context) {
    SafetyGuard.clear(context)
    AppGraph.bootHandled = true
}

@Composable
fun SafetyRecoveryScreen(onRecover: () -> Unit, onStock: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().background(Palette.Background0)
        .verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("安全のため純正ランチャーを使っています", fontSize = Dims.BodyText, color = MaterialTheme.colorScheme.onBackground)
        Button(onRecover, Modifier.fillMaxWidth().sizeIn(minWidth = Dims.MinTouch, minHeight = Dims.MinTouch).testTag("safety_recover")) {
            OneLineText("安全モードを解除してホームへ", fontSize = Dims.BodyText, color = androidx.compose.material3.LocalContentColor.current)
        }
        Button(onStock, Modifier.fillMaxWidth().sizeIn(minWidth = Dims.MinTouch, minHeight = Dims.MinTouch).testTag("safety_stock")) {
            OneLineText("純正ランチャーに戻る", fontSize = Dims.BodyText, color = androidx.compose.material3.LocalContentColor.current)
        }
    }
}
