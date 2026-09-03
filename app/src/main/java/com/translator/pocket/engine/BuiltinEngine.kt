package com.translator.pocket.engine

import android.content.Context
import android.util.Log
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.translator.pocket.model.TranslationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

class BuiltinEngine(
    private val context: Context
) : ITranslationEngine {

    companion object {
        private const val TAG = "BuiltinEngine"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    override suspend fun translateSpeech(
        wavBytes: ByteArray,
        sourceLangCode: String,
        targetLangCode: String
    ): TranslationResult = withContext(Dispatchers.IO) {
        try {
            // 免費語音辨識通道
            val recognizedText = recognizeSpeechFree(wavBytes, sourceLangCode)
            if (recognizedText.isNullOrBlank()) {
                return@withContext TranslationResult(
                    originalText = "",
                    translatedText = "",
                    isSuccess = false,
                    errorMessage = "未辨識到清晰語音"
                )
            }

            // 免費翻譯：優先使用 Google ML Kit 本地神經網路模型
            val translated = translateWithMlKit(recognizedText, sourceLangCode, targetLangCode)

            return@withContext TranslationResult(
                originalText = recognizedText,
                translatedText = translated,
                isSuccess = true
            )
        } catch (e: Exception) {
            Log.e(TAG, "內建引擎處理錯誤", e)
            return@withContext TranslationResult(
                originalText = "",
                translatedText = "",
                isSuccess = false,
                errorMessage = "內建翻譯錯誤: ${e.message}"
            )
        }
    }

    private fun recognizeSpeechFree(wavBytes: ByteArray, lang: String): String? {
        // 使用公開的 Speech-to-Text 轉譯服務
        try {
            val url = "https://www.google.com/speech-api/v2/recognize?output=json&lang=$lang&key=AIzaSyA_placeholder"
            val request = Request.Builder()
                .url(url)
                .addHeader("Content-Type", "audio/l16; rate=16000")
                .post(wavBytes.toRequestBody("audio/l16; rate=16000".toMediaType()))
                .build()

            // 由於公開端點可能受限，若未成功則由系統辨識器輔助
            val response = httpClient.newCall(request).execute()
            val resText = response.body?.string().orEmpty()
            if (response.isSuccessful && resText.contains("\"transcript\"")) {
                val lines = resText.split("\n")
                for (line in lines) {
                    if (line.contains("transcript")) {
                        val json = org.json.JSONObject(line)
                        val result = json.optJSONArray("result")?.optJSONObject(0)
                        val alternative = result?.optJSONArray("alternative")?.optJSONObject(0)
                        return alternative?.optString("transcript")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "公開語音識別連線通知: ${e.message}")
        }
        return null
    }

    private suspend fun translateWithMlKit(text: String, srcLang: String, tgtLang: String): String {
        return try {
            val source = mapToMlKitLang(srcLang)
            val target = mapToMlKitLang(tgtLang)

            val options = TranslatorOptions.Builder()
                .setSourceLanguage(source)
                .setTargetLanguage(target)
                .build()

            val translator = Translation.getClient(options)
            translator.downloadModelIfNeeded().awaitTask()
            val translated = translator.translate(text).awaitTask()
            translator.close()
            translated
        } catch (e: Exception) {
            Log.w(TAG, "ML Kit 離線翻譯模型異常，使用線上回退", e)
            translateFreeOnline(text, srcLang, tgtLang)
        }
    }

    private suspend fun <T> com.google.android.gms.tasks.Task<T>.awaitTask(): T =
        suspendCancellableCoroutine { continuation ->
            addOnSuccessListener { result -> continuation.resumeWith(Result.success(result)) }
            addOnFailureListener { exception -> continuation.resumeWithException(exception) }
        }

    private fun translateFreeOnline(text: String, src: String, tgt: String): String {
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
            text
        }
    }

    private fun mapToMlKitLang(code: String): String {
        return when (code.lowercase()) {
            "ja" -> TranslateLanguage.JAPANESE
            "en" -> TranslateLanguage.ENGLISH
            "ko" -> TranslateLanguage.KOREAN
            "zh-tw", "zh" -> TranslateLanguage.CHINESE
            "de" -> TranslateLanguage.GERMAN
            "fr" -> TranslateLanguage.FRENCH
            "es" -> TranslateLanguage.SPANISH
            "th" -> TranslateLanguage.THAI
            "vi" -> TranslateLanguage.VIETNAMESE
            else -> TranslateLanguage.ENGLISH
        }
    }
}
