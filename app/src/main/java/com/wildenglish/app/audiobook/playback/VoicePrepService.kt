package com.wildenglish.app.audiobook.playback

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.wildenglish.app.MainActivity
import com.wildenglish.app.R
import com.wildenglish.app.i18n.Strings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Resident foreground service ("托盘").
 *
 * It keeps the process alive so [VoicePrepCenter] can dub books even after the
 * app has been sent to the background, and shows a persistent, low-priority
 * notification that doubles as the tray entry: tap to return to the app, or use
 * the action to stop dubbing.
 */
class VoicePrepService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        VoicePrepCenter.init(application as Application)
        createChannel()
        try {
            val notification = buildNotification(VoicePrepCenter.status.value)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIF_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIF_ID, notification)
            }
        } catch (e: Exception) {
            // Foreground-service start can be disallowed on newer Android when
            // triggered from a background context; fail gracefully.
            stopSelf()
            return
        }
        serviceScope.launch {
            VoicePrepCenter.status
                .distinctUntilChanged { old, new -> notificationKey(old) == notificationKey(new) }
                .collect { st ->
                    NotificationManagerCompat.from(this@VoicePrepService)
                        .notify(NOTIF_ID, buildNotification(st))
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            VoicePrepCenter.stopAll()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        // (Re)start work, including after a START_STICKY restart where the
        // in-memory queue was lost.
        VoicePrepCenter.refresh()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.voice_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.voice_channel_desc)
                setSound(null, null)
                enableVibration(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(st: VoicePrepCenter.Status): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP,
            ),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, VoicePrepService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val working = st.isWorking && st.activeBookTitle != null
        val text = if (working) {
            "《${st.activeBookTitle}》${st.done}/${st.total}" +
                    if (st.pendingCount > 1) {
                        Strings.get(R.string.voice_pending, st.pendingCount - 1)
                    } else {
                        ""
                    }
        } else {
            getString(R.string.voice_notif_idle)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_voice)
            .setContentTitle(getString(R.string.voice_notif_title))
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .apply {
                if (working && st.total > 0) {
                    setProgress(st.total, st.done.coerceIn(0, st.total), false)
                }
            }
            .addAction(android.R.drawable.ic_delete, getString(R.string.voice_notif_stop), stop)
            .build()
    }

    /** Throttle notification rebuilds to the integer percent + book/state changes. */
    private fun notificationKey(st: VoicePrepCenter.Status): String {
        val pct = if (st.total > 0) st.done * 100 / st.total else 0
        return "${st.isWorking}|${st.activeBookTitle}|$pct|${st.pendingCount}"
    }

    companion object {
        const val ACTION_STOP = "com.wildenglish.app.action.STOP_VOICE_PREP"
        private const val CHANNEL_ID = "voice_prep"
        private const val NOTIF_ID = 1001
    }
}
