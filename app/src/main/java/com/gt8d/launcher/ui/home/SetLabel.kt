package com.gt8d.launcher.ui.home

import com.gt8d.launcher.config.ScreenSet
import com.gt8d.launcher.config.Side
import com.gt8d.launcher.config.Slot
import com.gt8d.launcher.config.slotAt

fun setLabel(set: ScreenSet, labelFor: (String) -> String): String {
    fun label(side: Side) = when (val slot = set.slotAt(side)) {
        is Slot.App -> labelFor(slot.pkg)
        Slot.Panel -> "パネル"
    }
    return "${label(Side.Left)} | ${label(Side.Right)}"
}

fun numberedSetLabel(index: Int, set: ScreenSet, labelFor: (String) -> String): String =
    "$index  ${setLabel(set, labelFor)}"
