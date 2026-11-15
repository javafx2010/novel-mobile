package com.wildenglish.app.audiobook.data

import android.content.Context

/**
 * Remembers, for every book downloaded from the market, which backend novel it came from and the
 * backend `updatedAt` at download time. The market sync compares this against the backend's current
 * `updatedAt`: a newer value means the book was updated on the backend and should be re-downloaded.
 *
 * Key = backend novel id. Value = `updatedAt` + local book id.
 */
object MarketSyncStore {

    private const val PREFS = "market_sync"
    private const val SEP = '\u0001'

    data class Record(val updatedAt: String, val localId: String)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(context: Context, marketId: Long): Record? {
        val raw = prefs(context).getString(key(marketId), null) ?: return null
        val parts = raw.split(SEP, limit = 2)
        if (parts.size != 2) return null
        return Record(parts[0], parts[1])
    }

    fun put(context: Context, marketId: Long, updatedAt: String, localId: String) {
        prefs(context).edit().putString(key(marketId), "$updatedAt$SEP$localId").apply()
    }

    fun remove(context: Context, marketId: Long) {
        prefs(context).edit().remove(key(marketId)).apply()
    }

    /** Every recorded market book, keyed by backend novel id. */
    fun entries(context: Context): Map<Long, Record> =
        prefs(context).all.mapNotNull { (key, value) ->
            val id = key.removePrefix("m_").toLongOrNull() ?: return@mapNotNull null
            val raw = value as? String ?: return@mapNotNull null
            val parts = raw.split(SEP, limit = 2)
            if (parts.size != 2) null else id to Record(parts[0], parts[1])
        }.toMap()

    private fun key(id: Long) = "m_$id"
}
