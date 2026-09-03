package com.translator.pocket.model

import android.content.Context
import android.content.SharedPreferences

class AppSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("pocket_translator_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_ENGINE_TYPE = "engine_type"
        private const val KEY_GROQ_API_KEY = "groq_api_key"
        private const val KEY_GEMINI_API_KEY = "gemini_api_key"
        private const val KEY_TTS_SPEED = "tts_speed"
        private const val KEY_VAD_SENSITIVITY = "vad_sensitivity"
        private const val KEY_SOURCE_LANG_INDEX = "source_lang_index"
        private const val KEY_TARGET_LANG_INDEX = "target_lang_index"
        private const val KEY_MODE = "translation_mode"
        private const val KEY_GEMINI_LIVE_VOICE = "gemini_live_voice"
        private const val KEY_GEMINI_MODEL = "gemini_model_name"
        private const val KEY_AUDIO_OUTPUT = "audio_output_preference"
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

    var geminiLiveVoice: String
        get() = prefs.getString(KEY_GEMINI_LIVE_VOICE, "Puck") ?: "Puck"
        set(value) = prefs.edit().putString(KEY_GEMINI_LIVE_VOICE, value).apply()

    var geminiModelName: String
        get() = prefs.getString(KEY_GEMINI_MODEL, "gemini-3.5-flash") ?: "gemini-3.5-flash"
        set(value) = prefs.edit().putString(KEY_GEMINI_MODEL, value.trim()).apply()

    var engineType: EngineType
        get() {
            val name = prefs.getString(KEY_ENGINE_TYPE, EngineType.CLOUD_AI.name)
            return try {
                EngineType.valueOf(name ?: EngineType.CLOUD_AI.name)
            } catch (e: Exception) {
                EngineType.CLOUD_AI
            }
        }
        set(value) = prefs.edit().putString(KEY_ENGINE_TYPE, value.name).apply()

    var groqApiKey: String
        get() = prefs.getString(KEY_GROQ_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GROQ_API_KEY, value.trim()).apply()

    var geminiApiKey: String
        get() = prefs.getString(KEY_GEMINI_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GEMINI_API_KEY, value.trim()).apply()

    var ttsSpeed: Float
        get() = prefs.getFloat(KEY_TTS_SPEED, 1.15f) // 預設 1.15x 微快，確保耳機能跟上口譯
        set(value) = prefs.edit().putFloat(KEY_TTS_SPEED, value).apply()

    var vadSensitivity: Int // 1 (靈敏) ~ 10 (遲鈍)
        get() = prefs.getInt(KEY_VAD_SENSITIVITY, 5)
        set(value) = prefs.edit().putInt(KEY_VAD_SENSITIVITY, value).apply()

    var sourceLangIndex: Int
        get() = prefs.getInt(KEY_SOURCE_LANG_INDEX, 0) // 預設日語
        set(value) = prefs.edit().putInt(KEY_SOURCE_LANG_INDEX, value).apply()

    var targetLangIndex: Int
        get() = prefs.getInt(KEY_TARGET_LANG_INDEX, 0) // 預設繁體中文
        set(value) = prefs.edit().putInt(KEY_TARGET_LANG_INDEX, value).apply()

    var translationMode: TranslationMode
        get() {
            val name = prefs.getString(KEY_MODE, TranslationMode.ONE_WAY.name)
            return try {
                TranslationMode.valueOf(name ?: TranslationMode.ONE_WAY.name)
            } catch (e: Exception) {
                TranslationMode.ONE_WAY
            }
        }
        set(value) = prefs.edit().putString(KEY_MODE, value.name).apply()

    // 常用語言清單
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
