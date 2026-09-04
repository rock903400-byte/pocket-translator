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
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.translator.pocket.MainActivity
import com.translator.pocket.PocketTranslatorApp
import com.translator.pocket.R
import com.translator.pocket.audio.AudioOutputRouter
import com.translator.pocket.audio.AudioStreamRecorder
import com.translator.pocket.audio.LiveAudioTrackPlayer
import com.translator.pocket.engine.GeminiLiveEngine
import com.translator.pocket.model.AppSettings
import com.translator.pocket.model.AudioOutputTarget
import com.translator.pocket.model.InterimSubtitle
import com.translator.pocket.model.TranslationMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

        private const val NOTIFICATION_THROTTLE_MS = 1200L

        private val _isRunningFlow = MutableStateFlow(false)
        val isRunningFlow = _isRunningFlow.asStateFlow()

        private val _statusTextFlow = MutableStateFlow("待命中")
        val statusTextFlow = _statusTextFlow.asStateFlow()

        private val _messageFlow = MutableSharedFlow<TranslationMessage>(extraBufferCapacity = 64)
        val messageFlow = _messageFlow.asSharedFlow()

        private val _interimFlow = MutableStateFlow<InterimSubtitle?>(null)
        val interimFlow = _interimFlow.asStateFlow()

        var audioRouterInstance: AudioOutputRouter? = null
            private set
    }

    private var serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var settings: AppSettings
    private var wakeLock: PowerManager.WakeLock? = null

    private var audioRecorder: AudioStreamRecorder? = null
    private var liveAudioPlayer: LiveAudioTrackPlayer? = null
    private var geminiLiveEngine: GeminiLiveEngine? = null
    private var audioRouter: AudioOutputRouter? = null

    private var activeSourceLang = "ja"
    private var activeTargetLang = "zh-TW"

    inner class LocalBinder : Binder() {
        fun getService(): LiveTranslationService = this@LiveTranslationService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)

        audioRouter = AudioOutputRouter(this).apply {
            setUserPreference(settings.audioOutputPreference)
        }
        audioRouterInstance = audioRouter

        liveAudioPlayer = LiveAudioTrackPlayer()
        geminiLiveEngine = GeminiLiveEngine(
            apiKeyProvider = { settings.geminiApiKey },
            modelNameProvider = { settings.geminiLiveModelName },
            onAudioChunkReceived = { pcm24k ->
                val target = audioRouter?.activeTargetFlow?.value ?: AudioOutputTarget.AUTO_HEADPHONES
                if (target != AudioOutputTarget.MUTE) {
                    if (target == AudioOutputTarget.SPEAKER) {
                        audioRecorder?.isMutedByPlayback?.set(true)
                    }
                    liveAudioPlayer?.playChunk(pcm24k)
                }
            },
            onTranscriptReceived = { id, orig, trans ->
                serviceScope.launch {
                    val message = TranslationMessage(
                        id = id,
                        originalText = orig,
                        sourceLangName = activeSourceLang,
                        translatedText = trans,
                        targetLangName = activeTargetLang
                    )
                    _messageFlow.emit(message)
                    _interimFlow.value = null
                    updateNotification("Gemini 口譯：$trans")
                }
            },
            onConnectionStateChanged = { isConnected, msg ->
                _statusTextFlow.value = msg
                if (!isConnected && (msg.contains("失敗") || msg.contains("異常") || msg.contains("拒絕") || msg.contains("中斷"))) {
                    serviceScope.launch {
                        _messageFlow.emit(
                            TranslationMessage(
                                originalText = "連線提示",
                                sourceLangName = "系統",
                                translatedText = "⚠️ $msg\n💡 建議：確認 API Key 已開通 Live API 並檢查網路連線。",
                                targetLangName = "系統"
                            )
                        )
                    }
                }
            },
            onInterimReceived = { id, orig, trans ->
                // 即時字幕：收到逐字稿立刻顯示，不等 800ms 定案（StateFlow conflated 不怕洗版）
                // id 與最終 commit 共用，確保「同一句」可追蹤；commit 後 engine 會 +1
                _interimFlow.value = InterimSubtitle(
                    utteranceId = id,
                    sourceText = orig.ifBlank { "…" },
                    translatedText = trans.ifBlank { "翻譯中…" },
                    sourceLangName = activeSourceLang,
                    targetLangName = activeTargetLang
                )
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startForegroundTranslation()
            ACTION_STOP -> {
                stopForegroundTranslation()
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startForegroundTranslation() {
        if (_isRunningFlow.value) return

        if (settings.geminiApiKey.isBlank()) {
            _statusTextFlow.value = "請先在設定中填入 Gemini API Key"
            return
        }

        acquireWakeLock()

        val notification = createNotification("Gemini Live 同步口譯啟動中...")
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

        val srcLangObj = settings.supportedLanguages.getOrElse(settings.sourceLangIndex) { settings.supportedLanguages[0] }
        val tgtLangObj = settings.supportedLanguages.getOrElse(settings.targetLangIndex) { settings.supportedLanguages[3] }
        activeSourceLang = srcLangObj.code
        activeTargetLang = tgtLangObj.code

        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        audioRecorder = AudioStreamRecorder()
        var lastRmsUpdate = 0L
        audioRecorder?.onAudioLevelChanged = { rms ->
            val now = System.currentTimeMillis()
            if (now - lastRmsUpdate > 160L && _isRunningFlow.value) {
                lastRmsUpdate = now
                val wave = when {
                    rms > 700 -> " ▃▅▆█ [聲音宏亮]"
                    rms > 380 -> " ▃▅▆ [說話中]"
                    rms > 200 -> " ▃▅ [接收中]"
                    rms > 70 -> " ▂ [環境底噪]"
                    else -> "   [安靜等待中]"
                }
                val currentStatus = _statusTextFlow.value
                if (currentStatus.startsWith("正在背景聆聽") || currentStatus.startsWith("正在聆聽") || currentStatus.contains("[")) {
                    _statusTextFlow.value = "正在聆聽對話$wave"
                }
            }
        }

        liveAudioPlayer?.start()
        geminiLiveEngine?.start(activeSourceLang, activeTargetLang)
        audioRecorder?.onRawFrameCaptured = { frame, len ->
            geminiLiveEngine?.sendAudioFrame(frame, len)
        }

        val success = audioRecorder?.startRecording() ?: false

        if (success) {
            _isRunningFlow.value = true
            _statusTextFlow.value = "Gemini Live 擬真同步口譯已啟動 (關螢幕可運作)"
        } else {
            _statusTextFlow.value = "麥克風啟動失敗，請確認錄音權限"
            stopForegroundTranslation()
        }
    }

    private fun stopForegroundTranslation() {
        _isRunningFlow.value = false
        _statusTextFlow.value = "口譯已停止"

        audioRecorder?.onRawFrameCaptured = null
        audioRecorder?.stopRecording()
        audioRecorder = null

        geminiLiveEngine?.stop()
        liveAudioPlayer?.stop()
        _interimFlow.value = null

        releaseWakeLock()
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            Log.w(TAG, "stopForeground 失敗", e)
        }
        serviceScope.cancel()
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "PocketTranslator::BgRecordWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire(12 * 60 * 60 * 1000L)
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

        return NotificationCompat.Builder(this, PocketTranslatorApp.CHANNEL_ID)
            .setContentTitle("隨身即時口譯 (關螢幕可運作)")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pOpenApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(R.drawable.ic_notification, "結束", pStop)
            .build()
    }

    @Volatile
    private var lastNotificationMs = 0L

    private fun updateNotification(content: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotificationMs < NOTIFICATION_THROTTLE_MS) return
        lastNotificationMs = now

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(PocketTranslatorApp.NOTIFICATION_ID, createNotification(content))
    }

    override fun onDestroy() {
        stopForegroundTranslation()
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
