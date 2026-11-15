package com.wildenglish.app.audiobook.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Stable app-owned directory on the SD card, shared by the voice cache and the reading-progress
 * mirror, so an app **update / clear-data** does not lose either of them.
 *
 * The chosen path is pinned in SharedPreferences: [getExternalFilesDirs] can reorder its volumes
 * (or a removable SD card can be absent on one boot and present on the next), which used to make
 * the voice cache point at a different directory and look "lost". We also detect a volume that
 * already holds a voice cache, so even a cleared `app_storage` pref re-attaches to existing audio.
 *
 * App-specific external storage needs no permission, survives app updates and reboots, and is only
 * removed on uninstall / "clear data" (same as the internal filesDir). The progress mirror lives
 * beside the audio so both follow the same lifecycle.
 */
object AppStorage {

    const val VOICE_CACHE_DIR = "voice_cache"
    private const val PROGRESS_FILE = "progress.json"
    private const val PREFS = "app_storage"
    private const val KEY_ROOT = "sdcard_root"

    private val mirrorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mirrorChannel = Channel<Map<String, String>>(Channel.CONFLATED)
    private val mirrorStarted = AtomicBoolean(false)

    /** Resolves (and remembers) the SD-card directory, or null when no external volume is writable. */
    fun sdcardRoot(context: Context): File? {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        val saved = prefs.getString(KEY_ROOT, null)
        if (!saved.isNullOrBlank()) {
            val dir = File(saved)
            if ((dir.isDirectory || dir.mkdirs()) && dir.canWrite()) return dir
            // The remembered volume disappeared (card ejected / path changed): pick a new one.
            prefs.edit().remove(KEY_ROOT).apply()
        }

        val volumes: List<File> = runCatching { app.getExternalFilesDirs(null).filterNotNull() }
            .getOrDefault(emptyList())
        // [0] is the primary shared storage (/sdcard); [1..] are usually removable SD cards.
        // 1) Reuse the volume that already has our data (survives a cleared pref).
        // 2) Otherwise prefer a removable SD card ("优先放在 sdcard"), then the primary volume.
        val withCache = volumes.firstOrNull { File(it, VOICE_CACHE_DIR).isDirectory }
        val preferred = (volumes.drop(1) + volumes.take(1))
        for (dir in listOfNotNull(withCache) + preferred) {
            if ((dir.isDirectory || dir.mkdirs()) && dir.canWrite()) {
                prefs.edit().putString(KEY_ROOT, dir.absolutePath).apply()
                return dir
            }
        }
        return null
    }

    fun progressFile(context: Context): File? =
        sdcardRoot(context)?.let { File(it, PROGRESS_FILE) }

    /** Reads the mirrored progress snapshot; null when absent or unreadable. */
    fun readProgress(context: Context): Map<String, String>? {
        val file = progressFile(context) ?: return null
        if (!file.isFile) return null
        return runCatching {
            val obj = JSONObject(file.readText())
            val map = HashMap<String, String>()
            obj.keys().forEach { key -> map[key] = obj.getString(key) }
            map
        }.getOrNull()
    }

    /**
     * Queues a progress snapshot to be written to the SD card. Rapid successive saves (one per
     * sentence) are coalesced — only the latest snapshot is written — and I/O runs off the main
     * thread.
     */
    fun mirrorProgress(context: Context, snapshot: Map<String, String>) {
        if (mirrorStarted.compareAndSet(false, true)) {
            val app = context.applicationContext
            mirrorScope.launch {
                for (value in mirrorChannel) {
                    runCatching { writeProgress(app, value) }
                }
            }
        }
        mirrorChannel.trySend(snapshot)
    }

    private fun writeProgress(context: Context, snapshot: Map<String, String>) {
        val file = progressFile(context) ?: return
        val obj = JSONObject()
        snapshot.forEach { (key, value) -> obj.put(key, value) }
        val parent = file.parentFile ?: return
        if (!parent.isDirectory && !parent.mkdirs()) return
        val tmp = File(parent, file.name + ".tmp")
        tmp.writeText(obj.toString())
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }
}
