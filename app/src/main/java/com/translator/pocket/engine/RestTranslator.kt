package com.translator.pocket.engine

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * C 線：把 transcribe 滾出來的暫存原文即時翻成目標語言（Google 翻譯 App 那排會跳動的譯文）。
 *
 * 設計原則：寧缺勿卡 —— 任何失敗（超時、429、空回應）一律回 null，
 * 呼叫端保留上一版暫存字，絕不顯示錯誤。暫存譯文品質本來就會跳動，最終以 B 線定案為準。
 */
class RestTranslator(
    private val apiKeyProvider: () -> String,
    private val modelName: String = DEFAULT_REST_MODEL,
    private val baseUrl: String = BASE_URL,
    client: OkHttpClient? = null
) {
    companion object {
        private const val TAG = "RestTranslator"

        /** ListModels 實測存在、generateContent 可用的速模型 */
        const val DEFAULT_REST_MODEL = "gemini-3.5-flash"
        const val BASE_URL = "https://generativelanguage.googleapis.com"

        /** 暫存原文去抖：500ms 內只翻最新一次 */
        const val DEBOUNCE_MS = 500L

        /** 與上次送翻差不到 2 字就不打，省 token */
        const val MIN_DELTA_CHARS = 2

        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun targetDisplayName(code: String): String = when (code.lowercase()) {
            "zh-hant", "zh-tw" -> "Traditional Chinese (繁體中文)"
            "zh-hans", "zh-cn", "zh" -> "Simplified Chinese (简体中文)"
            "ja", "ja-jp" -> "Japanese (日本語)"
            "en", "en-us" -> "English"
            "ko", "ko-kr" -> "Korean (한국어)"
            "de" -> "German"
            "fr" -> "French"
            "es" -> "Spanish"
            "th" -> "Thai"
            "vi" -> "Vietnamese"
            else -> code
        }

        fun buildPrompt(text: String, targetLangCode: String): String =
            "Translate the following speech transcript fragment into ${targetDisplayName(targetLangCode)}. " +
                "It is an incomplete fragment of ongoing speech; translate only what is there. " +
                "Output ONLY the translated text, no explanations, no quotes:\n\n$text"

        fun buildRequestBody(text: String, targetLangCode: String): String =
            """{"contents":[{"parts":[{"text":${escapeJsonString(buildPrompt(text, targetLangCode))}}]}],""" +
                """"generationConfig":{"temperature":0,"maxOutputTokens":256}}"""

        /**
         * 刻意不用 org.json：它是 Android 樁，JVM 單元測試下所有方法只回預設值。
         * 回應形狀固定為 candidates[0].content.parts[0].text，抓第一個 "text" 鍵即可。
         * 成功回譯文，失敗回 null（呼叫端保留上一版）。純函式方便測試。
         */
        private val TEXT_VALUE = Regex("\"text\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

        fun parseTranslatedText(body: String): String? {
            val raw = TEXT_VALUE.find(body)?.groupValues?.getOrNull(1) ?: return null
            val text = unescapeJsonString(raw)?.trim().orEmpty()
            return if (text.isEmpty()) null else text
        }

        fun escapeJsonString(s: String): String = buildString(s.length + 2) {
            append('"')
            for (c in s) {
                when (c) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
                }
            }
            append('"')
        }

        fun unescapeJsonString(s: String): String? {
            val out = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c != '\\') {
                    out.append(c)
                    i++
                    continue
                }
                if (i + 1 >= s.length) return null
                when (s[i + 1]) {
                    '"', '\\', '/' -> out.append(s[i + 1])
                    'b' -> out.append('\b')
                    'f' -> out.append('\u000C')
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    't' -> out.append('\t')
                    'u' -> {
                        if (i + 5 >= s.length) return null
                        val code = s.substring(i + 2, i + 6).toIntOrNull(16) ?: return null
                        out.append(code.toChar())
                        i += 4
                    }
                    else -> return null
                }
                i += 2
            }
            return out.toString()
        }

        /** 去抖決策：夠久且差夠多字才打。純函式方便測試。 */
        fun shouldTranslate(nowMs: Long, lastSentMs: Long, current: String, lastSent: String): Boolean {
            if (current.isBlank()) return false
            if (current == lastSent) return false
            if (nowMs - lastSentMs < DEBOUNCE_MS) return false
            // 用「新增長度」判斷，避免修正跳動時每個小改都打
            val common = current.commonPrefixWith(lastSent).length
            return current.length - common >= MIN_DELTA_CHARS || current.length < lastSent.length
        }
    }

    private val httpClient: OkHttpClient = client ?: OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    suspend fun translatePartial(text: String, targetLangCode: String): String? =
        withContext(Dispatchers.IO) {
            if (text.isBlank()) return@withContext null
            val apiKey = apiKeyProvider().trim()
            if (apiKey.isEmpty()) return@withContext null
            try {
                val request = Request.Builder()
                    .url("$baseUrl/v1beta/models/$modelName:generateContent?key=$apiKey")
                    .addHeader("x-goog-api-key", apiKey)
                    .post(buildRequestBody(text, targetLangCode).toRequestBody(JSON))
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    val body = response.body?.string().orEmpty()
                    parseTranslatedText(body)
                }
            } catch (e: Exception) {
                Log.w(TAG, "暫存翻譯失敗（靜默略過）: ${e.message}")
                null
            }
        }
}
