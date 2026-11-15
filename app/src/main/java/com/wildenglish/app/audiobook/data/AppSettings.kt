package com.wildenglish.app.audiobook.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where synthesized voice files (MP3) are written. */
enum class VoiceStorageMode {
    /** 自动：优先 SD 卡，不可用时退回应用内部存储。 */
    AUTO,

    /** 应用内部存储（卸载/清除数据时一起删除）。 */
    INTERNAL,

    /** 主存储上的应用专属目录（/sdcard/Android/data/…）。 */
    EXTERNAL,

    /** 用户通过系统文件选择器指定的任意目录。 */
    CUSTOM,
}

data class AppSettingsState(
    val voice: VoiceConfig = VoiceConfig.DEFAULT,
    val storageMode: VoiceStorageMode = VoiceStorageMode.AUTO,
    /** SAF tree uri of the folder picked for [VoiceStorageMode.CUSTOM]. */
    val customTreeUri: String? = null,
    /** 播放页是否展示「当前朗读句」卡片。 */
    val showSentence: Boolean = true,
)

/**
 * 全局设置：朗读音色 + 配音文件存储目录。
 *
 * 取值保存在 SharedPreferences 并对外暴露为 [state]，界面与后台配音都以它为准 —— 每本书都用
 * 同一个音色朗读，切换音色或存储目录后缓存目录里的旧音频会因音色/标记不匹配而重新生成。
 */
object AppSettings {

    private const val PREFS = "app_settings"
    private const val KEY_VOICE = "voice"
    private const val KEY_STORAGE_MODE = "storage_mode"
    private const val KEY_TREE_URI = "storage_tree_uri"
    private const val KEY_SHOW_SENTENCE = "show_sentence"

    private var prefs: SharedPreferences? = null

    private val _state = MutableStateFlow(AppSettingsState())
    val state: StateFlow<AppSettingsState> = _state.asStateFlow()

    fun init(context: Context) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        _state.value = AppSettingsState(
            voice = VoiceConfig.byName(p.getString(KEY_VOICE, null)),
            storageMode = runCatching {
                VoiceStorageMode.valueOf(p.getString(KEY_STORAGE_MODE, null).orEmpty())
            }.getOrDefault(VoiceStorageMode.AUTO),
            customTreeUri = p.getString(KEY_TREE_URI, null),
            showSentence = p.getBoolean(KEY_SHOW_SENTENCE, true),
        )
    }

    fun voice(): VoiceConfig = _state.value.voice

    fun setVoice(name: String) {
        val voice = VoiceConfig.byName(name)
        if (voice == _state.value.voice) return
        _state.value = _state.value.copy(voice = voice)
        prefs?.edit()?.putString(KEY_VOICE, voice.name)?.apply()
    }

    fun setStorageMode(mode: VoiceStorageMode) {
        if (mode == _state.value.storageMode) return
        _state.value = _state.value.copy(storageMode = mode)
        prefs?.edit()?.putString(KEY_STORAGE_MODE, mode.name)?.apply()
        VoiceStorage.invalidate()
    }

    fun setCustomTreeUri(uri: String?) {
        _state.value = _state.value.copy(customTreeUri = uri)
        prefs?.edit()?.putString(KEY_TREE_URI, uri)?.apply()
        VoiceStorage.invalidate()
    }

    /** 是否在播放页展示当前朗读句（关闭后播放页只保留封面与控件）。 */
    fun setShowSentence(show: Boolean) {
        if (show == _state.value.showSentence) return
        _state.value = _state.value.copy(showSentence = show)
        prefs?.edit()?.putBoolean(KEY_SHOW_SENTENCE, show)?.apply()
    }
}
