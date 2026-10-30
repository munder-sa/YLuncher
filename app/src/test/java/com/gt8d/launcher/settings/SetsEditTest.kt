package com.gt8d.launcher.settings

import com.gt8d.launcher.config.*
import org.junit.Assert.*
import org.junit.Test

class SetsEditTest {
    private val a = ScreenSet("a", Slot.App("left"), Slot.Panel)
    private val b = a.copy(id = "b")
    private val c = a.copy(id = "c")
    private val sets = listOf(a, b, c)
    @Test fun upsert_replacesSameId_appendsNew() {
        val changed = a.copy(ratio = SplitRatio.R6_4)
        assertEquals(listOf(changed, b, c), SetsEdit.upsert(sets, changed))
        assertEquals(sets + a.copy(id = "d"), SetsEdit.upsert(sets, a.copy(id = "d")))
    }
    @Test fun delete_removes() { assertEquals(listOf(a, c), SetsEdit.delete(sets, "b")) }
    @Test fun moveUp_first_noChange() { assertEquals(sets, SetsEdit.moveUp(sets, "a")) }
    @Test fun moveDown_last_noChange() { assertEquals(sets, SetsEdit.moveDown(sets, "c")) }
    @Test fun moveUp_swaps() { assertEquals(listOf(b, a, c), SetsEdit.moveUp(sets, "b")) }
    @Test fun remap_followsMovedSet() { assertEquals(1, SetsEdit.remapIndex(sets, SetsEdit.moveUp(sets, "b"), 2)) }
    @Test fun remap_deletedCurrent_isHome() { assertEquals(0, SetsEdit.remapIndex(sets, SetsEdit.delete(sets, "b"), 2)) }
    @Test fun remap_home_staysHome() { assertEquals(0, SetsEdit.remapIndex(sets, sets.reversed(), 0)) }
    @Test fun remap_otherDeleted_shifts() { assertEquals(2, SetsEdit.remapIndex(sets, SetsEdit.delete(sets, "a"), 3)) }
    @Test fun draft_new_cannotSaveWithoutLeft() {
        val draft = SetDraft.new("new")
        assertFalse(draft.canSave); assertEquals(Slot.Panel, draft.right); assertEquals(SplitRatio.R6_4, draft.ratio)
        assertThrows(IllegalStateException::class.java) { draft.toScreenSet() }
        assertTrue(draft.copy(left = "left").canSave)
    }
    @Test fun draft_withPanel_fixesRatio() {
        listOf(SplitRatio.R7_3 to SplitRatio.R6_4, SplitRatio.R3_7 to SplitRatio.R4_6,
            SplitRatio.R5_5 to SplitRatio.R5_5).forEach { (before, after) ->
            val draft = SetDraft("id", "left", Slot.App("right"), before).withRight(Slot.Panel)
            assertEquals(after, draft.ratio); assertTrue(draft.canSave)
        }
    }
    @Test fun draft_keepsAppPairLabel() {
        val set = a.copy(appPairLabel = "pair")
        assertEquals(set.copy(left = Slot.App("new")), SetDraft.from(set).copy(left = "new").toScreenSet())
    }
}
