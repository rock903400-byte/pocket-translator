package com.translator.pocket.tts

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class EarphoneTtsManager(
    private val context: Context,
    private val onSpeakingStateChanged: (isSpeaking: Boolean) -> Unit
) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "EarphoneTtsManager"
    }

    private var tts: TextToSpeech? = null
    private val isInitialized = AtomicBoolean(false)
    private var pendingSpeechRate = 1.15f

    /** 尚未唸完的段落數（含正在唸的那一段）。 */
    private val pending = AtomicInteger(0)

    val pendingCount: Int get() = pending.get()

    init {
        tts = try {
            TextToSpeech(context, this, "com.google.android.tts")
        } catch (e: Exception) {
            TextToSpeech(context, this)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isInitialized.set(true)
            tts?.apply {
                // 設定音訊屬性為通話/語音屬性，確保能精準由系統路由至聽筒 (Earpiece)、耳機或外放
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                setAudioAttributes(audioAttributes)

                setSpeechRate(pendingSpeechRate)
                language = Locale.TRADITIONAL_CHINESE

                setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        onSpeakingStateChanged(true)
                    }

                    override fun onDone(utteranceId: String?) {
                        decrementPending()
                        onSpeakingStateChanged(false)
                    }

                    override fun onError(utteranceId: String?) {
                        decrementPending()
                        onSpeakingStateChanged(false)
                    }
                })
            }
            Log.d(TAG, "TextToSpeech 初始化成功")
        } else {
            Log.e(TAG, "TextToSpeech 初始化失敗: $status")
        }
    }

    fun setSpeechRate(rate: Float) {
        pendingSpeechRate = rate
        if (isInitialized.get()) {
            tts?.setSpeechRate(rate)
        }
    }

    fun setLanguage(langCode: String) {
        if (!isInitialized.get()) return

        val locale = when (langCode.lowercase()) {
            "zh-tw", "zh" -> Locale.TRADITIONAL_CHINESE
            "ja" -> Locale.JAPANESE
            "en" -> Locale.ENGLISH
            "ko" -> Locale.KOREAN
            "de" -> Locale.GERMAN
            "fr" -> Locale.FRENCH
            "es" -> Locale("es", "ES")
            "th" -> Locale("th", "TH")
            "vi" -> Locale("vi", "VN")
            else -> Locale.TRADITIONAL_CHINESE
        }

        val result = tts?.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "語音朗讀語言暫不支援或需下載套件: $langCode")
        } else {
            try {
                val voices = tts?.voices
                if (!voices.isNullOrEmpty()) {
                    val highQualityVoice = voices.firstOrNull { v ->
                        v.locale.language == locale.language &&
                        (v.name.contains("network", ignoreCase = true) || v.name.contains("neural", ignoreCase = true))
                    } ?: voices.firstOrNull { it.locale.language == locale.language }

                    if (highQualityVoice != null) {
                        tts?.voice = highQualityVoice
                        Log.d(TAG, "已選用最高品質 Google 語音: ${highQualityVoice.name}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "選擇高音質語音失敗", e)
            }
        }
    }

    fun speak(text: String, flushQueue: Boolean = false) {
        if (!isInitialized.get() || text.isBlank()) return

        val queueMode = if (flushQueue) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD

        // 舊版在 Bundle 與第 4 個參數各呼叫一次 currentTimeMillis()，
        // 差 1ms 就變成兩個不同的 id，而 listener 收到的是第 4 個參數那個。
        val utteranceId = "utterance_${System.nanoTime()}"
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }

        pending.incrementAndGet()
        val result = tts?.speak(text, queueMode, params, utteranceId)
        if (result != TextToSpeech.SUCCESS) {
            decrementPending()
        }
    }

    /**
     * 串流口譯專用：佇列積太多就清空跳到最新的一句。
     *
     * 刻意不改動 [speak] 的語意 —— 這個類別與 Gemini Live / 高速 AI 兩個引擎共用。
     */
    fun speakStreaming(text: String) {
        if (TtsQueuePolicy.shouldFlush(pending.get())) {
            pending.set(0)
            speak(text, flushQueue = true)
        } else {
            speak(text, flushQueue = false)
        }
    }

    private fun decrementPending() {
        pending.updateAndGet { if (it > 0) it - 1 else 0 }
    }

    fun stop() {
        pending.set(0)
        if (isInitialized.get()) {
            tts?.stop()
        }
    }

    fun release() {
        if (isInitialized.get()) {
            tts?.stop()
            tts?.shutdown()
        }
        isInitialized.set(false)
        tts = null
    }
}
