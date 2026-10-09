package io.github.gutsmcd.blackscreen

import android.Manifest
import android.app.Activity
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Single screen: permissions, the "Black out" button, and a few settings. Built in code. */
class MainActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private var countdown = 0
    private lateinit var loc: Context
    private val stateListener: () -> Unit = { if (!isFinishing) render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    override fun onResume() {
        super.onResume()
        BlackoutController.addListener(stateListener)
        render()
        // Keep the "Black out" notification shortcut alive while we are in front.
        if (BlackoutController.canDraw(this)) BlackoutService.refresh(this)
    }

    override fun onPause() {
        BlackoutController.removeListener(stateListener)
        super.onPause()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun s(id: Int) = loc.getString(id)
    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    private fun render() {
        loc = Prefs.localized(this)
        val canDraw = BlackoutController.canDraw(this)
        val needNotif = Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(28f), dp(20f), dp(28f))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            fitsSystemWindows = true
            addView(col)
        }

        // Header + language toggle
        col.addView(text(s(R.string.app_name), 30f, Color.WHITE, bold = true))
        col.addView(text(s(R.string.tagline), 16f, GREY).apply { setPadding(0, dp(4f), 0, dp(12f)) })
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(langButton("English", Prefs.LANG_EN))
            addView(View(context), LinearLayout.LayoutParams(dp(8f), 1))
            addView(langButton("日本語", Prefs.LANG_JA))
        })

        // Permissions (first run)
        if (!canDraw || needNotif) {
            col.addView(section(s(R.string.setup_title)))
            if (!canDraw) {
                col.addView(card(s(R.string.perm_overlay_title), s(R.string.perm_overlay_why), s(R.string.allow)) {
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                    )
                })
            }
            if (needNotif) {
                col.addView(card(s(R.string.perm_notif_title), s(R.string.perm_notif_why), s(R.string.allow)) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
                })
            }
        }

        // Main action
        col.addView(section(s(R.string.start_title)))
        val big = Button(this).apply {
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTextColor(Color.WHITE)
            background = rounded(if (canDraw) ACCENT else 0xFF333333.toInt(), 16f)
            setPadding(dp(16f), dp(18f), dp(16f), dp(18f))
            isEnabled = canDraw && countdown == 0
            text = when {
                countdown > 0 -> loc.getString(R.string.counting_down, countdown)
                else -> s(R.string.blackout_button)
            }
            setOnClickListener { startCountdown() }
        }
        col.addView(big, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(text(s(R.string.blackout_button_help), 14f, GREY).apply { setPadding(0, dp(8f), 0, 0) })

        if (Build.VERSION.SDK_INT >= 33) {
            col.addView(outlineButton(s(R.string.add_tile)) { requestTile() }.apply {
                (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(12f)
            })
        }

        // How to use
        col.addView(section(s(R.string.howto_title)))
        col.addView(text(s(R.string.howto_body), 15f, LIGHT))

        // Settings
        col.addView(section(s(R.string.settings_title)))
        if (BlackoutController.proximitySupported(this)) {
            col.addView(switchRow(s(R.string.pocket_title), s(R.string.pocket_desc), Prefs.pocketSensor(this)) {
                Prefs.setPocketSensor(this, it)
            })
        }
        col.addView(switchRow(s(R.string.shortcut_title), s(R.string.shortcut_desc), Prefs.notifShortcut(this)) {
            Prefs.setNotifShortcut(this, it)
            BlackoutService.refresh(this)
        })

        // Honest limits + footer
        col.addView(section(s(R.string.limits_title)))
        col.addView(text(s(R.string.limits_body), 14f, GREY))
        col.addView(text(s(R.string.footer), 13f, GREY).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(28f), 0, 0)
        })

        setContentView(scroll)
        title = s(R.string.app_name)
    }

    private fun startCountdown() {
        if (!BlackoutController.canDraw(this)) return
        if (!BlackoutService.start(this, BlackoutService.ACTION_BLACKOUT, 3000L)) {
            Toast.makeText(this, s(R.string.start_failed), Toast.LENGTH_LONG).show()
            return
        }
        countdown = 3
        render()
        tick()
        Toast.makeText(this, s(R.string.go_back_toast), Toast.LENGTH_SHORT).show()
        // Step aside so the previous app (your video) comes back to the front.
        moveTaskToBack(true)
    }

    private fun tick() {
        main.postDelayed({
            countdown = (countdown - 1).coerceAtLeast(0)
            if (!isFinishing) render()
            if (countdown > 0) tick()
        }, 1000L)
    }

    private fun requestTile() {
        if (Build.VERSION.SDK_INT < 33) return
        val sbm = getSystemService(StatusBarManager::class.java) ?: return
        try {
            sbm.requestAddTileService(
                ComponentName(this, BlackoutTileService::class.java),
                s(R.string.tile_label),
                Icon.createWithResource(this, R.drawable.ic_tile),
                mainExecutor,
            ) { result ->
                val msg = when (result) {
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED,
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> s(R.string.tile_added)
                    else -> null
                }
                if (msg != null) Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        } catch (_: Exception) {
            Toast.makeText(this, s(R.string.tile_manual), Toast.LENGTH_LONG).show()
        }
    }

    // ---- tiny view helpers ----

    private fun text(t: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setLineSpacing(0f, 1.2f)
    }

    private fun section(t: String) = text(t, 13f, ACCENT, bold = true).apply {
        isAllCaps = true
        letterSpacing = 0.08f
        setPadding(0, dp(28f), 0, dp(10f))
    }

    private fun rounded(color: Int, r: Float, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(r).toFloat()
        if (stroke != null) setStroke(dp(1f), stroke)
    }

    private fun langButton(label: String, code: String): Button {
        val selected = Prefs.lang(this) == code
        return Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(if (selected) Color.BLACK else Color.WHITE)
            background = rounded(if (selected) Color.WHITE else Color.BLACK, 20f, Color.WHITE)
            setPadding(dp(18f), dp(6f), dp(18f), dp(6f))
            minHeight = dp(40f); minimumHeight = dp(40f)
            setOnClickListener {
                Prefs.setLang(this@MainActivity, code)
                render()
                BlackoutService.refresh(this@MainActivity)
            }
        }
    }

    private fun outlineButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.WHITE)
        background = rounded(Color.BLACK, 14f, 0xFF555555.toInt())
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setOnClickListener { onClick() }
    }

    private fun card(title: String, why: String, action: String, onClick: () -> Unit): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0xFF161616.toInt(), 14f)
            setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
        }
        box.addView(text(title, 17f, Color.WHITE, bold = true))
        box.addView(text(why, 14f, GREY).apply { setPadding(0, dp(4f), 0, dp(10f)) })
        box.addView(Button(this).apply {
            text = action
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = rounded(ACCENT, 12f)
            setOnClickListener { onClick() }
        })
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(10f))
            addView(box)
        }
    }

    @Suppress("DEPRECATION")
    private fun switchRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6f), 0, dp(10f))
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(title, 16f, Color.WHITE))
            addView(text(desc, 13f, GREY))
        }
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Switch(this).apply {
            isChecked = checked
            contentDescription = title
            setOnCheckedChangeListener { _, v -> onChange(v) }
        })
        return row
    }

    private companion object {
        const val ACCENT = 0xFF5B8DEF.toInt()
        const val GREY = 0xFF9A9A9A.toInt()
        const val LIGHT = 0xFFDDDDDD.toInt()
    }
}
