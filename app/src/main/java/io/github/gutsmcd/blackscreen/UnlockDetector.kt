package io.github.gutsmcd.blackscreen

import kotlin.math.hypot

/**
 * Unlock gesture: [requiredTaps] short, single-finger taps anywhere on the screen,
 * in quick succession. Forgiving on purpose (real fingers wobble and land in different
 * places), yet pocket fabric still rarely triggers it: long presses, drags and
 * multi-finger contacts reset the count.
 *
 * - each tap: finger down for at most [maxTapMs] and moved at most [slopPx]
 * - gap between one tap's lift and the next touch: at most [maxGapMs]
 * - the whole sequence (first touch to last lift): at most [windowMs]
 */
class UnlockDetector(
    private val requiredTaps: Int = 4,
    private val maxTapMs: Long = 400L,
    private val maxGapMs: Long = 600L,
    private val windowMs: Long = 2500L,
    private val slopPx: Float = 144f, // ~48dp on a typical phone; pass the real value from dp
) {
    private var count = 0
    private var firstDown = -1L
    private var lastUp = -1L
    private var downTime = -1L
    private var downX = 0f
    private var downY = 0f
    private var invalid = false

    /** Taps counted so far in the current sequence (for the on-screen dots). */
    val taps: Int get() = count

    fun reset() {
        count = 0; firstDown = -1L; lastUp = -1L; downTime = -1L; invalid = false
    }

    fun onDown(x: Float, y: Float, t: Long) {
        if (count > 0 && (t - lastUp > maxGapMs || t - firstDown > windowMs)) count = 0
        downTime = t; downX = x; downY = y; invalid = false
    }

    /** A second finger touched: this contact is not a clean tap. */
    fun onExtraPointer() {
        invalid = true
        count = 0
    }

    fun onMove(x: Float, y: Float) {
        if (downTime >= 0 && hypot(x - downX, y - downY) > slopPx) invalid = true
    }

    /** @return true when the unlock gesture is complete. */
    fun onUp(x: Float, y: Float, t: Long): Boolean {
        val start = downTime
        downTime = -1L
        if (start < 0 || invalid || t - start > maxTapMs || hypot(x - downX, y - downY) > slopPx) {
            count = 0 // long press, drag or multi-touch: start over
            return false
        }
        if (count > 0 && (start - lastUp > maxGapMs || t - firstDown > windowMs)) count = 0
        if (count == 0) firstDown = start
        count++
        lastUp = t
        if (count >= requiredTaps) {
            reset()
            return true
        }
        return false
    }

    /** Drops a sequence that has gone stale; returns the remaining tap count. */
    fun expire(now: Long): Int {
        if (count > 0 && downTime < 0 && now - lastUp > maxGapMs) count = 0
        return count
    }

    fun onCancel() {
        downTime = -1L
        count = 0
    }
}
