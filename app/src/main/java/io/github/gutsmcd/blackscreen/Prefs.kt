package io.github.gutsmcd.blackscreen

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/** Tiny settings store plus the in-app language switch (English default). */
object Prefs {
    private const val FILE = "settings"
    private const val KEY_LANG = "lang"
    private const val KEY_POCKET = "pocket_sensor"
    private const val KEY_SHORTCUT = "notif_shortcut"
    private const val KEY_OVERLAY_ASKED = "overlay_asked"

    const val LANG_EN = "en"
    const val LANG_JA = "ja"

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun lang(c: Context): String = sp(c).getString(KEY_LANG, LANG_EN) ?: LANG_EN
    fun setLang(c: Context, v: String) = sp(c).edit().putString(KEY_LANG, v).apply()

    /** Turn the display fully off while the proximity sensor is covered (pocket / face down). */
    fun pocketSensor(c: Context): Boolean = sp(c).getBoolean(KEY_POCKET, true)
    fun setPocketSensor(c: Context, v: Boolean) = sp(c).edit().putBoolean(KEY_POCKET, v).apply()

    /** Keep a small notification with a "Black out" button while idle. */
    fun notifShortcut(c: Context): Boolean = sp(c).getBoolean(KEY_SHORTCUT, true)
    fun setNotifShortcut(c: Context, v: Boolean) = sp(c).edit().putBoolean(KEY_SHORTCUT, v).apply()

    /** True once the user has been sent to the "Display over other apps" screen. */
    fun overlayAsked(c: Context): Boolean = sp(c).getBoolean(KEY_OVERLAY_ASKED, false)
    fun setOverlayAsked(c: Context, v: Boolean) = sp(c).edit().putBoolean(KEY_OVERLAY_ASKED, v).apply()

    /** A Context whose resources use the language picked inside the app. */
    fun localized(c: Context): Context {
        val conf = Configuration(c.resources.configuration)
        conf.setLocale(Locale.forLanguageTag(lang(c)))
        return c.createConfigurationContext(conf)
    }
}
