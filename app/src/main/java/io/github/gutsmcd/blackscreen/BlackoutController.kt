package io.github.gutsmcd.blackscreen

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
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
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Owns the black, touch-eating overlay window. Lives at process level so the tile,
 * the service and the activity all see the same state. Main thread only.
 */
// The overlay view is built from the application context only, so holding it here cannot leak an Activity.
@SuppressLint("StaticFieldLeak")
object BlackoutController {
    private const val TAG = "BlackScreen"
    private const val HINT_MS = 2500L
    private const val DOT_RESET_MS = 650L
    private const val TAPS = 4
    /** Backlight while idle: lowest the window can ask for. Pixels stay pure black. */
    private const val DIM_BRIGHTNESS = 0.01f
    /** Briefly raised while the hint is showing, so the hint and dots are readable on LCDs too. */
    private const val HINT_BRIGHTNESS = 0.12f
    private const val WAKELOCK_TIMEOUT_MS = 12L * 60 * 60 * 1000 // safety cap: 12 h

    private val main = Handler(Looper.getMainLooper())
    private var root: FrameLayout? = null
    private var hint: View? = null
    private var dots: List<View> = emptyList()
    private var detector: UnlockDetector? = null
    private var hintBright = false
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
        // Off by default: while the proximity wake lock turns the panel off, Android delivers
        // no touches at all, so the 4-tap unlock can't work until the power button is pressed.
        if (Prefs.pocketSensor(app)) acquireProximity(app)
        showHint()
        notifyChanged(app)
        return true
    }

    fun hide(context: Context) {
        val app = context.applicationContext
        val view = root ?: return
        root = null
        main.removeCallbacks(hideHint)
        main.removeCallbacks(resetDots)
        hint = null; dots = emptyList(); detector = null; hintBright = false
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

    private fun layoutParams(brightness: Float = DIM_BRIGHTNESS): WindowManager.LayoutParams {
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
        lp.screenBrightness = brightness
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

    private val hideHint = Runnable {
        hint?.visibility = View.INVISIBLE
        renderDots(0)
        root?.let { setBright(it, false) }
    }

    private val resetDots = Runnable {
        val d = detector ?: return@Runnable
        renderDots(d.expire(SystemClock.uptimeMillis()))
    }

    private fun showHint() {
        val view = root ?: return
        hint?.visibility = View.VISIBLE
        setBright(view, true)
        main.removeCallbacks(hideHint)
        main.postDelayed(hideHint, HINT_MS)
    }

    /** Raise the backlight a little while the hint shows; back to minimum afterwards. */
    private fun setBright(view: View, bright: Boolean) {
        if (hintBright == bright) return
        hintBright = bright
        try {
            view.context.getSystemService(WindowManager::class.java)
                ?.updateViewLayout(view, layoutParams(if (bright) HINT_BRIGHTNESS else DIM_BRIGHTNESS))
        } catch (e: Exception) {
            Log.w(TAG, "brightness update failed", e)
        }
    }

    private fun renderDots(n: Int) {
        dots.forEachIndexed { i, v ->
            (v.background as? GradientDrawable)?.setColor(if (i < n) DOT_ON else DOT_OFF)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildView(app: Context): FrameLayout {
        val loc = Prefs.localized(app)
        val det = UnlockDetector(requiredTaps = TAPS, slopPx = dp(app, 48f))
        detector = det
        val frame = object : FrameLayout(app) {
            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        det.onDown(ev.rawX, ev.rawY, ev.eventTime)
                        renderDots(det.taps)
                        main.removeCallbacks(resetDots)
                        showHint()
                    }
                    MotionEvent.ACTION_POINTER_DOWN -> { det.onExtraPointer(); renderDots(0) }
                    MotionEvent.ACTION_MOVE -> det.onMove(ev.rawX, ev.rawY)
                    MotionEvent.ACTION_UP -> {
                        if (det.onUp(ev.rawX, ev.rawY, ev.eventTime)) {
                            renderDots(TAPS)
                            main.post { hide(app) }
                        } else {
                            renderDots(det.taps)
                            main.removeCallbacks(resetDots)
                            main.postDelayed(resetDots, DOT_RESET_MS)
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> { det.onCancel(); renderDots(0) }
                }
                return true // swallow every touch
            }
        }
        frame.setBackgroundColor(Color.BLACK)
        frame.isHapticFeedbackEnabled = false
        frame.isSoundEffectsEnabled = false
        frame.keepScreenOn = true

        val box = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.INVISIBLE
        }
        box.addView(TextView(app).apply {
            text = loc.getString(R.string.overlay_hint)
            setTextColor(HINT_COLOR)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
        })
        val row = LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(app, 14f).toInt(), 0, 0)
        }
        val size = dp(app, 12f).toInt()
        val gap = dp(app, 9f).toInt()
        val list = ArrayList<View>()
        repeat(TAPS) {
            val dot = View(app).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(DOT_OFF)
                }
            }
            row.addView(dot, LinearLayout.LayoutParams(size, size).apply { setMargins(gap, 0, gap, 0) })
            list += dot
        }
        box.addView(row)
        frame.addView(
            box,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )
        hint = box
        dots = list
        return frame
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireProximity(app: Context) {
        if (proximityLock?.isHeld == true || !proximitySupported(app)) return
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

    private val HINT_COLOR = Color.rgb(0x5A, 0x5A, 0x5A)
    private val DOT_OFF = Color.rgb(0x22, 0x22, 0x22)
    private val DOT_ON = Color.rgb(0x80, 0x80, 0x80)

    private fun dp(c: Context, v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.resources.displayMetrics)
}
