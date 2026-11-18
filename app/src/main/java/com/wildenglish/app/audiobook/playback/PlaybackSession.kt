package com.wildenglish.app.audiobook.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationManagerCompat
import com.wildenglish.app.MainActivity
import com.wildenglish.app.R
import com.wildenglish.app.audiobook.ui.LockDisplayActivity
import com.wildenglish.app.i18n.Strings
import java.io.File

/**
 * 锁屏 / 息屏（AOD）的播放操作界面。
 *
 * A framework [MediaSession] plus a [Notification.MediaStyle] notification. The system shows it on
 * the lock screen and, on devices whose always-on display supports media, while the screen is off,
 * with 上一句 / 播放暂停 / 下一句. Notification buttons and hardware media keys are routed back into
 * the app via [PlaybackActionReceiver] → [Listener].
 *
 * The notification is public (shows content on the lock screen) and ongoing while playing.
 */
object PlaybackSession {

    const val ACTION_PLAY = "com.wildenglish.app.action.MEDIA_PLAY"
    const val ACTION_PAUSE = "com.wildenglish.app.action.MEDIA_PAUSE"
    const val ACTION_PREV = "com.wildenglish.app.action.MEDIA_PREV"
    const val ACTION_NEXT = "com.wildenglish.app.action.MEDIA_NEXT"

    /** What the lock-screen controls call back into. */
    interface Listener {
        fun onPlay()
        fun onPause()
        fun onPrevious()
        fun onNext()
    }

    const val CHANNEL_ID = "playback"
    const val NOTIFICATION_ID = 1002

    @Volatile
    private var initialized = false

    @Volatile
    private var session: MediaSession? = null

    @Volatile
    private var listener: Listener? = null

    @Volatile
    private var lastSignature: String? = null

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val app = context.applicationContext
            runCatching {
                createChannel(app)
                val s = MediaSession(app, "wildenglish-playback")
                s.setCallback(object : MediaSession.Callback() {
                    override fun onPlay() {
                        listener?.onPlay()
                    }

                    override fun onPause() {
                        listener?.onPause()
                    }

                    override fun onSkipToNext() {
                        listener?.onNext()
                    }

                    override fun onSkipToPrevious() {
                        listener?.onPrevious()
                    }
                }, Handler(Looper.getMainLooper()))
                s.setFlags(
                    MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                            MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
                )
                s.setSessionActivity(openApp(app))
                s.isActive = true
                session = s
            }
            initialized = true
        }
    }

    fun setListener(value: Listener?) {
        listener = value
    }

    /** Called from [PlaybackActionReceiver] for a notification action. */
    fun onAction(action: String?) {
        when (action) {
            ACTION_PLAY -> listener?.onPlay()
            ACTION_PAUSE -> listener?.onPause()
            ACTION_PREV -> listener?.onPrevious()
            ACTION_NEXT -> listener?.onNext()
        }
    }

    /**
     * Updates the lock-screen controls.
     *
     * @param hasPlayer false hides the notification and deactivates the session (no book loaded)
     */
    fun update(
        context: Context,
        title: String,
        subtitle: String,
        isPlaying: Boolean,
        hasPlayer: Boolean,
        coverFile: File? = null,
        sentence: String = "",
    ) {
        init(context)
        val app = context.applicationContext
        val current = session
        if (!hasPlayer || current == null) {
            runCatching { NotificationManagerCompat.from(app).cancel(NOTIFICATION_ID) }
            runCatching { current?.isActive = false }
            lastSignature = null
            return
        }

        val displaySubtitle = sentence.ifBlank { subtitle }
        val signature = "$title|$displaySubtitle|$isPlaying|${coverFile?.path}"
        if (signature == lastSignature) return
        lastSignature = signature

        runCatching {
            if (!current.isActive) current.isActive = true
            val art = decodeCover(coverFile)
            val metadata = MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, displaySubtitle)
                .putString(
                    MediaMetadata.METADATA_KEY_ALBUM,
                    subtitle.ifBlank { Strings.get(R.string.audiobook) },
                )
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, displaySubtitle)
            if (art != null) {
                metadata.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art)
                metadata.putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, art)
            }
            current.setMetadata(metadata.build())
            current.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(
                        PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                                PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
                                PlaybackState.ACTION_SKIP_TO_PREVIOUS
                    )
                    .setState(
                        if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                        PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                        if (isPlaying) 1f else 0f,
                    )
                    .build()
            )
            NotificationManagerCompat.from(app)
                .notify(
                    NOTIFICATION_ID,
                    buildNotification(app, current, title, displaySubtitle, isPlaying, art)
                )
        }
    }

    fun buildNotification(
        app: Context,
        mediaSession: MediaSession,
        title: String,
        subtitle: String,
        isPlaying: Boolean,
        art: Bitmap? = null,
    ): Notification {
        val builder = Notification.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_voice)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setContentIntent(openApp(app))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setFullScreenIntent(openLockDisplay(app, ambient = false), false)
        if (art != null) builder.setLargeIcon(art)
        builder.addAction(
            action(
                app,
                R.drawable.ic_media_prev,
                Strings.get(R.string.previous_sentence),
                ACTION_PREV
            )
        )
        if (isPlaying) {
            builder.addAction(
                action(app, R.drawable.ic_media_pause, Strings.get(R.string.pause), ACTION_PAUSE)
            )
        } else {
            builder.addAction(
                action(app, R.drawable.ic_media_play, Strings.get(R.string.play), ACTION_PLAY)
            )
        }
        builder.addAction(
            action(app, R.drawable.ic_media_next, Strings.get(R.string.next_sentence), ACTION_NEXT)
        )
        builder.setStyle(
            Notification.MediaStyle()
                .setMediaSession(mediaSession.sessionToken)
                .setShowActionsInCompactView(0, 1, 2)
        )
        return builder.build()
    }

    fun sessionToken(): MediaSession.Token? = session?.sessionToken

    fun currentNotification(
        app: Context,
        isPlaying: Boolean,
        title: String,
        subtitle: String,
        art: Bitmap?
    ): Notification? {
        val current = session ?: return null
        return buildNotification(app, current, title, subtitle, isPlaying, art)
    }

    fun decodeCover(file: File?): Bitmap? {
        if (file == null || !file.isFile) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val max = 512
            var sample = 1
            val largest = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
            while (largest / sample > max) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeFile(file.absolutePath, opts)
        }.getOrNull()
    }

    private fun action(
        app: Context,
        iconRes: Int,
        label: String,
        action: String
    ): Notification.Action =
        Notification.Action.Builder(
            Icon.createWithResource(app, iconRes),
            label,
            broadcast(app, action),
        ).build()

    private fun broadcast(app: Context, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            app,
            action.hashCode(),
            Intent(app, PlaybackActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun openApp(app: Context): PendingIntent =
        PendingIntent.getActivity(
            app,
            0,
            Intent(app, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            ),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun openLockDisplay(app: Context, ambient: Boolean): PendingIntent =
        PendingIntent.getActivity(
            app,
            if (ambient) 2 else 1,
            LockDisplayActivity.intent(app, ambient).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_USER_ACTION
            ),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun createChannel(app: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            Strings.get(R.string.lock_title),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = Strings.get(R.string.lock_controls_desc)
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        app.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
