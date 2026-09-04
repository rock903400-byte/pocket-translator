package com.translator.pocket.engine

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
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
            JSONObject()
                .put(
                    "contents",
                    org.json.JSONArray().put(
                        JSONObject().put(
                            "parts",
                            org.json.JSONArray().put(
                                JSONObject().put("text", buildPrompt(text, targetLangCode))
                            )
                        )
                    )
                )
                .put(
                    "generationConfig",
                    JSONObject().put("temperature", 0).put("maxOutputTokens", 256)
                )
                .toString()

        /** 成功回譯文，失敗回 null（呼叫端保留上一版）。純函式方便測試。 */
        fun parseTranslatedText(body: String): String? = try {
            val text = JSONObject(body)
                .optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?.optJSONObject(0)
                ?.optString("text", "")
                .orEmpty()
                .trim()
            if (text.isEmpty()) null else text
        } catch (e: Exception) {
            null
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
