package com.gt8d.launcher.settings

import com.gt8d.launcher.config.ScreenSet
import com.gt8d.launcher.config.Slot
import com.gt8d.launcher.config.SplitRatio
import com.gt8d.launcher.config.allowedFor
import com.gt8d.launcher.config.mirrored
import com.gt8d.launcher.sets.SetNavigator

data class SetDraft(
    val id: String,
    val left: Slot?,
    val right: Slot?,
    val ratio: SplitRatio,
    val appPairLabel: String? = null,
) {
    val canSave: Boolean get() = left != null && right != null &&
        !(left == Slot.Panel && right == Slot.Panel) && ratio.allowedFor(left, right)

    fun withLeft(slot: Slot): SetDraft = copy(
        left = slot, right = if (slot == Slot.Panel && right == Slot.Panel) null else right,
    ).correctRatio()

    fun withRight(slot: Slot): SetDraft = copy(
        right = slot, left = if (slot == Slot.Panel && left == Slot.Panel) null else left,
    ).correctRatio()

    private fun correctRatio(): SetDraft = copy(ratio = when {
        ratio.allowedFor(left ?: Slot.App(""), right ?: Slot.App("")) -> ratio
        ratio == SplitRatio.R3_7 -> SplitRatio.R4_6
        else -> SplitRatio.R6_4
    })

    fun swapped(): SetDraft = copy(left = right, right = left, ratio = ratio.mirrored(), appPairLabel = null)

    fun toScreenSet(): ScreenSet {
        check(canSave)
        return ScreenSet(id, left!!, right!!, ratio, appPairLabel)
    }

    companion object {
        fun from(set: ScreenSet) = SetDraft(set.id, set.left, set.right, set.ratio, set.appPairLabel)
        fun new(id: String) = SetDraft(id, null, Slot.Panel, SplitRatio.R6_4)
    }
}

object SetsEdit {
    fun upsert(sets: List<ScreenSet>, set: ScreenSet): List<ScreenSet> =
        if (sets.any { it.id == set.id }) sets.map { if (it.id == set.id) set else it } else sets + set

    fun delete(sets: List<ScreenSet>, id: String): List<ScreenSet> = sets.filterNot { it.id == id }
    fun moveUp(sets: List<ScreenSet>, id: String): List<ScreenSet> = move(sets, id, -1)
    fun moveDown(sets: List<ScreenSet>, id: String): List<ScreenSet> = move(sets, id, 1)

    private fun move(sets: List<ScreenSet>, id: String, delta: Int): List<ScreenSet> {
        val from = sets.indexOfFirst { it.id == id }
        val to = from + delta
        if (from < 0 || to !in sets.indices) return sets
        return sets.toMutableList().apply { this[from] = sets[to]; this[to] = sets[from] }
    }

    fun remapIndex(before: List<ScreenSet>, after: List<ScreenSet>, index: Int): Int {
        if (index == SetNavigator.HOME) return SetNavigator.HOME
        val id = before.getOrNull(index - 1)?.id ?: return SetNavigator.HOME
        return after.indexOfFirst { it.id == id } + 1
    }
}
