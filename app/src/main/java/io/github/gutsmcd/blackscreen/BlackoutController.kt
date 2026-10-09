package io.github.gutsmcd.blackscreen

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.service.quicksettings.TileService
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Owns the black, touch-eating overlay window. Lives at process level so the tile,
 * the service and the activity all see the same state. Main thread only.
 */
// The overlay view is built from the application context only, so holding it here cannot leak an Activity.
@SuppressLint("StaticFieldLeak")
object BlackoutController {
    private const val TAG = "BlackScreen"
    private const val HINT_MS = 2000L
    private const val WAKELOCK_TIMEOUT_MS = 12L * 60 * 60 * 1000 // safety cap: 12 h

    private val main = Handler(Looper.getMainLooper())
    private var root: FrameLayout? = null
    private var proximityLock: PowerManager.WakeLock? = null
    private val listeners = mutableSetOf<() -> Unit>()

    val isShowing: Boolean get() = root != null

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    fun canDraw(c: Context): Boolean = Settings.canDrawOverlays(c)

    fun proximitySupported(c: Context): Boolean {
        val pm = c.getSystemService(PowerManager::class.java) ?: return false
        return pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)
    }

    /** Shows the overlay. Returns false if not allowed (no permission) or it failed. */
    fun show(context: Context): Boolean {
        val app = context.applicationContext
        if (isShowing) return true
        if (!canDraw(app)) return false
        val wm = app.getSystemService(WindowManager::class.java) ?: return false
        val view = buildView(app)
        try {
            wm.addView(view, layoutParams())
        } catch (e: Exception) {
            Log.w(TAG, "addView failed", e)
            return false
        }
        root = view
        hideSystemBars(view)
        if (Prefs.pocketSensor(app)) acquireProximity(app)
        showHint(view)
        notifyChanged(app)
        return true
    }

    fun hide(context: Context) {
        val app = context.applicationContext
        val view = root ?: return
        root = null
        releaseProximity()
        try {
            app.getSystemService(WindowManager::class.java)?.removeViewImmediate(view)
        } catch (e: Exception) {
            Log.w(TAG, "removeView failed", e)
        }
        notifyChanged(app)
    }

    private fun notifyChanged(app: Context) {
        listeners.toList().forEach { it() }
        try {
            TileService.requestListeningState(app, ComponentName(app, BlackoutTileService::class.java))
        } catch (_: Exception) { }
        BlackoutService.refresh(app)
    }

    private fun layoutParams(): WindowManager.LayoutParams {
        @Suppress("DEPRECATION")
        val flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.OPAQUE,
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.screenBrightness = 0.01f // lowest backlight while the cover is up
        lp.buttonBrightness = 0f
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            lp.fitInsetsTypes = 0
            lp.fitInsetsSides = 0
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        lp.title = "Black Screen"
        return lp
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars(v: View) {
        // Best effort only: many phones do not let overlay windows hide the bars.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                v.windowInsetsController?.hide(WindowInsets.Type.systemBars())
            } else {
                v.systemUiVisibility = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            }
        } catch (_: Exception) { }
    }

    private val hideHint = Runnable { (root?.getChildAt(0) as? TextView)?.visibility = View.INVISIBLE }

    private fun showHint(view: FrameLayout) {
        val hint = view.getChildAt(0) as? TextView ?: return
        hint.visibility = View.VISIBLE
        main.removeCallbacks(hideHint)
        main.postDelayed(hideHint, HINT_MS)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildView(app: Context): FrameLayout {
        val loc = Prefs.localized(app)
        val detector = UnlockDetector(radiusPx = dp(app, 60f), slopPx = dp(app, 16f))
        val frame = object : FrameLayout(app) {
            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        showHint(this)
                        detector.onDown(ev.rawX, ev.rawY, ev.eventTime)
                    }
                    MotionEvent.ACTION_POINTER_DOWN -> detector.onExtraPointer()
                    MotionEvent.ACTION_MOVE -> detector.onMove(ev.rawX, ev.rawY)
                    MotionEvent.ACTION_UP ->
                        if (detector.onUp(ev.rawX, ev.rawY, ev.eventTime)) main.post { hide(app) }
                    MotionEvent.ACTION_CANCEL -> detector.onCancel()
                }
                return true // swallow every touch
            }
        }
        frame.setBackgroundColor(Color.BLACK)
        frame.isHapticFeedbackEnabled = false
        frame.isSoundEffectsEnabled = false
        val hint = TextView(app).apply {
            text = loc.getString(R.string.overlay_hint)
            setTextColor(Color.rgb(0x3A, 0x3A, 0x3A))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            visibility = View.INVISIBLE
        }
        frame.addView(
            hint,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )
        return frame
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireProximity(app: Context) {
        if (!proximitySupported(app)) return
        try {
            val pm = app.getSystemService(PowerManager::class.java) ?: return
            val lock = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "BlackScreen:pocket")
            lock.setReferenceCounted(false)
            lock.acquire(WAKELOCK_TIMEOUT_MS)
            proximityLock = lock
        } catch (e: Exception) {
            Log.w(TAG, "proximity lock failed", e)
        }
    }

    private fun releaseProximity() {
        try {
            proximityLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) { }
        proximityLock = null
    }

    private fun dp(c: Context, v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.resources.displayMetrics)
}
