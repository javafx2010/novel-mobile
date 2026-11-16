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
        AppDatabase.init(context.applicationContext)
        _state.value = AppSettingsState(
            voice = VoiceConfig.byName(p.getString(KEY_VOICE, null)),
            storageMode = runCatching {
                VoiceStorageMode.valueOf(p.getString(KEY_STORAGE_MODE, null).orEmpty())
            }.getOrDefault(VoiceStorageMode.AUTO),
            customTreeUri = p.getString(KEY_TREE_URI, null),
            showSentence = p.getBoolean(KEY_SHOW_SENTENCE, true),
        )
        // 设置全量落库。
        runCatching {
            val s = _state.value
            persistToDb(KEY_VOICE, s.voice.name)
            persistToDb(KEY_STORAGE_MODE, s.storageMode.name)
            persistToDb(KEY_TREE_URI, s.customTreeUri.orEmpty())
            persistToDb(KEY_SHOW_SENTENCE, s.showSentence.toString())
        }
    }

    /** 双写：SharedPreferences + 数据库。 */
    private fun persist(key: String, value: String, bool: Boolean? = null, int: Int? = null) {
        val editor = prefs?.edit()
        when {
            bool != null -> editor?.putBoolean(key, bool)
            int != null -> editor?.putInt(key, int)
            else -> editor?.putString(key, value)
        }
        editor?.apply()
        persistToDb(key, value)
    }

    private fun persistToDb(key: String, value: String) {
        runCatching { AppDatabase.putSetting(key, value) }
    }

    fun voice(): VoiceConfig = _state.value.voice

    fun setVoice(name: String) {
        val voice = VoiceConfig.byName(name)
        if (voice == _state.value.voice) return
        _state.value = _state.value.copy(voice = voice)
        persist(KEY_VOICE, voice.name)
    }

    fun setStorageMode(mode: VoiceStorageMode) {
        if (mode == _state.value.storageMode) return
        _state.value = _state.value.copy(storageMode = mode)
        persist(KEY_STORAGE_MODE, mode.name)
        VoiceStorage.invalidate()
    }

    fun setCustomTreeUri(uri: String?) {
        _state.value = _state.value.copy(customTreeUri = uri)
        persist(KEY_TREE_URI, uri.orEmpty())
        VoiceStorage.invalidate()
    }

    /** 是否在播放页展示当前朗读句（关闭后播放页只保留封面与控件）。 */
    fun setShowSentence(show: Boolean) {
        if (show == _state.value.showSentence) return
        _state.value = _state.value.copy(showSentence = show)
        persist(KEY_SHOW_SENTENCE, show.toString(), bool = show)
    }

}
