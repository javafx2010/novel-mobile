package com.wildenglish.app.audiobook.data

import android.content.Context
import java.net.URI

/**
 * Backend address of the book market, editable by the user in the market screen's settings dialog
 * and persisted in SharedPreferences. [MarketApi] builds every URL from [baseUrl].
 */
object ServerConfig {

    /** Default deployment address, also used by "恢复默认". */
    const val DEFAULT_BASE_URL = "http://43.173.26.117:8080"

    private const val PREFS = "server_prefs"
    private const val KEY = "market_base_url"

    @Volatile
    private var cached: String = DEFAULT_BASE_URL

    /** Loads the saved address once, from the application context. */
    fun init(context: Context) {
        val app = context.applicationContext
        val saved = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        cached = normalize(saved) ?: DEFAULT_BASE_URL
    }

    fun baseUrl(): String = cached

    /** Validates and persists a new address. Returns false when the input is not a usable http(s) URL. */
    fun set(context: Context, raw: String): Boolean {
        val value = normalize(raw) ?: return false
        cached = value
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, value).apply()
        return true
    }

    /**
     * Accepts `43.173.26.117:8080`, `http://…`, `https://…` or a host with a path prefix; returns the
     * normalized URL (scheme added, trailing slash removed) or null when invalid.
     */
    fun normalize(raw: String): String? {
        var value = raw.trim()
        if (value.isEmpty() || value.any { it.isWhitespace() }) return null
        if (!value.contains("://")) value = "http://$value"
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        if (uri.host.isNullOrBlank()) return null
        return value.trimEnd('/')
    }
}
