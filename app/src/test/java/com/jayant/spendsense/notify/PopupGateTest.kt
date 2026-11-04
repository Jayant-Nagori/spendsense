package com.jayant.spendsense.notify

import com.jayant.spendsense.notify.PopupGate.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PopupGateTest {
    private val unlocked = PopupGate.Device(popupEnabled = true, overlayAllowed = true, interactive = true, keyguardLocked = false, fullScreenAllowed = true)

    @Test fun unlockedPhoneGetsTheOverlayAtOnce() {
        assertEquals(Path.OVERLAY, PopupGate().decide(1, unlocked, 0).path)
    }

    @Test fun secondPaymentQueuesWhileTheFirstIsOnScreen() {
        val g = PopupGate()
        g.onAttaching(1, 0); g.onAttached(1)
        assertEquals(Path.QUEUE, g.decide(2, unlocked, 60_000).path)
        g.onDismissed(1)
        assertEquals(Path.OVERLAY, g.decide(2, unlocked, 60_001).path)
    }

    @Test fun overlayRemovedBySystemDoesNotBlockLaterPayments() {
        // The v1.2 bug: `current` stayed set after the window was gone, so every payment was queued forever.
        val g = PopupGate()
        g.onAttaching(1, 0); g.onAttached(1)
        g.onDetached(1)
        assertNull(g.currentId)
        assertEquals(Path.OVERLAY, g.decide(2, unlocked, 10).path)
    }

    @Test fun overlayThatNeverAttachedGoesStale() {
        val g = PopupGate(attachTimeoutMs = 2_000)
        g.onAttaching(1, 0)
        assertEquals(Path.QUEUE, g.decide(2, unlocked, 1_000).path) // still attaching
        assertEquals(Path.OVERLAY, g.decide(2, unlocked, 2_500).path) // never attached: stale, cleared
        assertNull(g.currentId)
    }

    @Test fun failedAttachClearsState() {
        val g = PopupGate()
        g.onAttaching(1, 0); g.onAttachFailed(1)
        assertFalse(g.busy(1))
    }

    @Test fun lockedOrScreenOffUsesFullScreenIntent() {
        val g = PopupGate()
        assertEquals(Path.FULL_SCREEN, g.decide(1, unlocked.copy(keyguardLocked = true), 0).path)
        assertEquals(Path.FULL_SCREEN, g.decide(1, unlocked.copy(interactive = false), 0).path)
        assertEquals("screen-off", g.decide(1, unlocked.copy(interactive = false, keyguardLocked = true), 0).reason)
    }

    @Test fun lockedWithoutFullScreenPermissionQueuesForUnlock() {
        val d = unlocked.copy(keyguardLocked = true, fullScreenAllowed = false)
        assertEquals(Path.QUEUE, PopupGate().decide(1, d, 0).path)
    }

    @Test fun noOverlayPermissionFallsBackToFullScreenIntent() {
        assertEquals(Path.FULL_SCREEN, PopupGate().decide(1, unlocked.copy(overlayAllowed = false), 0).path)
    }

    @Test fun disabledPopupNeverShows() {
        assertEquals(Path.NONE, PopupGate().decide(1, unlocked.copy(popupEnabled = false), 0).path)
    }

    @Test fun samePaymentTwiceIsNotShownTwice() {
        val g = PopupGate()
        g.onAttaching(7, 0); g.onAttached(7)
        assertEquals(Path.NONE, g.decide(7, unlocked, 5).path)
    }

    @Test fun drainOnlyWhenUnlockedAndFree() {
        val g = PopupGate()
        assertTrue(g.canDrain(unlocked, 0))
        assertFalse(g.canDrain(unlocked.copy(keyguardLocked = true), 0))
        assertFalse(g.canDrain(unlocked.copy(overlayAllowed = false), 0))
        g.onAttaching(1, 0); g.onAttached(1)
        assertFalse(g.canDrain(unlocked, 100_000))
        g.onDismissed()
        assertTrue(g.canDrain(unlocked, 100_001))
    }

    @Test fun dismissOfAnOlderIdDoesNotClearTheNewOne() {
        val g = PopupGate()
        g.onAttaching(2, 0); g.onAttached(2)
        g.onDismissed(1)
        assertEquals(2L, g.currentId)
    }
}
