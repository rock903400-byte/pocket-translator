package com.translator.pocket.tts

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

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

    init {
        tts = TextToSpeech(context, this)
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
                        onSpeakingStateChanged(false)
                    }

                    override fun onError(utteranceId: String?) {
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
        }
    }

    fun speak(text: String, flushQueue: Boolean = false) {
        if (!isInitialized.get() || text.isBlank()) return

        val queueMode = if (flushQueue) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "utterance_${System.currentTimeMillis()}")
        }

        tts?.speak(text, queueMode, params, "utterance_${System.currentTimeMillis()}")
    }

    fun stop() {
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
