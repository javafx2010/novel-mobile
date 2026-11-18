package com.wildenglish.app

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
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
import com.wildenglish.app.audiobook.data.AudioFolderImporter
import com.wildenglish.app.audiobook.playback.PlaybackCenter
import com.wildenglish.app.audiobook.playback.VoicePrepCenter
import com.wildenglish.app.audiobook.ui.AudioBookApp
import com.wildenglish.app.audiobook.ui.LockDisplayActivity
import com.wildenglish.app.i18n.LocaleHelper
import com.wildenglish.app.ui.theme.AudioBookTheme

class MainActivity : ComponentActivity() {

    /** 与 Compose 共用同一个 ViewModel，才能接收外部递进来的文件。 */
    private val viewModel: AudioBookViewModel by viewModels()

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        VoicePrepCenter.init(application)
        PlaybackCenter.init(application)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // 首页只负责加载图书；配音扫描只在后台服务（VoicePrepService / BootReceiver）
        // 里进行，不在 Activity 启动路径上启动服务或扫描。
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
     * 接受 EPUB、.jpd，以及单个 / 多个 MP3（M4A/FLAC…）音频文件；按 MIME 与文件名判断，
     * 其他类型静默忽略，避免 octet-stream 过滤把无关文件也当成书。
     */
    private fun handleIncoming(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        val uris: List<Uri> = when (action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            )

            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(
                intent, Intent.EXTRA_STREAM, Uri::class.java
            ).orEmpty()

            else -> emptyList()
        }
        if (uris.isEmpty()) return
        // 「打开方式」是用户明确选择本应用，宽松一些；「分享」只认 epub/jpd/音频。
        val lenient = action == Intent.ACTION_VIEW
        val accepted = uris.filter { uri ->
            looksLikeBook(uri, if (uris.size == 1) intent.type else null, lenient)
        }
        if (accepted.isEmpty()) return
        when {
            accepted.size == 1 -> viewModel.importPackage(accepted.first())
            accepted.all { isAudioUri(it) } -> viewModel.importAudioFiles(accepted)
            // 混合分享（如多个 EPUB）：逐个导入。
            else -> accepted.forEach { viewModel.importPackage(it) }
        }
    }

    private fun isAudioUri(uri: Uri): Boolean {
        val mime = runCatching { contentResolver.getType(uri) }.getOrNull()
        return AudioFolderImporter.isAudioFile(displayName(uri) ?: uri.lastPathSegment, mime)
    }

    private fun looksLikeBook(uri: Uri, type: String?, lenient: Boolean): Boolean {
        val mime = type ?: runCatching { contentResolver.getType(uri) }.getOrNull()
        if (mime == "application/epub+zip") return true
        val name = (displayName(uri) ?: uri.lastPathSegment.orEmpty()).lowercase()
        if (name.endsWith(".epub") || name.endsWith(".jpd")) return true
        // 单个 MP3 / M4A / FLAC…：直接作为一本有声书导入。
        if (AudioFolderImporter.isAudioFile(name, mime)) return true
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
