package com.gt8d.launcher.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.gt8d.launcher.ui.theme.Dims
import com.gt8d.launcher.ui.theme.Palette

@Composable
fun AppPairPrepScreen() {
    SettingsSection {
        Text("予備方式の準備は、今後の更新で使えるようになります",
            Modifier.fillMaxWidth().background(Palette.Panel).padding(16.dp).testTag("apppair_note"),
            fontSize = Dims.BodyText, color = MaterialTheme.colorScheme.onBackground)
    }
}
