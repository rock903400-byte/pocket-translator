package com.translator.pocket.model

import android.content.Context
import android.content.SharedPreferences

class AppSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("pocket_translator_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_GEMINI_API_KEY = "gemini_api_key"
        private const val KEY_TTS_SPEED = "tts_speed"
        private const val KEY_SOURCE_LANG_INDEX = "source_lang_index"
        private const val KEY_TARGET_LANG_INDEX = "target_lang_index"
        private const val KEY_GEMINI_LIVE_MODEL = "gemini_live_model_name"
        private const val KEY_AUDIO_OUTPUT = "audio_output_preference"

        /** 實測可用的 Live 模型（此 Key 僅支援 bidi 的兩個模型） */
        const val DEFAULT_GEMINI_LIVE_MODEL = "gemini-2.5-flash-native-audio-latest"
        const val ALT_LIVE_MODEL = "gemini-3.5-transcribe-live"

        /** 將使用者輸入的顯示名/舊幻覺名稱正規化為真實模型 ID */
        fun normalizeLiveModel(raw: String): String {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return DEFAULT_GEMINI_LIVE_MODEL
            val lower = trimmed.lowercase()
            // 顯示名「Gemini 3.5 Live Translate」及任何含 3.5 的幻覺名稱 -> 映射至實測可用的 native-audio 模型
            if (lower == "gemini 3.5 live translate" ||
                lower.contains("gemini 3.5") ||
                lower.contains("3.5-live-translate") ||
                lower.contains("3.5 live translate") ||
                lower == "gemini-3.5-live-translate-preview" ||
                lower == "models/gemini-3.5-live-translate-preview" ||
                lower == "gemini-2.0-flash-live-preview-04-09" ||
                lower == "models/gemini-2.0-flash-live-preview-04-09"
            ) {
                return DEFAULT_GEMINI_LIVE_MODEL
            }
            if (trimmed.contains(" ")) return DEFAULT_GEMINI_LIVE_MODEL
            return trimmed
        }
    }

    var audioOutputPreference: AudioOutputTarget
        get() {
            val name = prefs.getString(KEY_AUDIO_OUTPUT, AudioOutputTarget.EARPIECE.name)
            return try {
                AudioOutputTarget.valueOf(name ?: AudioOutputTarget.EARPIECE.name)
            } catch (e: Exception) {
                AudioOutputTarget.EARPIECE
            }
        }
        set(value) = prefs.edit().putString(KEY_AUDIO_OUTPUT, value.name).apply()

    var geminiLiveModelName: String
        get() {
            val stored = prefs.getString(KEY_GEMINI_LIVE_MODEL, DEFAULT_GEMINI_LIVE_MODEL)
                ?.trim()?.ifEmpty { DEFAULT_GEMINI_LIVE_MODEL } ?: DEFAULT_GEMINI_LIVE_MODEL
            val normalized = normalizeLiveModel(stored)
            // 自動遷移：若儲存的是顯示名，寫回真實 ID
            if (normalized != stored) {
                prefs.edit().putString(KEY_GEMINI_LIVE_MODEL, normalized).apply()
            }
            return normalized
        }
        set(value) {
            val normalized = normalizeLiveModel(value)
            prefs.edit().putString(KEY_GEMINI_LIVE_MODEL, normalized).apply()
        }

    var geminiApiKey: String
        get() = prefs.getString(KEY_GEMINI_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GEMINI_API_KEY, value.trim()).apply()

    var ttsSpeed: Float
        get() = prefs.getFloat(KEY_TTS_SPEED, 1.15f)
        set(value) = prefs.edit().putFloat(KEY_TTS_SPEED, value).apply()

    var sourceLangIndex: Int
        get() = prefs.getInt(KEY_SOURCE_LANG_INDEX, 0)
        set(value) = prefs.edit().putInt(KEY_SOURCE_LANG_INDEX, value).apply()

    var targetLangIndex: Int
        get() = prefs.getInt(KEY_TARGET_LANG_INDEX, 3)
        set(value) = prefs.edit().putInt(KEY_TARGET_LANG_INDEX, value).apply()

    val supportedLanguages = listOf(
        LanguageOption("ja", "日語 (Japanese)", "ja"),
        LanguageOption("en", "英語 (English)", "en"),
        LanguageOption("ko", "韓語 (Korean)", "ko"),
        LanguageOption("zh-TW", "繁體中文 (Traditional Chinese)", "zh"),
        LanguageOption("de", "德語 (German)", "de"),
        LanguageOption("fr", "法語 (French)", "fr"),
        LanguageOption("es", "西班牙語 (Spanish)", "es"),
        LanguageOption("th", "泰語 (Thai)", "th"),
        LanguageOption("vi", "越南語 (Vietnamese)", "vi")
    )
}
