package com.gt8d.launcher.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gt8d.launcher.ui.layout.OneLineText
import com.gt8d.launcher.ui.theme.Dims
import com.gt8d.launcher.ui.theme.Palette

enum class SettingsRowKind { Navigate, External, Danger }
enum class SettingsButtonKind { Primary, Secondary, Danger, Outlined }

val SettingsPrimary = SemanticsPropertyKey<Boolean>("SettingsPrimary")
val SettingsTrailingIcon = SemanticsPropertyKey<String>("SettingsTrailingIcon")
val SettingsChoiceFilled = SemanticsPropertyKey<Boolean>("SettingsChoiceFilled")

@Composable
fun SettingsTopBar(title: String, onBack: () -> Unit, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onBack, Modifier.size(Dims.MinTouch).testTag("settings_back")) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "戻る", tint = Color.White)
        }
        OneLineText(title, Modifier.weight(1f).padding(horizontal = 8.dp),
            fontWeight = FontWeight.SemiBold, color = Color.White)
    }
}

@Composable
fun SettingsSection(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 16.dp)
        .then(if (title == null) Modifier else Modifier.testTag("settings_section_$title"))) {
        title?.let {
            OneLineText(it, Modifier.padding(start = 16.dp, bottom = 8.dp), color = Palette.Muted)
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.Divider),
            verticalArrangement = Arrangement.spacedBy(1.dp), content = content)
    }
}

@Composable
fun SettingsRow(
    title: String,
    tag: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true,
    kind: SettingsRowKind = SettingsRowKind.Navigate,
    modifier: Modifier = Modifier,
) {
    val foreground = when {
        !enabled -> Palette.Muted
        kind == SettingsRowKind.Danger -> Palette.Danger
        else -> Color.White
    }
    Row(modifier.fillMaxWidth().heightIn(min = Dims.MinTouch).background(Palette.Panel)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).testTag(tag)
        .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            OneLineText(title, color = foreground)
            subtitle?.let { Text(it, fontSize = Dims.BodyText, color = Palette.Muted) }
        }
        if (kind != SettingsRowKind.Danger) {
            Icon(if (kind == SettingsRowKind.Navigate) Icons.AutoMirrored.Filled.KeyboardArrowRight
                else Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = foreground,
                modifier = Modifier.padding(start = 8.dp).testTag("${tag}_trailing")
                    .semantics { this[SettingsTrailingIcon] = kind.name })
        }
    }
}

@Composable
fun SettingsButton(
    text: String,
    tag: String,
    modifier: Modifier = Modifier,
    kind: SettingsButtonKind = SettingsButtonKind.Secondary,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val primary = kind == SettingsButtonKind.Primary
    Button(onClick, modifier.padding(vertical = 4.dp)
        .sizeIn(minWidth = Dims.MinTouch, minHeight = Dims.MinTouch).testTag(tag)
        .semantics { this[SettingsPrimary] = primary }, enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        border = if (kind == SettingsButtonKind.Outlined) BorderStroke(1.dp, Color.White) else null,
        colors = ButtonDefaults.buttonColors(
            containerColor = when (kind) {
                SettingsButtonKind.Primary -> Palette.PanelAccent
                SettingsButtonKind.Secondary -> Palette.Panel
                SettingsButtonKind.Danger -> Palette.Control
                SettingsButtonKind.Outlined -> Color.Transparent
            },
            contentColor = when (kind) {
                SettingsButtonKind.Primary -> Palette.Background0
                SettingsButtonKind.Secondary -> Color.White
                SettingsButtonKind.Outlined -> Color.White
                SettingsButtonKind.Danger -> Palette.Danger
            }, disabledContainerColor = Palette.Panel, disabledContentColor = Palette.Muted,
        )) {
        if (kind == SettingsButtonKind.Outlined) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
        }
        OneLineText(text, color = LocalContentColor.current)
    }
}

@Composable
fun SettingsChoice(
    label: String,
    tag: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Surface(modifier.padding(vertical = 4.dp)
        .sizeIn(minWidth = Dims.MinTouch, minHeight = Dims.MinTouch).testTag(tag)
        .clip(RoundedCornerShape(12.dp))
        .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        .semantics { this[SettingsChoiceFilled] = selected },
        shape = RoundedCornerShape(12.dp),
        color = if (selected) Palette.PanelAccent else Palette.Background1,
    ) {
        Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
            OneLineText(label, color = if (!enabled) Palette.Muted else if (selected) Palette.Background0 else Color.White)
        }
    }
}

@Composable
fun SettingsSegments(
    options: List<Pair<String, String>>,
    selected: Int,
    onSelect: (Int) -> Unit,
    enabled: Boolean = true,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth < Dims.MinTouch * options.size) 3 else options.size.coerceAtLeast(1)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            options.withIndex().toList().chunked(columns).forEach { group ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.Background1)) {
                    group.forEach { (index, option) ->
                        Box(Modifier.weight(1f).height(Dims.MinTouch)
                            .background(if (index == selected) Palette.PanelAccent else Palette.Background1)
                            .testTag(option.second)
                            .selectable(index == selected, enabled, Role.RadioButton) { onSelect(index) },
                            contentAlignment = Alignment.Center) {
                            OneLineText(option.first, color = if (!enabled) Palette.Muted
                                else if (index == selected) Palette.Background0 else Color.White)
                        }
                    }
                    repeat(columns - group.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
fun SettingsAppRow(
    label: String,
    icon: ImageBitmap?,
    tag: String,
    placeholder: String? = null,
    highlighted: Boolean = false,
    onClick: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(Dims.MinTouch).clip(RoundedCornerShape(12.dp))
        .background(Palette.Background1)
        .then(if (highlighted) Modifier.border(1.dp, Color.White, RoundedCornerShape(12.dp)) else Modifier)
        .testTag(tag).semantics { this[SettingsAppHighlighted] = highlighted }
        .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (placeholder != null) Icon(Icons.Default.Add, null, Modifier.size(48.dp), tint = Color.White)
        else SettingsAppIcon(label, icon)
        OneLineText(placeholder ?: label, Modifier.weight(1f).padding(horizontal = 8.dp)
            .testTag("${tag}_label"), fontSize = Dims.BodyText, color = Color.White)
        OneLineText("変更", fontSize = Dims.BodyText, color = Color.White)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Color.White)
    }
}

val SettingsAppHighlighted = SemanticsPropertyKey<Boolean>("SettingsAppHighlighted")

@Composable
internal fun SettingsAppIcon(label: String, icon: ImageBitmap?, modifier: Modifier = Modifier) {
    if (icon != null) Image(icon, null, modifier.size(48.dp))
    else Box(modifier.size(48.dp).background(Palette.Panel, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
        OneLineText(label.take(1), color = Color.White)
    }
}

@Composable
fun SettingsAutoSaveNote() {
    Text("変更は自動で保存されます", fontSize = Dims.BodyText, color = Palette.Muted)
}
