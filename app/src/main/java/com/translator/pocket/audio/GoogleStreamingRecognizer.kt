package com.translator.pocket.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.translator.pocket.util.LatencyLog

/**
 * Google 原生流式即時語音辨識器 (與 Google 翻譯 App、Pixel 即時字幕同款核心)
 * - 支援邊說邊出字 (Partial Results 即時動態字串)
 * - Google 聲學模型智慧斷句 (徹底杜絕雜音誤觸)
 * - 自動重啟循環保持持續監聽 (鎖屏/背景依然持續運作)
 */
class GoogleStreamingRecognizer(
    private val context: Context,
    private val onPartialText: (String) -> Unit,
    private val onFinalText: (String) -> Unit,
    private val onRmsChanged: ((Float) -> Unit)? = null,
    private val onStateChanged: ((String) -> Unit)? = null
) {
    companion object {
        private const val TAG = "GoogleStreamRecognizer"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var currentLanguage = "ja-JP"

    fun start(languageCode: String) {
        currentLanguage = mapToLanguageTag(languageCode)
        isListening = true
        mainHandler.post {
            initAndStartRecognizer()
        }
    }

    fun stop() {
        isListening = false
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.post {
            try {
                speechRecognizer?.stopListening()
                speechRecognizer?.destroy()
                speechRecognizer = null
            } catch (e: Exception) {
                Log.w(TAG, "停止辨識出錯", e)
            }
        }
    }

    private fun initAndStartRecognizer() {
        if (!isListening) return

        try {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(createListener())
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentLanguage)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, currentLanguage)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra("android.speech.extra.DICTATION_MODE", true)
            }

            speechRecognizer?.startListening(intent)
            onStateChanged?.invoke("Google 即時流式辨識已就緒，請說話...")
        } catch (e: Exception) {
            Log.e(TAG, "啟動 Google 辨識失敗", e)
            scheduleRestart(1000L)
        }
    }

    private fun createListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            onStateChanged?.invoke("正在聆聽中... (隨說隨譯)")
        }

        override fun onBeginningOfSpeech() {
            LatencyLog.onSpeechStart()
            onStateChanged?.invoke("偵測到語音，正在即時轉譯...")
        }

        override fun onRmsChanged(rmsdB: Float) {
            onRmsChanged?.invoke(rmsdB)
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            onStateChanged?.invoke("語音結束，正在即時口譯...")
        }

        override fun onError(error: Int) {
            val errorMsg = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH -> "未聽清"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "靜音等待"
                SpeechRecognizer.ERROR_AUDIO -> "音訊錯誤"
                SpeechRecognizer.ERROR_CLIENT -> "客戶端重置"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "權限不足"
                SpeechRecognizer.ERROR_NETWORK -> "網路逾時"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "連線逾時"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "辨識忙碌"
                SpeechRecognizer.ERROR_SERVER -> "伺服器錯誤"
                else -> "狀態代碼 $error"
            }
            Log.d(TAG, "Google 辨識狀態: $errorMsg ($error)")

            // 無匹配或靜音逾時為日常自然停頓，立即無縫重啟監聽
            if (isListening) {
                scheduleRestart(250L)
            }
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull()?.trim()
            if (!text.isNullOrBlank()) {
                Log.d(TAG, "Google 最終辨識結果: $text")
                onFinalText(text)
            }

            if (isListening) {
                scheduleRestart(150L)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val partial = matches?.firstOrNull()?.trim()
            if (!partial.isNullOrBlank()) {
                onPartialText(partial)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun scheduleRestart(delayMs: Long) {
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.postDelayed({
            if (isListening) {
                initAndStartRecognizer()
            }
        }, delayMs)
    }

    private fun mapToLanguageTag(code: String): String {
        return when (code.lowercase()) {
            "ja" -> "ja-JP"
            "en" -> "en-US"
            "ko" -> "ko-KR"
            "zh-tw", "zh" -> "zh-TW"
            "de" -> "de-DE"
            "fr" -> "fr-FR"
            "es" -> "es-ES"
            "th" -> "th-TH"
            "vi" -> "vi-VN"
            else -> code
        }
    }
}
