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
    private val geminiApiKeyProvider: () -> String
) : ITranslationEngine {

    companion object {
        private const val TAG = "CloudAiEngine"
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
                    addFormDataPart("language", sourceLangCode)
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

        // 步驟 2: 調用極速 LLM (llama-3.3-70b-versatile) 翻譯成繁體中文或目標語言
        val targetName = when (targetLangCode) {
            "zh-TW" -> "Traditional Chinese (繁體中文-台灣)"
            "ja" -> "Japanese (日本語)"
            "en" -> "English"
            "ko" -> "Korean (한국어)"
            else -> targetLangCode
        }

        val prompt = "You are an expert real-time simultaneous interpreter. Translate the following text naturally, accurately, and concisely into $targetName. Output ONLY the translated text without explanations, greetings, notes, or quotes:\n\n$recognizedText"

        val chatPayload = JSONObject().apply {
            put("model", "llama-3.3-70b-versatile")
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

        if (!chatResponse.isSuccessful) {
            Log.e(TAG, "Groq 翻譯錯誤: HTTP ${chatResponse.code} - $chatBody")
            return TranslationResult(
                originalText = recognizedText,
                translatedText = recognizedText, // 降級回傳原文
                isSuccess = false,
                errorMessage = "文字翻譯失敗: HTTP ${chatResponse.code}"
            )
        }

        val jsonChat = JSONObject(chatBody)
        val choices = jsonChat.optJSONArray("choices")
        val translatedText = choices?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content", "")
            ?.trim() ?: recognizedText

        return TranslationResult(
            originalText = recognizedText,
            translatedText = translatedText,
            isSuccess = true
        )
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

        val prompt = "請擔任同步口譯員。聽取這段語音，並直接輸出翻譯後的【$targetName】文字。只要輸出翻譯結果，不要包含任何多餘說明。"

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

        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$apiKey")
            .post(payload.toString().toRequestBody(MEDIA_TYPE_JSON))
            .build()

        val response = httpClient.newCall(request).execute()
        val body = response.body?.string().orEmpty()

        if (!response.isSuccessful) {
            return TranslationResult(
                originalText = "",
                translatedText = "",
                isSuccess = false,
                errorMessage = "Gemini API 失敗: HTTP ${response.code}"
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
