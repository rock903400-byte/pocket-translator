package com.translator.pocket.model

import java.util.concurrent.atomic.AtomicLong

/**
 * 訊息識別碼。
 * 舊版用 System.currentTimeMillis() 當預設值，同一毫秒發出的兩則訊息會撞號，
 * 而即時字幕的就地更新完全依賴這個 id 的唯一性。
 */
internal object MessageIds {
    private val seq = AtomicLong(0)
    fun next(): Long = seq.incrementAndGet()
}

enum class TranslationMode {
    ONE_WAY, // 單向即時同傳口譯 (聽外語 -> 耳機播中文)
    TWO_WAY  // 雙向交談模式 (我說中文對方聽外語，對方說外語我聽中文)
}

enum class EngineType {
    GEMINI_LIVE, // Gemini Multimodal Live 真人雙向即時口譯 (Audio-to-Audio)
    CLOUD_AI,    // Groq Whisper + Llama 高速引擎 (0.5s 延遲)
    BUILTIN      // 免費內建模式 (Google 語音辨識 + ML Kit)
}

enum class AudioOutputTarget {
    AUTO_HEADPHONES, // 🎧 耳機優先 (藍牙/有線耳機連線時自動優先)
    EARPIECE,        // 📞 貼耳聽筒私密通話模式 (頂部聽筒 + 距離感測滅屏)
    SPEAKER,         // 📢 外放揚聲器擴音模式 (底部喇叭 + 防回授自靜音)
    MUTE             // 🔕 靜音純字幕模式 (完全不發聲)
}

data class LanguageOption(
    val code: String,       // 例如: "ja", "en", "ko", "zh-TW"
    val displayName: String,// 例如: "日語 (日本語)", "英語 (English)"
    val whisperCode: String // Whisper API 語言代碼: "ja", "en", "ko", "zh"
) {
    override fun toString(): String = displayName
}

data class TranslationMessage(
    val id: Long = MessageIds.next(),
    val originalText: String,
    val sourceLangName: String,
    val translatedText: String,
    val targetLangName: String,
    val timestamp: Long = System.currentTimeMillis(),
    /** 譯文仍為暫定版本（例如繁中尚未由線上通道升級），之後會以同一個 id 覆蓋。 */
    val isProvisional: Boolean = false
)

/**
 * 進行中、尚未定案的字幕，顯示在畫面底部那張固定的卡片上。
 * 走獨立的 StateFlow 而非 messageFlow：它每秒更新數次，需要的是「永遠只看到最新狀態」，
 * 而不是一個會漏幀或回放陳舊資料的 SharedFlow。
 */
data class InterimSubtitle(
    val utteranceId: Long,
    val sourceText: String,
    val translatedText: String,
    val sourceLangName: String,
    val targetLangName: String
)

data class TranslationResult(
    val originalText: String,
    val translatedText: String,
    val isSuccess: Boolean,
    val errorMessage: String? = null
)
