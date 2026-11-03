package com.gt8d.launcher.ui.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription

object ScreenSemantics {
    // テスト(Robolectric)では true にして、細かい semantics(testTag など)を残す
    @Volatile var detailed: Boolean = false
}

fun Modifier.screenSemantics(label: String = "パネル"): Modifier =
    if (ScreenSemantics.detailed) this else this.clearAndSetSemantics { contentDescription = label }
