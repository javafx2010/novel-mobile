package com.wildenglish.app.i18n

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import com.wildenglish.app.R
import java.util.Locale

/** Interface languages offered in Settings. AUTO follows the device locale. */
enum class AppLanguage(val tag: String, val labelRes: Int) {
    AUTO("", R.string.language_auto),
    ZH("zh-CN", R.string.language_zh),
    EN("en", R.string.language_en),
    JA("ja-JP", R.string.language_ja);

    companion object {
        fun fromTag(tag: String?): AppLanguage =
            entries.firstOrNull { it.tag.equals(tag, ignoreCase = true) } ?: AUTO
    }
}

/**
 * Keeps the chosen interface language and applies it to every context in the app.
 *
 * Every Activity (and the Application) wraps its base context with the resolved
 * [Configuration]. The platform LocaleManager is deliberately NOT used: changing it
 * recreates the running Activity automatically, which used to hang the app while the
 * language screen was still compositing. The caller now just finishes the Activity and
 * the user reopens the app to see the new language.
 */
object LocaleHelper {
    private const val PREFS = "language_prefs"
    private const val KEY = "app_language"

    // Do NOT use context.applicationContext here: it is null while Application.attachBaseContext
    // is running, and that is exactly where wrap() is first called.
    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun current(context: Context): AppLanguage =
        AppLanguage.fromTag(prefs(context).getString(KEY, AppLanguage.AUTO.tag))

    fun set(context: Context, language: AppLanguage) {
        prefs(context).edit().putString(KEY, language.tag).apply()
    }

    /** Context whose resources use the effective (user-selected or device) locale. */
    fun wrap(context: Context): Context {
        val language = current(context)
        val deviceLocales = context.resources.configuration.locales
        val locale = if (language == AppLanguage.AUTO) {
            if (deviceLocales.isEmpty()) Locale.getDefault() else deviceLocales[0]
        } else {
            Locale.forLanguageTag(language.tag)
        }
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return context.createConfigurationContext(config)
    }

    fun getString(context: Context, id: Int, vararg args: Any): String =
        wrap(context).getString(id, *args)
}

/** Resource access for non-Compose code (errors, notifications, services). */
object Strings {
    private lateinit var app: Context

    fun init(context: Context) {
        app = context.applicationContext
    }

    fun get(id: Int): String = LocaleHelper.getString(app, id)

    fun get(id: Int, vararg args: Any): String = LocaleHelper.getString(app, id, *args)
}
