package io.github.gutsmcd.blackscreen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnlockDetectorTest {
    private fun tap(d: UnlockDetector, x: Float, y: Float, t: Long, holdMs: Long = 60): Boolean {
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

    @Test fun threeTapsDoNothing() {
        val d = UnlockDetector()
        assertFalse(tap(d, 100f, 100f, 0))
        assertFalse(tap(d, 100f, 100f, 200))
        assertFalse(tap(d, 100f, 100f, 400))
    }

    @Test fun tooSlowDoesNotUnlock() {
        val d = UnlockDetector()
        var t = 0L
        repeat(6) { assertFalse(tap(d, 100f, 100f, t)); t += 700 }
    }

    @Test fun tapsAllOverTheScreenDoNotUnlock() {
        val d = UnlockDetector()
        assertFalse(tap(d, 100f, 100f, 0))
        assertFalse(tap(d, 600f, 100f, 200))
        assertFalse(tap(d, 100f, 900f, 400))
        assertFalse(tap(d, 600f, 900f, 600))
    }

    @Test fun longPressesDoNotCount() {
        val d = UnlockDetector()
        var t = 0L
        repeat(5) { assertFalse(tap(d, 100f, 100f, t, holdMs = 500)); t += 550 }
    }

    @Test fun dragsAndMultiTouchDoNotCount() {
        val d = UnlockDetector()
        assertFalse(tap(d, 100f, 100f, 0))
        assertFalse(tap(d, 100f, 100f, 200))
        d.onDown(100f, 100f, 400); d.onMove(200f, 100f)
        assertFalse(d.onUp(200f, 100f, 450))
        assertFalse(tap(d, 100f, 100f, 600))
        d.onDown(100f, 100f, 800); d.onExtraPointer()
        assertFalse(d.onUp(100f, 100f, 850))
        assertFalse(tap(d, 100f, 100f, 1000))
    }
}
