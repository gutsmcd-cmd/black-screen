package io.github.gutsmcd.blackscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnlockDetectorTest {
    private fun tap(d: UnlockDetector, x: Float, y: Float, t: Long, holdMs: Long = 80): Boolean {
        d.onDown(x, y, t)
        return d.onUp(x, y, t + holdMs)
    }

    @Test fun fourQuickTapsSameSpotUnlock() {
        val d = UnlockDetector()
        assertFalse(tap(d, 100f, 100f, 0))
        assertFalse(tap(d, 105f, 98f, 250))
        assertFalse(tap(d, 98f, 104f, 500))
        assertTrue(tap(d, 102f, 101f, 750))
    }

    @Test fun fourQuickTapsAnywhereUnlock() {
        val d = UnlockDetector()
        assertFalse(tap(d, 100f, 100f, 0))
        assertFalse(tap(d, 600f, 100f, 300))
        assertFalse(tap(d, 100f, 1500f, 600))
        assertTrue(tap(d, 700f, 1800f, 900))
    }

    @Test fun relaxedRealFingerTimingUnlocks() {
        // Real-finger pace: 200-300 ms presses, ~0.5 s pauses, ~2.4 s in total.
        val d2 = UnlockDetector()
        assertFalse(tap(d2, 300f, 300f, 0, holdMs = 300))
        assertFalse(tap(d2, 320f, 290f, 800, holdMs = 300))
        assertFalse(tap(d2, 310f, 330f, 1600, holdMs = 250))
        assertTrue(tap(d2, 290f, 310f, 2200, holdMs = 200))
    }

    @Test fun smallWobbleStillCounts() {
        val d = UnlockDetector(slopPx = 144f)
        repeat(3) { i ->
            d.onDown(100f, 100f, i * 300L); d.onMove(130f, 120f)
            assertFalse(d.onUp(140f, 125f, i * 300L + 90))
        }
        d.onDown(100f, 100f, 900); d.onMove(110f, 110f)
        assertTrue(d.onUp(110f, 110f, 990))
    }

    @Test fun counterReportsProgress() {
        val d = UnlockDetector()
        assertEquals(0, d.taps)
        tap(d, 1f, 1f, 0); assertEquals(1, d.taps)
        tap(d, 1f, 1f, 300); assertEquals(2, d.taps)
        assertEquals(2, d.expire(700))
        assertEquals(0, d.expire(1100)) // > 600 ms since last lift
    }

    @Test fun threeTapsDoNothing() {
        val d = UnlockDetector()
        assertFalse(tap(d, 100f, 100f, 0))
        assertFalse(tap(d, 100f, 100f, 200))
        assertFalse(tap(d, 100f, 100f, 400))
    }

    @Test fun tooSlowDoesNotUnlock() {
        val d = UnlockDetector()
        var t = 0L
        repeat(6) { assertFalse(tap(d, 100f, 100f, t)); t += 800 } // gaps of 720 ms
    }

    @Test fun overallWindowEnforced() {
        // gaps are each OK (500 ms) but holds of 390 ms push the total past 2.5 s
        val d = UnlockDetector()
        assertFalse(tap(d, 1f, 1f, 0, holdMs = 390))
        assertFalse(tap(d, 1f, 1f, 890, holdMs = 390))
        assertFalse(tap(d, 1f, 1f, 1780, holdMs = 390))
        assertFalse(tap(d, 1f, 1f, 2670, holdMs = 390))
    }

    @Test fun longPressResets() {
        val d = UnlockDetector()
        assertFalse(tap(d, 100f, 100f, 0))
        assertFalse(tap(d, 100f, 100f, 200))
        assertFalse(tap(d, 100f, 100f, 400, holdMs = 600)) // long press
        assertEquals(0, d.taps)
        var t = 0L
        val d2 = UnlockDetector()
        repeat(5) { assertFalse(tap(d2, 100f, 100f, t, holdMs = 500)); t += 550 }
    }

    @Test fun dragsAndMultiTouchDoNotCount() {
        val d = UnlockDetector(slopPx = 144f)
        assertFalse(tap(d, 100f, 100f, 0))
        assertFalse(tap(d, 100f, 100f, 200))
        d.onDown(100f, 100f, 400); d.onMove(300f, 100f)
        assertFalse(d.onUp(300f, 100f, 450))
        assertEquals(0, d.taps)
        assertFalse(tap(d, 100f, 100f, 600))
        d.onDown(100f, 100f, 800); d.onExtraPointer()
        assertFalse(d.onUp(100f, 100f, 850))
        assertFalse(tap(d, 100f, 100f, 1000))
    }

    @Test fun cancelResets() {
        val d = UnlockDetector()
        tap(d, 1f, 1f, 0); tap(d, 1f, 1f, 200); tap(d, 1f, 1f, 400)
        d.onDown(1f, 1f, 600); d.onCancel()
        assertFalse(tap(d, 1f, 1f, 700))
    }

    @Test fun keepsWorkingAfterUnlock() {
        val d = UnlockDetector()
        repeat(3) { assertFalse(tap(d, 1f, 1f, it * 200L)) }
        assertTrue(tap(d, 1f, 1f, 600))
        repeat(3) { assertFalse(tap(d, 1f, 1f, 5000 + it * 200L)) }
        assertTrue(tap(d, 1f, 1f, 5600))
    }
}
