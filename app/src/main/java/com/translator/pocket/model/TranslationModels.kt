package com.translator.pocket.model

enum class TranslationMode {
    ONE_WAY, // 單向即時同傳口譯 (聽外語 -> 耳機播中文)
    TWO_WAY  // 雙向交談模式 (我說中文對方聽外語，對方說外語我聽中文)
}

enum class EngineType {
    CLOUD_AI, // Groq Whisper + Gemini/Llama 高速引擎 (0.5s 延遲)
    BUILTIN   // 免費內建模式 (Google 語音辨識 + ML Kit)
}

data class LanguageOption(
    val code: String,       // 例如: "ja", "en", "ko", "zh-TW"
    val displayName: String,// 例如: "日語 (日本語)", "英語 (English)"
    val whisperCode: String // Whisper API 語言代碼: "ja", "en", "ko", "zh"
) {
    override fun toString(): String = displayName
}

data class TranslationMessage(
    val id: Long = System.currentTimeMillis(),
    val originalText: String,
    val sourceLangName: String,
    val translatedText: String,
    val targetLangName: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class TranslationResult(
    val originalText: String,
    val translatedText: String,
    val isSuccess: Boolean,
    val errorMessage: String? = null
)
