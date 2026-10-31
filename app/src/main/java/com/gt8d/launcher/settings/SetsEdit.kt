package com.gt8d.launcher.settings

import com.gt8d.launcher.config.ScreenSet
import com.gt8d.launcher.config.Slot
import com.gt8d.launcher.config.SplitRatio
import com.gt8d.launcher.config.allowedFor
import com.gt8d.launcher.sets.SetNavigator

data class SetDraft(
    val id: String,
    val left: String?,
    val right: Slot,
    val ratio: SplitRatio,
    val appPairLabel: String? = null,
) {
    val canSave: Boolean get() = left != null && ratio.allowedFor(Slot.App(left ?: ""), right)

    fun withRight(right: Slot): SetDraft = copy(right = right, ratio = when {
        ratio.allowedFor(Slot.App(left ?: ""), right) -> ratio
        ratio == SplitRatio.R3_7 -> SplitRatio.R4_6
        else -> SplitRatio.R6_4
    })

    fun toScreenSet(): ScreenSet {
        check(canSave)
        return ScreenSet(id, Slot.App(checkNotNull(left)), right, ratio, appPairLabel)
    }

    companion object {
        fun from(set: ScreenSet) = SetDraft(set.id, (set.left as? Slot.App)?.pkg, set.right, set.ratio, set.appPairLabel)
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
