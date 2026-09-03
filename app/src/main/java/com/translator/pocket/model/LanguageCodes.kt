package com.translator.pocket.model

/**
 * App 內部語言代碼與各家 API 代碼的唯一轉換來源。
 *
 * 刻意保持為純 Kotlin（零 Android / ML Kit import），以便用一般 JUnit 測試覆蓋。
 * 同一個語言在不同 API 需要不同寫法，例如繁體中文：
 * - 語音辨識 (SpeechRecognizer) 要 BCP-47 的 "zh-TW"
 * - ML Kit 端上翻譯只有 "zh"（簡體），沒有繁體模型
 * - Google translate_a 端點的 tl 參數要 "zh-TW"
 * - TextToSpeech 要 "zh-TW"
 */
object LanguageCodes {

    /** App 內部代碼（等同 AppSettings.supportedLanguages 的 code 欄位） */
    private val BCP47 = mapOf(
        "ja" to "ja-JP",
        "en" to "en-US",
        "ko" to "ko-KR",
        "zh-tw" to "zh-TW",
        "zh" to "zh-CN",
        "de" to "de-DE",
        "fr" to "fr-FR",
        "es" to "es-ES",
        "th" to "th-TH",
        "vi" to "vi-VN"
    )

    /**
     * ML Kit 端上翻譯支援的語言標籤。
     * 注意 ML Kit 只有 "zh"（簡體），繁體必須另外處理，見 [toGtxTag]。
     */
    private val ML_KIT = mapOf(
        "ja" to "ja",
        "en" to "en",
        "ko" to "ko",
        "zh-tw" to "zh",
        "zh" to "zh",
        "de" to "de",
        "fr" to "fr",
        "es" to "es",
        "th" to "th",
        "vi" to "vi"
    )

    private val GTX = mapOf(
        "ja" to "ja",
        "en" to "en",
        "ko" to "ko",
        "zh-tw" to "zh-TW",
        "zh" to "zh-CN",
        "de" to "de",
        "fr" to "fr",
        "es" to "es",
        "th" to "th",
        "vi" to "vi"
    )

    private val TTS = mapOf(
        "ja" to "ja-JP",
        "en" to "en-US",
        "ko" to "ko-KR",
        "zh-tw" to "zh-TW",
        "zh" to "zh-CN",
        "de" to "de-DE",
        "fr" to "fr-FR",
        "es" to "es-ES",
        "th" to "th-TH",
        "vi" to "vi-VN"
    )

    /** 語音辨識用的 BCP-47 標籤。未知代碼原樣回傳，交由辨識器自行判斷。 */
    fun toBcp47(appCode: String): String = BCP47[normalize(appCode)] ?: appCode.trim()

    /**
     * ML Kit 端上翻譯的語言標籤。
     * 未知代碼回傳 null（而非預設成英文），讓呼叫端能改走線上翻譯而不是靜默翻錯語言。
     */
    fun toMlKitTag(appCode: String): String? = ML_KIT[normalize(appCode)]

    /** Google translate_a 端點的 sl / tl 參數。這是唯一能拿到繁體中文的通道。 */
    fun toGtxTag(appCode: String): String = GTX[normalize(appCode)] ?: appCode.trim()

    /** TextToSpeech 用的語言標籤，供 Locale.forLanguageTag 使用。 */
    fun toTtsTag(appCode: String): String = TTS[normalize(appCode)] ?: appCode.trim()

    /** 該語言的譯文是否為繁體中文（ML Kit 給不出來，需要線上升級）。 */
    fun isTraditionalChinese(appCode: String): Boolean = normalize(appCode) == "zh-tw"

    private fun normalize(appCode: String): String = appCode.trim().lowercase()
}
