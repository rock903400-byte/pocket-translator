package com.translator.pocket.model

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

class AppSettings(context: Context) {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("pocket_translator_prefs", Context.MODE_PRIVATE)

    /**
     * 機敏 Key 專用加密存儲。建構失敗（單元測試 / 極舊裝置）時降級為獨立明文檔，
     * 功能不受影響，僅安全等級下降，並打 warn 供排查。
     */
    private val secretPrefs: SharedPreferences by lazy {
        try {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedSharedPreferences.create(
                "pocket_translator_secret",
                masterKeyAlias,
                appContext,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            ).also { migratePlaintextKeyIfNeeded(it) }
        } catch (e: Exception) {
            Log.w(TAG, "加密存儲不可用，降級為獨立明文檔", e)
            appContext.getSharedPreferences("pocket_translator_secret_fallback", Context.MODE_PRIVATE)
        }
    }

    companion object {
        private const val TAG = "AppSettings"
        private const val KEY_GEMINI_API_KEY = "gemini_api_key"
        private const val KEY_TTS_SPEED = "tts_speed"
        private const val KEY_SOURCE_LANG_INDEX = "source_lang_index"
        private const val KEY_TARGET_LANG_INDEX = "target_lang_index"
        private const val KEY_GEMINI_LIVE_MODEL = "gemini_live_model_name"
        private const val KEY_AUDIO_OUTPUT = "audio_output_preference"

        /** 依 Rate Limit 頁面實測真實可用的 Live Translate 模型 */
        const val DEFAULT_GEMINI_LIVE_MODEL = "gemini-3.5-live-translate-preview"

        /** 將使用者輸入的顯示名正規化為真實模型 ID（顯示名即為此模型） */
        fun normalizeLiveModel(raw: String): String {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return DEFAULT_GEMINI_LIVE_MODEL
            val lower = trimmed.lowercase()
            // 顯示名「Gemini 3.5 Live Translate」直接對應預覽模型
            if (lower == "gemini 3.5 live translate" ||
                lower == "gemini 3.5 live translate preview" ||
                lower == "gemini-3.5-live-translate" ||
                lower == "models/gemini-3.5-live-translate"
            ) {
                return DEFAULT_GEMINI_LIVE_MODEL
            }
            // 舊幻覺模型一律遷移至正確的 preview
            if (lower.contains("2.0-flash-live") ||
                lower == "gemini-2.5-flash-native-audio-latest" ||
                lower == "models/gemini-2.5-flash-native-audio-latest"
            ) {
                return DEFAULT_GEMINI_LIVE_MODEL
            }
            if (trimmed.contains(" ")) return DEFAULT_GEMINI_LIVE_MODEL
            return trimmed
        }
    }

    /**
     * 一次性遷移：舊版 Key 明文存在 pocket_translator_prefs，
     * 首次讀到加密存儲時搬過去並清除舊值，之後只走加密。
     */
    private fun migratePlaintextKeyIfNeeded(encrypted: SharedPreferences) {
        try {
            if (encrypted.contains(KEY_GEMINI_API_KEY)) return
            val legacy = prefs.getString(KEY_GEMINI_API_KEY, "").orEmpty()
            if (legacy.isNotBlank()) {
                encrypted.edit().putString(KEY_GEMINI_API_KEY, legacy.trim()).apply()
                prefs.edit().remove(KEY_GEMINI_API_KEY).apply()
                Log.d(TAG, "已將明文 API Key 遷移至加密存儲並清除舊值")
            }
        } catch (e: Exception) {
            Log.w(TAG, "遷移舊 Key 失敗", e)
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
        get() {
            // 加密為主；舊版明文殘留也順手讀到，避免升級後要重貼
            val v = try {
                secretPrefs.getString(KEY_GEMINI_API_KEY, "").orEmpty()
            } catch (e: Exception) {
                ""
            }
            if (v.isNotBlank()) return v
            return prefs.getString(KEY_GEMINI_API_KEY, "") ?: ""
        }
        set(value) {
            try {
                secretPrefs.edit().putString(KEY_GEMINI_API_KEY, value.trim()).apply()
                // 寫成功就清除舊明文殘留
                if (prefs.contains(KEY_GEMINI_API_KEY)) {
                    prefs.edit().remove(KEY_GEMINI_API_KEY).apply()
                }
            } catch (e: Exception) {
                Log.w(TAG, "加密寫入失敗，改寫舊位置", e)
                prefs.edit().putString(KEY_GEMINI_API_KEY, value.trim()).apply()
            }
        }

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
