package com.wildenglish.app.audiobook.playback

import android.app.KeyguardManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.wildenglish.app.audiobook.ui.LockDisplayActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Media playback foreground service.
 *
 * Keeps speech alive after the Activity is gone, posts the MediaStyle
 * notification used by the system lock screen / always-on display, and
 * opens the app's own lock and ambient (息屏) pages when the screen turns off.
 */
class PlaybackService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var receiverRegistered = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val snap = PlaybackCenter.snapshot.value
            if (!snap.hasPlayer) return
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> showDisplay(ambient = true)
                Intent.ACTION_SCREEN_ON -> {
                    val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
                    if (locked) showDisplay(ambient = false)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        PlaybackCenter.init(application)
        promoteForeground()
        serviceScope.launch {
            PlaybackCenter.snapshot
                .map { it.hasPlayer to it.currentSentenceIndex }
                .distinctUntilChanged()
                .collect { (hasPlayer, _) ->
                    if (!hasPlayer) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        promoteForeground()
                    }
                }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenReceiver, filter)
        }
        receiverRegistered = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteForeground()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (receiverRegistered) {
            runCatching { unregisterReceiver(screenReceiver) }
            receiverRegistered = false
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun promoteForeground() {
        val snap = PlaybackCenter.snapshot.value
        if (!snap.hasPlayer) return
        PlaybackSession.init(this)
        val art = PlaybackSession.decodeCover(snap.coverFile)
        val subtitle = snap.currentSentence.ifBlank { snap.chapterTitle }
        val notification = PlaybackSession.currentNotification(
            applicationContext,
            snap.isPlaying,
            snap.title,
            subtitle,
            art,
        ) ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    PlaybackSession.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
            } else {
                startForeground(PlaybackSession.NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // Starting a media FGS can be rejected from a background context.
        }
    }

    private fun showDisplay(ambient: Boolean) {
        val snap = PlaybackCenter.snapshot.value
        if (!snap.hasPlayer) return
        // Ambient (息屏) page only while playing, so a paused book can truly sleep.
        if (ambient && !snap.isPlaying) return
        val interactive = getSystemService(PowerManager::class.java).isInteractive
        if (ambient && interactive) return
        runCatching {
            startActivity(LockDisplayActivity.intent(this, ambient))
        }
    }
}
