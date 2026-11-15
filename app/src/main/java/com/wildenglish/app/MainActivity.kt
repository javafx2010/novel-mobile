package com.wildenglish.app

import android.Manifest
import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.wildenglish.app.audiobook.playback.PlaybackCenter
import com.wildenglish.app.audiobook.playback.VoicePrepCenter
import com.wildenglish.app.audiobook.ui.AudioBookApp
import com.wildenglish.app.audiobook.ui.LockDisplayActivity
import com.wildenglish.app.ui.theme.AudioBookTheme

class MainActivity : ComponentActivity() {

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        VoicePrepCenter.init(application)
        PlaybackCenter.init(application)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // Start the resident service + auto-resume dubbing while the app is in
        // the foreground (required for a foreground-service start on Android 12+).
        VoicePrepCenter.onAppStart()
        setContent {
            AudioBookTheme {
                AudioBookApp()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (!PlaybackCenter.snapshot.value.hasPlayer) return
        val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        val interactive = getSystemService(PowerManager::class.java).isInteractive
        if (!interactive || locked) {
            startActivity(LockDisplayActivity.intent(this, ambient = !interactive))
        }
    }
}
