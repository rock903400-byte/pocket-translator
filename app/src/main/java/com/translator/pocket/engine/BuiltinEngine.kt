package com.translator.pocket.engine

import android.content.Context
import android.util.Log
import com.translator.pocket.model.LanguageCodes
import com.translator.pocket.model.TranslationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/**
 * 免金鑰引擎：Google 原生語音辨識（由 GoogleStreamingRecognizer 負責）搭配翻譯。
 *
 * 翻譯優先走 ML Kit 端上模型（離線、低延遲），失敗才回退到 Google 免費翻譯端點。
 * 端上翻譯器由 [MlKitTranslatorCache] 在整個 session 內保持存活，見該類別的說明。
 */
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

    private val translatorCache = MlKitTranslatorCache()

    /**
     * 在 session 開始時預先備妥語言對的端上模型並暖機。
     * 回傳 [MlKitTranslatorCache.Prepare.Failed] 時不應中止 session ——
     * [translateText] 仍會自動回退到線上翻譯。
     */
    suspend fun prepare(
        srcLangCode: String,
        tgtLangCode: String,
        onDownloadStarted: (approxMb: Int) -> Unit = {}
    ): MlKitTranslatorCache.Prepare = translatorCache.prepare(srcLangCode, tgtLangCode, onDownloadStarted)

    /** 釋放端上翻譯器。冪等。 */
    fun release() {
        translatorCache.close()
    }

    override suspend fun translateSpeech(
        wavBytes: ByteArray,
        sourceLangCode: String,
        targetLangCode: String
    ): TranslationResult {
        // 免金鑰模式的語音辨識由系統 SpeechRecognizer 串流完成，不走音訊檔上傳。
        return TranslationResult(
            originalText = "",
            translatedText = "",
            isSuccess = false,
            errorMessage = "免金鑰模式不支援音訊檔翻譯，請在設定中選擇【Google 原生流式即時翻譯】，或填入 Groq / Gemini API Key。"
        )
    }

    /**
     * 翻譯一段文字。端上模型優先，不可用時回退線上端點。
     * 永遠回傳可用的字串（最壞情況是原文），呼叫端不需要處理 null。
     */
    suspend fun translateText(text: String, srcLang: String, tgtLang: String): String {
        if (text.isBlank()) return text

        translatorCache.translate(text, srcLang, tgtLang)?.let { return it }

        Log.d(TAG, "端上翻譯不可用，改走線上免費通道")
        return translateFreeOnline(text, srcLang, tgtLang)
    }

    /**
     * Google translate_a 免費通道。這是唯一能拿到繁體中文的路徑
     * （ML Kit 端上模型只有簡體 "zh"）。
     */
    suspend fun translateFreeOnline(text: String, src: String, tgt: String): String =
        withContext(Dispatchers.IO) {
            try {
                val encodedText = java.net.URLEncoder.encode(text, "UTF-8")
                val sl = LanguageCodes.toGtxTag(src)
                val tl = LanguageCodes.toGtxTag(tgt)
                val url =
                    "https://translate.googleapis.com/translate_a/single?client=gtx&sl=$sl&tl=$tl&dt=t&q=$encodedText"

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
                Log.w(TAG, "線上免費翻譯失敗: ${e.message}")
                text
            }
        }
}
