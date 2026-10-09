package io.github.gutsmcd.blackscreen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

/**
 * Foreground service (type specialUse) that keeps the process alive while the black
 * cover is up, and optionally keeps a "Black out" shortcut in the notification shade.
 */
class BlackoutService : Service() {

    companion object {
        private const val TAG = "BlackScreen"
        const val ACTION_BLACKOUT = "io.github.gutsmcd.blackscreen.BLACKOUT"
        const val ACTION_UNLOCK = "io.github.gutsmcd.blackscreen.UNLOCK"
        const val ACTION_SYNC = "io.github.gutsmcd.blackscreen.SYNC"
        const val EXTRA_DELAY_MS = "delay_ms"
        private const val CHANNEL = "blackout"
        private const val NOTIF_ID = 1

        private var instance: BlackoutService? = null

        /** Start (or update) the service. Safe to call from anywhere; never throws. */
        fun start(c: Context, action: String, delayMs: Long = 0L): Boolean {
            val i = Intent(c, BlackoutService::class.java).setAction(action).putExtra(EXTRA_DELAY_MS, delayMs)
            return try {
                c.startForegroundService(i); true
            } catch (e: Exception) {
                // e.g. ForegroundServiceStartNotAllowedException when started from the background.
                Log.w(TAG, "could not start service", e); false
            }
        }

        /** Bring the notification in line with the current state. */
        fun refresh(c: Context) {
            val s = instance
            if (s != null) s.updateState()
            else if (BlackoutController.isShowing || Prefs.notifShortcut(c)) start(c, ACTION_SYNC)
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var pending = false
    private val doBlackout = Runnable {
        pending = false
        if (!BlackoutController.show(this)) updateState()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
    }

    override fun onDestroy() {
        main.removeCallbacks(doBlackout)
        pending = false
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must go foreground promptly after startForegroundService().
        if (!goForeground(buildNotification())) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_BLACKOUT -> {
                main.removeCallbacks(doBlackout)
                val delay = intent.getLongExtra(EXTRA_DELAY_MS, 0L)
                if (delay > 0) {
                    pending = true
                    main.postDelayed(doBlackout, delay)
                } else {
                    doBlackout.run()
                }
            }
            ACTION_UNLOCK -> {
                main.removeCallbacks(doBlackout)
                pending = false
                BlackoutController.hide(this)
            }
        }
        updateState()
        return START_NOT_STICKY
    }

    fun updateState() {
        if (BlackoutController.isShowing || pending || Prefs.notifShortcut(this)) {
            try {
                createChannel() // also renames the channel after a language switch
                getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, buildNotification())
            } catch (_: Exception) { }
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun goForeground(n: Notification): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
        true
    } catch (e: Exception) {
        Log.w(TAG, "startForeground failed", e); false
    }

    private fun createChannel() {
        val loc = Prefs.localized(this)
        val ch = NotificationChannel(CHANNEL, loc.getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
        ch.setShowBadge(false)
        ch.setSound(null, null)
        ch.enableVibration(false)
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val loc = Prefs.localized(this)
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), piFlags,
        )
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        val icon = Icon.createWithResource(this, R.drawable.ic_tile)
        if (BlackoutController.isShowing) {
            val unlock = PendingIntent.getService(
                this, 1, Intent(this, BlackoutService::class.java).setAction(ACTION_UNLOCK), piFlags,
            )
            b.setContentTitle(loc.getString(R.string.notif_active_title))
                .setContentText(loc.getString(R.string.notif_active_text))
                .addAction(Notification.Action.Builder(icon, loc.getString(R.string.action_unlock), unlock).build())
        } else {
            // An activity PendingIntent closes the notification shade before blacking out.
            val blackout = PendingIntent.getActivity(
                this, 2, StartBlackoutActivity.intent(this), piFlags,
            )
            b.setContentTitle(loc.getString(R.string.notif_idle_title))
                .setContentText(loc.getString(R.string.notif_idle_text))
                .addAction(Notification.Action.Builder(icon, loc.getString(R.string.action_blackout), blackout).build())
        }
        return b.build()
    }
}
