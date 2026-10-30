package com.gt8d.launcher.settings

import androidx.compose.foundation.layout.*
import com.gt8d.launcher.ui.layout.OneLineText
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.gt8d.launcher.config.LauncherConfig
import com.gt8d.launcher.config.LinkMode
import com.gt8d.launcher.ui.theme.Dims

@Composable
fun LinkScreen(config: LauncherConfig, onUpdate: ((LauncherConfig) -> LauncherConfig) -> Unit) {
    var input by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    SettingsChoice("内蔵ブラウザで分割", "link_mode_inapp", config.link.mode == LinkMode.InAppSplit, modifier = Modifier.fillMaxWidth(), onClick = {
        onUpdate { it.copy(link = it.link.copy(mode = LinkMode.InAppSplit)) }
    })
    SettingsChoice("Chrome で開く", "link_mode_chrome", config.link.mode == LinkMode.Chrome, modifier = Modifier.fillMaxWidth(), onClick = {
        onUpdate { it.copy(link = it.link.copy(mode = LinkMode.Chrome)) }
    })
    OneLineText("Chrome に回すドメイン", fontSize = Dims.BodyText)
    config.link.chromeDomains.forEach { domain ->
        Column(Modifier.fillMaxWidth()) {
            OneLineText(domain, fontSize = Dims.BodyText)
            DailyButton("削除", "link_domain_remove_$domain", kind = SettingsButtonKind.Danger) {
                onUpdate { it.copy(link = it.link.copy(chromeDomains = DailySettings.remove(it.link.chromeDomains, domain))) }
            }
        }
    }
    OutlinedTextField(input, { input = it; invalid = false },
        modifier = Modifier.fillMaxWidth().heightIn(min = Dims.MinTouch).testTag("link_domain_input"),
        label = { OneLineText("ドメイン", fontSize = Dims.BodyText) }, singleLine = true,
        textStyle = LocalTextStyle.current.copy(fontSize = Dims.BodyText), isError = invalid)
    DailyButton("追加", "link_domain_add", Modifier.fillMaxWidth()) {
        val domain = DailySettings.normalizeDomain(input)
        invalid = domain == null
        if (domain != null) {
            if (domain !in config.link.chromeDomains) onUpdate {
                it.copy(link = it.link.copy(chromeDomains = DailySettings.add(it.link.chromeDomains, domain, null)))
            }
            input = ""
        }
    }
    if (invalid) Text("正しいドメインを入力してください", fontSize = Dims.BodyText)
}
