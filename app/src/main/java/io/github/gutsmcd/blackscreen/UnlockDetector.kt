package io.github.gutsmcd.blackscreen

import kotlin.math.hypot

/**
 * Unlock gesture: [requiredTaps] short, single-finger taps on roughly the same spot,
 * all within [windowMs]. Pocket fabric tends to give long presses, drags or several
 * contact points at once, none of which count, so this is hard to trigger by accident
 * but easy on purpose.
 */
class UnlockDetector(
    private val requiredTaps: Int = 4,
    private val windowMs: Long = 1500L,
    private val maxTapMs: Long = 300L,
    private val radiusPx: Float = 150f,
    private val slopPx: Float = 40f,
) {
    private val tapTimes = ArrayList<Long>()
    private var anchorX = 0f
    private var anchorY = 0f
    private var downTime = -1L
    private var downX = 0f
    private var downY = 0f
    private var invalid = false

    fun reset() {
        tapTimes.clear(); downTime = -1L; invalid = false
    }

    fun onDown(x: Float, y: Float, t: Long) {
        downTime = t; downX = x; downY = y; invalid = false
    }

    /** A second finger touched: this contact is not a clean tap. */
    fun onExtraPointer() {
        invalid = true
        tapTimes.clear()
    }

    fun onMove(x: Float, y: Float) {
        if (hypot(x - downX, y - downY) > slopPx) invalid = true
    }

    /** @return true when the unlock gesture is complete. */
    fun onUp(x: Float, y: Float, t: Long): Boolean {
        val start = downTime
        downTime = -1L
        if (start < 0 || invalid || t - start > maxTapMs || hypot(x - downX, y - downY) > slopPx) {
            tapTimes.clear()
            return false
        }
        // Drop taps that are too old, or start over if this tap is far from the first one.
        while (tapTimes.isNotEmpty() && t - tapTimes.first() > windowMs) tapTimes.removeAt(0)
        if (tapTimes.isEmpty()) {
            anchorX = x; anchorY = y
        } else if (hypot(x - anchorX, y - anchorY) > radiusPx) {
            tapTimes.clear(); anchorX = x; anchorY = y
        }
        tapTimes.add(t)
        if (tapTimes.size >= requiredTaps) {
            tapTimes.clear()
            return true
        }
        return false
    }

    fun onCancel() {
        downTime = -1L
        tapTimes.clear()
    }
}
