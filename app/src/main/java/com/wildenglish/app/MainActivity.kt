package com.wildenglish.app

import android.Manifest
import android.app.KeyguardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.IntentCompat
import com.wildenglish.app.audiobook.AudioBookViewModel
import com.wildenglish.app.audiobook.playback.PlaybackCenter
import com.wildenglish.app.audiobook.playback.VoicePrepCenter
import com.wildenglish.app.audiobook.ui.AudioBookApp
import com.wildenglish.app.audiobook.ui.LockDisplayActivity
import com.wildenglish.app.ui.theme.AudioBookTheme

class MainActivity : ComponentActivity() {

    /** 与 Compose 共用同一个 ViewModel，才能接收外部递进来的文件。 */
    private val viewModel: AudioBookViewModel by viewModels()

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
                AudioBookApp(viewModel)
            }
        }
        // 只在首次创建时处理（旋转/重建时 savedInstanceState 非空，避免重复导入）。
        if (savedInstanceState == null) handleIncoming(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncoming(intent)
    }

    /**
     * 其他应用通过「打开方式」或「分享」把文件递进来时，直接导入书架。
     *
     * 接受 EPUB（也兼容本应用的 .jpd）：按 MIME 与文件名判断，其他类型静默忽略，
     * 避免 octet-stream 过滤把无关文件也当成书。
     */
    private fun handleIncoming(intent: Intent?) {
        if (intent == null) return
        val uri: Uri = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(
                intent, Intent.EXTRA_STREAM, Uri::class.java
            )

            else -> null
        } ?: return
        // 「打开方式」是用户明确选择本应用，宽松一些；「分享」只认 epub/jpd。
        if (!looksLikeBook(uri, intent.type, lenient = intent.action == Intent.ACTION_VIEW)) return
        viewModel.importPackage(uri)
    }

    private fun looksLikeBook(uri: Uri, type: String?, lenient: Boolean): Boolean {
        val mime = type ?: runCatching { contentResolver.getType(uri) }.getOrNull()
        if (mime == "application/epub+zip") return true
        val name = (displayName(uri) ?: uri.lastPathSegment.orEmpty()).lowercase()
        if (name.endsWith(".epub") || name.endsWith(".jpd")) return true
        if (!lenient) return false
        return mime == "application/zip" || mime == "application/octet-stream"
    }

    private fun displayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()

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
