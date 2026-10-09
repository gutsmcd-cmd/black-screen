package io.github.gutsmcd.blackscreen

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log

/** Quick Settings tile: the main way to black out while a video is playing. */
class BlackoutTileService : TileService() {

    private val listener: () -> Unit = { updateTile() }

    override fun onStartListening() {
        super.onStartListening()
        BlackoutController.addListener(listener)
        updateTile()
    }

    override fun onStopListening() {
        BlackoutController.removeListener(listener)
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        if (BlackoutController.isShowing) {
            BlackoutController.hide(this)
            return
        }
        launchAndCollapse()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun launchAndCollapse() {
        val i = StartBlackoutActivity.intent(this)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pi = PendingIntent.getActivity(
                    this, 3, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                startActivityAndCollapse(pi)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(i)
            }
        } catch (e: Exception) {
            Log.w("BlackScreen", "tile launch failed, showing cover directly", e)
            if (BlackoutController.show(this)) BlackoutService.refresh(this)
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val loc = Prefs.localized(this)
        tile.label = loc.getString(R.string.tile_label)
        tile.state = if (BlackoutController.isShowing) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = loc.getString(
                if (BlackoutController.isShowing) R.string.tile_on else R.string.tile_off,
            )
        }
        tile.updateTile()
    }
}
