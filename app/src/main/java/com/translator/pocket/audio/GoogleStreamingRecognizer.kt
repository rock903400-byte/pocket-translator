package com.translator.pocket.audio

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.translator.pocket.model.LanguageCodes
import com.translator.pocket.util.LatencyLog

/**
 * Google 原生流式語音辨識器（Google 翻譯 App、Pixel 即時字幕同款核心）。
 *
 * 相較舊版最重要的改變：**不再每一句就把辨識器銷毀重建**。
 * 舊的循環是 destroy() + createSpeechRecognizer()（重新綁定系統服務，冷啟常見 300~800ms）
 * 再加上 150~250ms 的 postDelayed；語句交界處吃字就是從這裡來的。
 * 現在「重啟」只是對同一個實例再呼叫一次 startListening()，約 30~80ms。
 *
 * 仍然繞不過去的限制：兩次 startListening 之間確實不收音，間隙只能縮小不能歸零。
 */
class GoogleStreamingRecognizer(
    private val context: Context,
    private val onPartialText: (String) -> Unit,
    private val onFinalText: (String) -> Unit,
    /** 辨識器放棄了這一段（自然停頓）。已聽到的內容應該定案。 */
    private val onAborted: (() -> Unit)? = null,
    private val onRmsChanged: ((Float) -> Unit)? = null,
    private val onStateChanged: ((String) -> Unit)? = null,
    /** 無法繼續的錯誤（權限、裝置不支援、連續失敗）。呼叫端應停止口譯並顯示訊息。 */
    private val onFatalError: ((String) -> Unit)? = null
) {
    companion object {
        private const val TAG = "GoogleStreamRecognizer"

        /**
         * 避免只講了半秒就結束 session。
         * session 頻繁重啟是交界吃字的主因之一。
         */
        private const val MINIMUM_LENGTH_MS = 6000

        private const val POSSIBLY_COMPLETE_SILENCE_MS = 700

        /** 轉錄模式：說話者停頓久一點也不要急著斷句。 */
        const val SILENCE_TRANSCRIBE_MS = 900L

        /** 對話模式：輪次短，要讓 TTS 快點出聲。 */
        const val SILENCE_CONVERSATION_MS = 600L
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var isPaused = false
    private var currentLanguage = "ja-JP"
    private var completeSilenceMs = SILENCE_TRANSCRIBE_MS
    private var preferOffline = false

    /** 每建立一次辨識器就 +1，用來忽略已銷毀實例遲來的 callback。 */
    private var recognizerGeneration = 0

    /** start / stop / setLanguage / pause 時 +1，用來讓排隊中的重啟失效。 */
    private var sessionGeneration = 0

    private var restartRunnable: Runnable? = null
    private var consecutiveFailures = 0

    fun start(
        languageCode: String,
        preferOfflineRecognition: Boolean = false,
        silenceLengthMs: Long = SILENCE_TRANSCRIBE_MS
    ) {
        currentLanguage = LanguageCodes.toBcp47(languageCode)
        preferOffline = preferOfflineRecognition
        completeSilenceMs = silenceLengthMs
        isListening = true
        isPaused = false
        consecutiveFailures = 0
        sessionGeneration++

        mainHandler.post {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                // 舊版沒有這個檢查，於是在缺少語音服務的裝置上會永遠靜靜地什麼都不做
                isListening = false
                Log.e(TAG, "此裝置沒有可用的語音辨識服務")
                onFatalError?.invoke("此裝置未安裝 Google 語音辨識服務，請改用其他引擎")
                return@post
            }
            recreateRecognizer()
            beginListening()
        }
    }

    /** 切換辨識語言（對話模式換發話方時使用）。 */
    fun setLanguage(languageCode: String) {
        val tag = LanguageCodes.toBcp47(languageCode)
        if (tag == currentLanguage) return
        currentLanguage = tag
        if (!isListening) return

        sessionGeneration++
        cancelPendingRestart()
        mainHandler.post {
            runCatching { speechRecognizer?.cancel() }
            beginListening()
        }
    }

    /**
     * 暫停收音（外放播放 TTS 時用來避免自己聽到自己）。
     *
     * 必須用 cancel() 而不是 stopListening()：後者會要求對已收到的音訊產出最終結果，
     * 於是 TTS 的聲音會被當成一句話定案。
     */
    fun pause() {
        if (!isListening || isPaused) return
        isPaused = true
        sessionGeneration++
        cancelPendingRestart()
        mainHandler.post { runCatching { speechRecognizer?.cancel() } }
    }

    fun resume() {
        if (!isListening || !isPaused) return
        isPaused = false
        sessionGeneration++
        mainHandler.post { beginListening() }
    }

    fun stop() {
        isListening = false
        isPaused = false
        sessionGeneration++
        cancelPendingRestart()
        mainHandler.post {
            recognizerGeneration++
            try {
                speechRecognizer?.cancel()
                speechRecognizer?.destroy()
            } catch (e: Exception) {
                Log.w(TAG, "停止辨識出錯", e)
            }
            speechRecognizer = null
        }
    }

    // ── 內部 ─────────────────────────────────────────────

    private fun recreateRecognizer() {
        recognizerGeneration++
        val generation = recognizerGeneration

        try {
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "銷毀舊辨識器出錯", e)
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(createListener(generation))
        }
    }

    private fun beginListening() {
        if (!isListening || isPaused) return
        if (speechRecognizer == null) recreateRecognizer()

        try {
            speechRecognizer?.startListening(buildIntent())
        } catch (e: Exception) {
            Log.e(TAG, "startListening 例外", e)
            scheduleRestart(1000L, recreate = true)
        }
    }

    private fun buildIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentLanguage)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)

        // 未公開，但正是它讓 Google 辨識器持續吐出豐富的 partial 而不在第一次停頓就收工
        putExtra("android.speech.extra.DICTATION_MODE", true)

        // 注意：官方文件明講這些只是提示，不保證被遵守。
        // 真正讓我們能提早出字的是 LiveSubtitleState 的沉澱計時器。
        // 文件把這三個標示為 integer，所以用 Int 送；型別不合時系統會直接忽略，不會出錯
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, MINIMUM_LENGTH_MS)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, completeSilenceMs.toInt())
        putExtra(
            RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
            POSSIBLY_COMPLETE_SILENCE_MS
        )

        if (preferOffline) {
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // 讓 partial 帶標點，正好餵給 LiveSubtitleState 的句中切段規則。
            // 選 OPTIMIZE_LATENCY 而非 OPTIMIZE_QUALITY：延遲是這裡的第一優先。
            putExtra(
                RecognizerIntent.EXTRA_ENABLE_FORMATTING,
                RecognizerIntent.FORMATTING_OPTIMIZE_LATENCY
            )
            putExtra(RecognizerIntent.EXTRA_MASK_OFFENSIVE_WORDS, false)
        }
    }

    private fun createListener(generation: Int) = object : RecognitionListener {

        private fun stale(): Boolean = generation != recognizerGeneration || !isListening || isPaused

        override fun onReadyForSpeech(params: Bundle?) {
            if (stale()) return
            onStateChanged?.invoke("正在聆聽中... (隨說隨譯)")
        }

        override fun onBeginningOfSpeech() {
            if (stale()) return
            LatencyLog.onSpeechStart()
            onStateChanged?.invoke("偵測到語音，正在即時轉譯...")
        }

        override fun onRmsChanged(rmsdB: Float) {
            if (stale()) return
            onRmsChanged?.invoke(rmsdB)
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            if (stale()) return
            onStateChanged?.invoke("正在完成這一句...")
        }

        override fun onError(error: Int) {
            if (stale()) return
            handleError(error)
        }

        override fun onResults(results: Bundle?) {
            if (stale()) return

            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()

            if (!text.isNullOrBlank()) {
                consecutiveFailures = 0
                Log.d(TAG, "最終辨識結果: $text")
                onFinalText(text)
            } else {
                onAborted?.invoke()
            }

            // 同一個實例立即重新聆聽，不重建、不延遲
            scheduleRestart(0L, recreate = false)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (stale()) return

            val partial = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()

            if (!partial.isNullOrBlank()) {
                consecutiveFailures = 0
                onPartialText(partial)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun handleError(error: Int) {
        consecutiveFailures++
        val normalPause = RecognizerErrorPolicy.isNormalPause(error)

        if (normalPause) {
            // 只是說話者停頓，不是故障；已經聽到的內容該定案
            consecutiveFailures = 0
            onAborted?.invoke()
        } else {
            Log.d(TAG, "辨識錯誤: ${RecognizerErrorPolicy.describe(error)} ($error) x$consecutiveFailures")
        }

        when (val action = RecognizerErrorPolicy.decide(error, consecutiveFailures, preferOffline)) {
            is RecognizerAction.Restart -> scheduleRestart(action.delayMs, recreate = false)

            is RecognizerAction.Recreate -> scheduleRestart(action.delayMs, recreate = true)

            is RecognizerAction.RetryWithoutOffline -> {
                Log.w(TAG, "此裝置沒有 $currentLanguage 的離線語音包，改用線上辨識")
                preferOffline = false
                onStateChanged?.invoke("裝置無此語言離線語音包，已改用線上辨識")
                scheduleRestart(action.delayMs, recreate = false)
            }

            is RecognizerAction.Abort -> {
                Log.e(TAG, "辨識中止: ${action.message}")
                isListening = false
                cancelPendingRestart()
                onFatalError?.invoke(action.message)
            }
        }
    }

    /**
     * 只移除自己排的那一個 runnable。
     * 舊版用 removeCallbacksAndMessages(null)，會把 main looper 上所有排隊工作一起清掉。
     */
    private fun cancelPendingRestart() {
        restartRunnable?.let { mainHandler.removeCallbacks(it) }
        restartRunnable = null
    }

    private fun scheduleRestart(delayMs: Long, recreate: Boolean) {
        cancelPendingRestart()
        val session = sessionGeneration

        val runnable = Runnable {
            if (session != sessionGeneration || !isListening || isPaused) return@Runnable
            if (recreate) recreateRecognizer()
            beginListening()
        }
        restartRunnable = runnable

        if (delayMs <= 0L) {
            mainHandler.post(runnable)
        } else {
            mainHandler.postDelayed(runnable, delayMs)
        }
    }
}
