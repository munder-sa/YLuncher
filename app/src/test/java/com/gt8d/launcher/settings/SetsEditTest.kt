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
        assertNull(draft.left)
        assertFalse(draft.canSave); assertEquals(Slot.Panel, draft.right); assertEquals(SplitRatio.R6_4, draft.ratio)
        assertThrows(IllegalStateException::class.java) { draft.toScreenSet() }
        assertTrue(draft.copy(left = Slot.App("left")).canSave)
    }
    @Test fun draft_withPanel_fixesRatio() {
        listOf(SplitRatio.R7_3 to SplitRatio.R6_4, SplitRatio.R3_7 to SplitRatio.R4_6,
            SplitRatio.R5_5 to SplitRatio.R5_5).forEach { (before, after) ->
            val draft = SetDraft("id", Slot.App("left"), Slot.App("right"), before).withRight(Slot.Panel)
            assertEquals(after, draft.ratio); assertTrue(draft.canSave)
        }
    }
    @Test fun draft_keepsAppPairLabel() {
        val set = a.copy(appPairLabel = "pair")
        assertEquals(set.copy(left = Slot.App("new")), SetDraft.from(set).copy(left = Slot.App("new")).toScreenSet())
    }
    @Test fun draft_withLeftPanel_clearsRightPanel() {
        val draft = SetDraft.new("id").withLeft(Slot.Panel)
        assertEquals(Slot.Panel, draft.left); assertNull(draft.right); assertFalse(draft.canSave)
        assertEquals(Slot.App("right"), draft.withRight(Slot.App("right")).toScreenSet().right)
    }
    @Test fun draft_withLeftPanel_fixesRatio() {
        listOf(SplitRatio.R3_7 to SplitRatio.R4_6, SplitRatio.R7_3 to SplitRatio.R6_4).forEach { (before, after) ->
            val draft = SetDraft("id", Slot.App("left"), Slot.App("right"), before).withLeft(Slot.Panel)
            assertEquals(after, draft.ratio); assertTrue(draft.canSave)
        }
    }
    @Test fun draft_withRightPanel_clearsLeftPanel() {
        val draft = SetDraft("id", Slot.Panel, Slot.App("right"), SplitRatio.R4_6).withRight(Slot.Panel)
        assertNull(draft.left); assertEquals(Slot.Panel, draft.right); assertFalse(draft.canSave)
    }
    @Test fun draft_swapped_mirrorsRatioAndClearsLabel() {
        val draft = SetDraft.from(a.copy(ratio = SplitRatio.R6_4, appPairLabel = "pair")).swapped()
        assertEquals(Slot.Panel, draft.left); assertEquals(a.left, draft.right)
        assertEquals(SplitRatio.R4_6, draft.ratio); assertNull(draft.appPairLabel)
    }
    @Test fun draft_bothPanels_cannotSave() {
        val draft = SetDraft("id", Slot.Panel, Slot.Panel, SplitRatio.R5_5)
        assertFalse(draft.canSave)
        assertThrows(IllegalStateException::class.java) { draft.toScreenSet() }
    }
    @Test fun draft_leftPanel_roundTrip() {
        val set = ScreenSet("id", Slot.Panel, Slot.App("right"), SplitRatio.R4_6, "pair")
        assertEquals(set, SetDraft.from(set).toScreenSet())
    }
}
