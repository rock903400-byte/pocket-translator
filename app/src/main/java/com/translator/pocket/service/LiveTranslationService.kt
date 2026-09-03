package com.translator.pocket.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.translator.pocket.MainActivity
import com.translator.pocket.PocketTranslatorApp
import com.translator.pocket.R
import com.translator.pocket.audio.AudioOutputRouter
import com.translator.pocket.audio.AudioStreamRecorder
import com.translator.pocket.audio.LiveAudioTrackPlayer
import com.translator.pocket.audio.VadSegmenter
import com.translator.pocket.audio.WavEncoder
import com.translator.pocket.engine.BuiltinEngine
import com.translator.pocket.engine.CloudAiEngine
import com.translator.pocket.engine.GeminiLiveEngine
import com.translator.pocket.engine.ITranslationEngine
import com.translator.pocket.model.AppSettings
import com.translator.pocket.model.AudioOutputTarget
import com.translator.pocket.model.EngineType
import com.translator.pocket.model.TranslationMessage
import com.translator.pocket.model.TranslationMode
import com.translator.pocket.tts.EarphoneTtsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LiveTranslationService : Service() {

    companion object {
        private const val TAG = "LiveTranslationService"

        const val ACTION_START = "com.translator.pocket.ACTION_START"
        const val ACTION_STOP = "com.translator.pocket.ACTION_STOP"
        const val ACTION_SWITCH_MODE = "com.translator.pocket.ACTION_SWITCH_MODE"

        // 全域狀態流，讓 Activity 與 Service 雙向響應
        private val _isRunningFlow = MutableStateFlow(false)
        val isRunningFlow = _isRunningFlow.asStateFlow()

        private val _statusTextFlow = MutableStateFlow("待命中")
        val statusTextFlow = _statusTextFlow.asStateFlow()

        private val _messageFlow = MutableSharedFlow<TranslationMessage>(extraBufferCapacity = 64)
        val messageFlow = _messageFlow.asSharedFlow()

        var audioRouterInstance: AudioOutputRouter? = null
            private set
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var settings: AppSettings
    private var wakeLock: PowerManager.WakeLock? = null

    private var audioRecorder: AudioStreamRecorder? = null
    private var vadSegmenter: VadSegmenter? = null
    private var ttsManager: EarphoneTtsManager? = null
    private var liveAudioPlayer: LiveAudioTrackPlayer? = null
    private var geminiLiveEngine: GeminiLiveEngine? = null
    private var audioRouter: AudioOutputRouter? = null

    private lateinit var cloudEngine: CloudAiEngine
    private lateinit var builtinEngine: BuiltinEngine

    private var activeSourceLang = "ja"
    private var activeTargetLang = "zh-TW"
    private var activeMode = TranslationMode.ONE_WAY

    inner class LocalBinder : Binder() {
        fun getService(): LiveTranslationService = this@LiveTranslationService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)

        // 初始化音訊輸出路由 (耳機優先 / 貼耳聽筒 / 外放擴音 / 靜音)
        audioRouter = AudioOutputRouter(this).apply {
            setUserPreference(settings.audioOutputPreference)
        }
        audioRouterInstance = audioRouter

        // 初始化翻譯引擎
        cloudEngine = CloudAiEngine(
            groqApiKeyProvider = { settings.groqApiKey },
            geminiApiKeyProvider = { settings.geminiApiKey }
        )
        builtinEngine = BuiltinEngine(this)

        // 初始化 Gemini Live 真人雙向音訊串流引擎
        liveAudioPlayer = LiveAudioTrackPlayer()
        geminiLiveEngine = GeminiLiveEngine(
            apiKeyProvider = { settings.geminiApiKey },
            voiceName = settings.geminiLiveVoice,
            onAudioChunkReceived = { pcm24k ->
                val target = audioRouter?.activeTargetFlow?.value ?: AudioOutputTarget.AUTO_HEADPHONES
                if (target != AudioOutputTarget.MUTE) {
                    if (target == AudioOutputTarget.SPEAKER) {
                        audioRecorder?.isMutedByPlayback?.set(true)
                    }
                    liveAudioPlayer?.playChunk(pcm24k)
                }
            },
            onTranscriptReceived = { orig, trans ->
                serviceScope.launch {
                    val message = TranslationMessage(
                        originalText = orig,
                        sourceLangName = activeSourceLang,
                        translatedText = trans,
                        targetLangName = activeTargetLang
                    )
                    _messageFlow.emit(message)
                    updateNotification("Gemini 口譯：$trans")
                }
            },
            onConnectionStateChanged = { _, msg ->
                _statusTextFlow.value = msg
            }
        ).apply {
            onPlaybackStateChanged = { isPlaying ->
                if (!isPlaying) {
                    if (audioRouter?.activeTargetFlow?.value == AudioOutputTarget.SPEAKER) {
                        serviceScope.launch {
                            delay(350)
                            audioRecorder?.isMutedByPlayback?.set(false)
                        }
                    }
                }
            }
        }

        // 初始化語音朗讀 TTS
        ttsManager = EarphoneTtsManager(this) { isSpeaking ->
            if (isSpeaking) {
                _statusTextFlow.value = "正在播放翻譯語音..."
            } else {
                if (audioRouter?.activeTargetFlow?.value == AudioOutputTarget.SPEAKER) {
                    serviceScope.launch {
                        delay(350)
                        audioRecorder?.isMutedByPlayback?.set(false)
                    }
                }
                if (_isRunningFlow.value && settings.engineType != EngineType.GEMINI_LIVE) {
                    _statusTextFlow.value = "正在背景聆聽外語對話... (關螢幕依然運作)"
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForegroundTranslation()
            }
            ACTION_STOP -> {
                stopForegroundTranslation()
                stopSelf()
            }
            ACTION_SWITCH_MODE -> {
                switchTranslationMode()
            }
        }
        return START_STICKY // 系統被強制殺死後自動重啟
    }

    private fun startForegroundTranslation() {
        if (_isRunningFlow.value) return

        // 1. 取得 WakeLock 防止手機熄滅螢幕時 CPU 進入休眠
        acquireWakeLock()

        // 2. 建立常駐前台通知
        val notification = createNotification("正在背景聆聽中... 關閉螢幕仍持續口譯")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(PocketTranslatorApp.NOTIFICATION_ID, notification, type)
        } else {
            startForeground(PocketTranslatorApp.NOTIFICATION_ID, notification)
        }

        // 3. 讀取設定與語言
        val srcLangObj = settings.supportedLanguages.getOrElse(settings.sourceLangIndex) { settings.supportedLanguages[0] }
        val tgtLangObj = settings.supportedLanguages.getOrElse(settings.targetLangIndex) { settings.supportedLanguages[3] }
        activeSourceLang = srcLangObj.code
        activeTargetLang = tgtLangObj.code
        activeMode = settings.translationMode

        ttsManager?.setSpeechRate(settings.ttsSpeed)
        ttsManager?.setLanguage(activeTargetLang)

        // 4. 初始化 VAD 與錄音器
        vadSegmenter = VadSegmenter(settings.vadSensitivity) { pcmBytes ->
            if (settings.engineType != EngineType.GEMINI_LIVE) {
                handleCapturedSpeech(pcmBytes)
            }
        }

        audioRecorder = AudioStreamRecorder(vadSegmenter!!)

        if (settings.engineType == EngineType.GEMINI_LIVE) {
            liveAudioPlayer?.start()
            geminiLiveEngine?.start(activeSourceLang, activeTargetLang)
            audioRecorder?.onRawFrameCaptured = { frame, len ->
                geminiLiveEngine?.sendAudioFrame(frame, len)
            }
        }

        val success = audioRecorder?.startRecording() ?: false

        if (success) {
            _isRunningFlow.value = true
            if (settings.engineType == EngineType.GEMINI_LIVE) {
                _statusTextFlow.value = "Gemini Live 擬真同步口譯已啟動 (關螢幕可運作)"
            } else {
                _statusTextFlow.value = "正在背景聆聽外語對話... (關螢幕可持續運作)"
            }
        } else {
            _statusTextFlow.value = "麥克風啟動失敗，請確認錄音權限"
            stopForegroundTranslation()
        }
    }

    private fun handleCapturedSpeech(pcmBytes: ByteArray) {
        serviceScope.launch {
            _statusTextFlow.value = "偵測到語句，正在極速口譯中..."

            val wavBytes = WavEncoder.pcmToWav(pcmBytes)
            val engine: ITranslationEngine = if (settings.engineType == EngineType.CLOUD_AI && settings.groqApiKey.isNotBlank()) {
                cloudEngine
            } else {
                builtinEngine
            }

            val result = engine.translateSpeech(wavBytes, activeSourceLang, activeTargetLang)

            if (result.isSuccess && result.translatedText.isNotBlank()) {
                val message = TranslationMessage(
                    originalText = result.originalText,
                    sourceLangName = activeSourceLang,
                    translatedText = result.translatedText,
                    targetLangName = activeTargetLang
                )
                _messageFlow.emit(message)

                // 更新通知列顯示最新翻譯
                updateNotification("最新翻譯：${result.translatedText}")

                // 檢查音訊輸出目標 (靜音模式不朗讀)
                val target = audioRouter?.activeTargetFlow?.value ?: AudioOutputTarget.AUTO_HEADPHONES
                if (target != AudioOutputTarget.MUTE) {
                    if (target == AudioOutputTarget.SPEAKER) {
                        audioRecorder?.isMutedByPlayback?.set(true)
                    }
                    ttsManager?.speak(result.translatedText)
                }
            } else {
                val errorMsg = result.errorMessage ?: "翻譯無結果"
                Log.w(TAG, errorMsg)
                _statusTextFlow.value = "聆聽中 ($errorMsg)"
            }
        }
    }

    private fun switchTranslationMode() {
        if (activeMode == TranslationMode.ONE_WAY) {
            activeMode = TranslationMode.TWO_WAY
        } else {
            activeMode = TranslationMode.ONE_WAY
        }
        settings.translationMode = activeMode

        // 雙向對話時交換發話與收聽語言
        val temp = activeSourceLang
        activeSourceLang = activeTargetLang
        activeTargetLang = temp
        ttsManager?.setLanguage(activeTargetLang)

        updateNotification("已切換為：${if (activeMode == TranslationMode.ONE_WAY) "單向同傳" else "雙向對話"}")
    }

    private fun stopForegroundTranslation() {
        _isRunningFlow.value = false
        _statusTextFlow.value = "口譯已停止"

        audioRecorder?.onRawFrameCaptured = null
        audioRecorder?.stopRecording()
        audioRecorder = null
        vadSegmenter?.reset()
        vadSegmenter = null
        ttsManager?.stop()

        geminiLiveEngine?.stop()
        liveAudioPlayer?.stop()

        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "PocketTranslator::BgRecordWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire(12 * 60 * 60 * 1000L) // 最長保持 12 小時
            }
            Log.d(TAG, "已獲取 PARTIAL_WAKE_LOCK 保證熄屏持續錄音")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "釋放 WakeLock 警告", e)
        }
        wakeLock = null
    }

    private fun createNotification(content: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pOpenApp = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, LiveTranslationService::class.java).apply {
            action = ACTION_STOP
        }
        val pStop = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val switchIntent = Intent(this, LiveTranslationService::class.java).apply {
            action = ACTION_SWITCH_MODE
        }
        val pSwitch = PendingIntent.getService(
            this, 2, switchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, PocketTranslatorApp.CHANNEL_ID)
            .setContentTitle("隨身即時口譯 (關螢幕可運作)")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pOpenApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(R.drawable.ic_swap, "切換模式/語言", pSwitch)
            .addAction(R.drawable.ic_notification, "結束", pStop)
            .build()
    }

    private fun updateNotification(content: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(PocketTranslatorApp.NOTIFICATION_ID, createNotification(content))
    }

    override fun onDestroy() {
        stopForegroundTranslation()
        ttsManager?.release()
        ttsManager = null
        liveAudioPlayer?.stop()
        liveAudioPlayer = null
        geminiLiveEngine?.stop()
        geminiLiveEngine = null
        audioRouter?.release()
        audioRouter = null
        audioRouterInstance = null
        super.onDestroy()
    }
}
