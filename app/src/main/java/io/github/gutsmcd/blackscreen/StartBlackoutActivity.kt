package io.github.gutsmcd.blackscreen

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * Invisible trampoline used by the Quick Settings tile and the notification button.
 * Launching an activity is what makes Android close the notification shade; it also
 * lets the foreground service start legally on Android 12+. It finishes at once and
 * the cover appears a moment later, once the shade has slid away.
 */
class StartBlackoutActivity : Activity() {

    companion object {
        private const val SHADE_CLOSE_DELAY_MS = 700L

        fun intent(c: Context): Intent =
            Intent(c, StartBlackoutActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                    // Don't make the video app think the user left it (avoids auto picture-in-picture).
                    Intent.FLAG_ACTIVITY_NO_USER_ACTION,
            )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BlackoutController.canDraw(this)) {
            if (!BlackoutService.start(this, BlackoutService.ACTION_BLACKOUT, SHADE_CLOSE_DELAY_MS)) {
                BlackoutController.show(this)
            }
        } else {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
