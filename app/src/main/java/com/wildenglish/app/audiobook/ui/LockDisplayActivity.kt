package com.wildenglish.app.audiobook.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wildenglish.app.audiobook.playback.PlaybackCenter
import com.wildenglish.app.audiobook.playback.PlaybackSnapshot
import com.wildenglish.app.ui.theme.AudioBookTheme
import com.wildenglish.app.ui.theme.GlassIconButton
import com.wildenglish.app.ui.theme.GlassPanel
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Custom lock-screen and ambient (息屏) player.
 *
 * Shown when the phone is locked or the screen turns off during playback, so
 * the listener can play / pause / skip without unlocking. Ambient mode keeps
 * the panel visible at a very low brightness with a clock, like an always-on
 * display with transport controls.
 */
class LockDisplayActivity : ComponentActivity() {

    private var ambient: Boolean = false

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_USER_PRESENT -> finish()
                Intent.ACTION_SCREEN_ON -> {
                    if (ambient) applyMode(ambient = false)
                }

                Intent.ACTION_SCREEN_OFF -> {
                    if (PlaybackCenter.snapshot.value.isPlaying) applyMode(ambient = true)
                    else finish()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        enableEdgeToEdge()
        applyMode(intent.getBooleanExtra(EXTRA_AMBIENT, false))
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(unlockReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(unlockReceiver, filter)
        }
        setContent {
            val snap by PlaybackCenter.snapshot.collectAsStateWithLifecycle()
            var ambientState by remember { mutableStateOf(ambient) }
            LaunchedEffect(ambient) { ambientState = ambient }
            LaunchedEffect(snap.hasPlayer) {
                if (!snap.hasPlayer) finish()
            }
            AudioBookTheme(darkTheme = true) {
                LockDisplayScreen(
                    snapshot = snap,
                    ambient = ambientState,
                    onToggle = { PlaybackCenter.togglePlayPause() },
                    onPrevious = { PlaybackCenter.skipSentences(-1) },
                    onNext = { PlaybackCenter.skipSentences(1) },
                    onBrighten = { applyMode(ambient = false) },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyMode(intent.getBooleanExtra(EXTRA_AMBIENT, false))
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(unlockReceiver) }
        super.onDestroy()
    }

    private fun applyMode(ambient: Boolean) {
        this.ambient = ambient
        val params = window.attributes
        if (ambient) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            params.screenBrightness = AMBIENT_BRIGHTNESS
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        window.attributes = params
    }

    companion object {
        const val EXTRA_AMBIENT = "ambient"
        private const val AMBIENT_BRIGHTNESS = 0.04f

        fun intent(context: Context, ambient: Boolean): Intent =
            Intent(context, LockDisplayActivity::class.java)
                .putExtra(EXTRA_AMBIENT, ambient)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_NO_USER_ACTION,
                )
    }
}

@Composable
private fun LockDisplayScreen(
    snapshot: PlaybackSnapshot,
    ambient: Boolean,
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onBrighten: () -> Unit,
) {
    val bg by animateColorAsState(
        if (ambient) Color(0xFF000000) else Color(0xFF07090E),
        label = "lock-bg",
    )
    var now by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        val fmtTick = if (ambient) 15_000L else 30_000L
        while (true) {
            now = LocalTime.now()
            delay(fmtTick)
        }
    }
    val timeText = remember(now) { now.format(DateTimeFormatter.ofPattern("HH:mm")) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { if (ambient) onBrighten() },
            )
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 28.dp, vertical = 20.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                timeText,
                color = Color.White.copy(alpha = if (ambient) 0.92f else 0.78f),
                fontSize = if (ambient) 64.sp else 42.sp,
                fontWeight = FontWeight.Light,
                fontFamily = FontFamily.SansSerif,
                letterSpacing = 2.sp,
            )
            Text(
                if (ambient) "息屏播放" else "锁屏播放",
                color = Color.White.copy(alpha = 0.45f),
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.weight(1f))
            BookCover(
                coverFile = snapshot.coverFile,
                title = snapshot.title,
                modifier = Modifier
                    .width(if (ambient) 160.dp else 200.dp)
                    .height(if (ambient) 222.dp else 278.dp),
            )
            Spacer(Modifier.height(20.dp))
            Text(
                snapshot.title.ifBlank { "有声书" },
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Serif,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            if (snapshot.chapterTitle.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    snapshot.chapterTitle,
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(16.dp))
            GlassPanel(
                modifier = Modifier.fillMaxWidth(),
                dark = true,
                elevation = 8.dp,
            ) {
                Text(
                    snapshot.currentSentence.ifBlank { "准备朗读…" },
                    color = Color.White.copy(alpha = 0.92f),
                    fontSize = 16.sp,
                    lineHeight = 24.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassIconButton(
                    onClick = onPrevious,
                    modifier = Modifier.size(56.dp),
                    dark = true,
                ) {
                    Icon(
                        Icons.Filled.SkipPrevious,
                        contentDescription = "上一句",
                        modifier = Modifier.size(28.dp)
                    )
                }
                GlassIconButton(
                    onClick = onToggle,
                    modifier = Modifier.size(72.dp),
                    dark = true,
                ) {
                    Icon(
                        if (snapshot.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (snapshot.isPlaying) "暂停" else "播放",
                        modifier = Modifier.size(36.dp),
                    )
                }
                GlassIconButton(
                    onClick = onNext,
                    modifier = Modifier.size(56.dp),
                    dark = true,
                ) {
                    Icon(
                        Icons.Filled.SkipNext,
                        contentDescription = "下一句",
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
    }
}
