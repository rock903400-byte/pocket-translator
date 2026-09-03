package com.translator.pocket.engine

import android.util.Log
import com.translator.pocket.model.TranslationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class CloudAiEngine(
    private val groqApiKeyProvider: () -> String,
    private val geminiApiKeyProvider: () -> String,
    private val geminiModelProvider: () -> String = { DEFAULT_REST_MODEL }
) : ITranslationEngine {

    companion object {
        private const val TAG = "CloudAiEngine"
        const val DEFAULT_REST_MODEL = "gemini-3.5-flash"
        private val MEDIA_TYPE_WAV = "audio/wav".toMediaType()
        private val MEDIA_TYPE_JSON = "application/json; charset=utf-8".toMediaType()
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .build()

    override suspend fun translateSpeech(
        wavBytes: ByteArray,
        sourceLangCode: String,
        targetLangCode: String
    ): TranslationResult = withContext(Dispatchers.IO) {
        val groqKey = groqApiKeyProvider().trim()
        val geminiKey = geminiApiKeyProvider().trim()

        if (groqKey.isEmpty() && geminiKey.isEmpty()) {
            return@withContext TranslationResult(
                originalText = "",
                translatedText = "",
                isSuccess = false,
                errorMessage = "未設定 API 金鑰，請至設定填入 Groq 或 Gemini API Key，或切換至免費內建模式"
            )
        }

        try {
            if (groqKey.isNotEmpty()) {
                return@withContext translateViaGroq(wavBytes, sourceLangCode, targetLangCode, groqKey)
            } else {
                return@withContext translateViaGemini(wavBytes, sourceLangCode, targetLangCode, geminiKey)
            }
        } catch (e: Exception) {
            Log.e(TAG, "雲端翻譯失敗: ${e.message}", e)
            return@withContext TranslationResult(
                originalText = "",
                translatedText = "",
                isSuccess = false,
                errorMessage = "連線失敗: ${e.localizedMessage ?: "網路異常"}"
            )
        }
    }

    /**
     * Groq Whisper (語音辨識 STT) + Groq Llama-3.3 (即時口譯 MT)
     * 延遲極低，僅約 300ms ~ 500ms
     */
    private fun translateViaGroq(
        wavBytes: ByteArray,
        sourceLangCode: String,
        targetLangCode: String,
        apiKey: String
    ): TranslationResult {
        // 步驟 1: Whisper-large-v3-turbo 快速語音識別
        val whisperRequestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                "speech.wav",
                wavBytes.toRequestBody(MEDIA_TYPE_WAV, 0, wavBytes.size)
            )
            .addFormDataPart("model", "whisper-large-v3-turbo")
            .addFormDataPart("response_format", "json")
            .apply {
                if (sourceLangCode.isNotEmpty() && sourceLangCode != "auto") {
                    val whisperLang = when {
                        sourceLangCode.startsWith("zh", ignoreCase = true) -> "zh"
                        sourceLangCode.contains("-") -> sourceLangCode.split("-")[0]
                        else -> sourceLangCode
                    }
                    addFormDataPart("language", whisperLang)
                }
            }
            .build()

        val whisperRequest = Request.Builder()
            .url("https://api.groq.com/openai/v1/audio/transcriptions")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(whisperRequestBody)
            .build()

        val whisperResponse = httpClient.newCall(whisperRequest).execute()
        val whisperBody = whisperResponse.body?.string().orEmpty()

        if (!whisperResponse.isSuccessful) {
            Log.e(TAG, "Groq Whisper 錯誤: HTTP ${whisperResponse.code} - $whisperBody")
            return TranslationResult(
                originalText = "",
                translatedText = "",
                isSuccess = false,
                errorMessage = "語音識別失敗: HTTP ${whisperResponse.code}"
            )
        }

        val jsonWhisper = JSONObject(whisperBody)
        val recognizedText = jsonWhisper.optString("text", "").trim()

        if (recognizedText.isEmpty()) {
            return TranslationResult(
                originalText = "",
                translatedText = "",
                isSuccess = false,
                errorMessage = "未識別到有效語音"
            )
        }

        // 若來源語言與目標語言相同，直接返回
        if (sourceLangCode.equals(targetLangCode, ignoreCase = true)) {
            return TranslationResult(
                originalText = recognizedText,
                translatedText = recognizedText,
                isSuccess = true
            )
        }

        // 步驟 2: 調用極速 LLM (優先使用官方標準主力 llama-3.1-8b-instant，並具備多重備援與 Google 翻譯回退)
        val targetName = when (targetLangCode) {
            "zh-TW" -> "Traditional Chinese (繁體中文-台灣)"
            "ja" -> "Japanese (日本語)"
            "en" -> "English"
            "ko" -> "Korean (한국어)"
            else -> targetLangCode
        }

        val prompt = "You are an expert real-time simultaneous interpreter. Translate the following text naturally, accurately, and concisely into $targetName. Output ONLY the translated text without explanations, greetings, notes, or quotes:\n\n$recognizedText"

        val candidateModels = listOf(
            "llama-3.1-8b-instant",
            "llama3-8b-8192",
            "llama-3.3-70b-versatile",
            "llama3-70b-8192"
        )

        var translatedText: String? = null

        for (modelName in candidateModels) {
            try {
                val chatPayload = JSONObject().apply {
                    put("model", modelName)
                    put("temperature", 0.1)
                    put("max_tokens", 300)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "system")
                            put("content", "You are a professional simultaneous interpreter. Output translated text only.")
                        })
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", prompt)
                        })
                    })
                }

                val chatRequest = Request.Builder()
                    .url("https://api.groq.com/openai/v1/chat/completions")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(chatPayload.toString().toRequestBody(MEDIA_TYPE_JSON))
                    .build()

                val chatResponse = httpClient.newCall(chatRequest).execute()
                val chatBody = chatResponse.body?.string().orEmpty()

                if (chatResponse.isSuccessful) {
                    val jsonChat = JSONObject(chatBody)
                    val choices = jsonChat.optJSONArray("choices")
                    val content = choices?.optJSONObject(0)
                        ?.optJSONObject("message")
                        ?.optString("content", "")
                        ?.trim()
                    if (!content.isNullOrEmpty()) {
                        translatedText = content
                        break
                    }
                } else {
                    Log.w(TAG, "Groq 模型 $modelName 失敗 (HTTP ${chatResponse.code})，嘗試備援模型...")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Groq 模型 $modelName 連線例外: ${e.message}")
            }
        }

        // 若所有 Groq 模型均異常，無縫回退至 Google Translate 免費通道
        if (translatedText.isNullOrBlank()) {
            Log.w(TAG, "Groq 所有 LLM 均未回應，自動回退 Google Translate 免費通道...")
            translatedText = fallbackTranslateGoogle(recognizedText, sourceLangCode, targetLangCode)
        }

        return TranslationResult(
            originalText = recognizedText,
            translatedText = translatedText ?: recognizedText,
            isSuccess = true
        )
    }

    private fun fallbackTranslateGoogle(text: String, src: String, tgt: String): String {
        return try {
            val encodedText = java.net.URLEncoder.encode(text, "UTF-8")
            val target = if (tgt == "zh-TW") "zh-TW" else tgt
            val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=$src&tl=$target&dt=t&q=$encodedText"

            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string().orEmpty()
            val jsonArray = JSONArray(body)
            val parts = jsonArray.optJSONArray(0)
            val sb = StringBuilder()
            for (i in 0 until (parts?.length() ?: 0)) {
                val segment = parts?.optJSONArray(i)?.optString(0).orEmpty()
                sb.append(segment)
            }
            sb.toString().ifEmpty { text }
        } catch (e: Exception) {
            Log.w(TAG, "Google 翻譯備援失敗", e)
            text
        }
    }

    /**
     * Gemini Flash 備援翻譯
     */
    private fun translateViaGemini(
        wavBytes: ByteArray,
        sourceLangCode: String,
        targetLangCode: String,
        apiKey: String
    ): TranslationResult {
        val base64Audio = android.util.Base64.encodeToString(wavBytes, android.util.Base64.NO_WRAP)
        val targetName = if (targetLangCode == "zh-TW") "繁體中文" else targetLangCode

        val prompt = "請擔任專業即時同步口譯員。請聽這段語音，並直接輸出翻譯後的【$targetName】文字。只輸出翻譯結果本身，不要包含引號、說明或問候。"

        val payload = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", prompt)
                        })
                        put(JSONObject().apply {
                            put("inlineData", JSONObject().apply {
                                put("mimeType", "audio/wav")
                                put("data", base64Audio)
                            })
                        })
                    })
                })
            })
        }

        // Live 專用模型只存在於 WebSocket Live API，用 REST generateContent 呼叫必定失敗，
        // 這裡一律換成可以走 REST 的一般模型。
        val inputModel = geminiModelProvider().trim().ifEmpty { DEFAULT_REST_MODEL }
        val rawModel = if (
            inputModel.contains("live", ignoreCase = true) ||
            inputModel.contains("transcribe", ignoreCase = true)
        ) DEFAULT_REST_MODEL else inputModel
        val modelClean = rawModel.removePrefix("models/")
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelClean:generateContent"

        val request = Request.Builder()
            .url(url)
            .addHeader("x-goog-api-key", apiKey)
            .post(payload.toString().toRequestBody(MEDIA_TYPE_JSON))
            .build()

        val response = httpClient.newCall(request).execute()
        val body = response.body?.string().orEmpty()

        if (!response.isSuccessful) {
            Log.e(TAG, "Gemini REST 失敗: HTTP ${response.code} - $body")
            return TranslationResult(
                originalText = "",
                translatedText = "",
                isSuccess = false,
                errorMessage = "Gemini API 失敗: HTTP ${response.code} ($body)"
            )
        }

        val json = JSONObject(body)
        val candidates = json.optJSONArray("candidates")
        val content = candidates?.optJSONObject(0)?.optJSONObject("content")
        val parts = content?.optJSONArray("parts")
        val translatedText = parts?.optJSONObject(0)?.optString("text", "")?.trim().orEmpty()

        return TranslationResult(
            originalText = "(語音已辨識)",
            translatedText = translatedText,
            isSuccess = translatedText.isNotEmpty()
        )
    }
}
