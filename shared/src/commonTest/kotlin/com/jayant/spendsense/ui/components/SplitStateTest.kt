package com.jayant.spendsense.ui.components

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

class SplitStateTest {
    @Test fun defaultsToAllMine() {
        val s = SplitState(250.0)
        assertTrue(s.fullyMine && !s.isSplit && !s.forOthers && s.isValid)
        assertNull(s.myShare)
        assertNull(s.paidForName)
    }

    @Test fun forSomeoneElseIsZeroShareAndTheyOweEverything() {
        val s = SplitState(250.0)
        s.mode = SplitMode.FOR_OTHERS
        s.paidFor = "  Rahul "
        assertTrue(s.isSplit && s.forOthers && s.isValid)
        assertEquals(0.0, s.myShare!!, 0.0)
        assertEquals(250.0, s.othersOwe, 0.0)
        assertEquals("Rahul", s.paidForName)
    }

    @Test fun nameIsOptional() {
        val s = SplitState(250.0, forOthers = true)
        assertTrue(s.isValid)
        assertNull(s.paidForName)
    }

    @Test fun nameIsIgnoredOutsideForSomeoneElse() {
        val s = SplitState(250.0, paidFor = "Rahul")
        assertNull(s.paidForName)
        s.mode = SplitMode.FOR_OTHERS
        assertEquals("Rahul", s.paidForName)
        s.mode = SplitMode.MINE
        assertNull(s.paidForName)
    }

    @Test fun splitStillNeedsAValidShare() {
        val s = SplitState(300.0, fullyMine = false)
        assertFalse(s.isValid)
        s.shareText = "100"
        assertTrue(s.isValid)
        assertEquals(100.0, s.myShare!!, 0.0)
        assertEquals(200.0, s.othersOwe, 0.0)
        s.shareText = "400"
        assertFalse(s.isValid)
    }

    @Test fun legacyFullyMineSetterMapsToModes() {
        val s = SplitState(100.0, forOthers = true)
        s.fullyMine = false
        assertEquals(SplitMode.SPLIT, s.mode)
        s.fullyMine = true
        assertEquals(SplitMode.MINE, s.mode)
    }
}
