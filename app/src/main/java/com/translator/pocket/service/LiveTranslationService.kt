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
import com.translator.pocket.engine.RestTranslator
import com.translator.pocket.model.AppSettings
import com.translator.pocket.model.AudioOutputTarget
import com.translator.pocket.model.InterimPolicy
import com.translator.pocket.model.InterimSubtitle
import com.translator.pocket.model.TranslationMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

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
    private var transcribeEngine: GeminiLiveEngine? = null
    private lateinit var restTranslator: RestTranslator
    private var audioRouter: AudioOutputRouter? = null

    // C 線暫存翻譯管線：A 線原文去抖後打 REST，結果只在同語句有效時才顯示
    private var partialScope: CoroutineScope? = null
    private var partialInbox: Channel<String>? = null
    private val partialSeq = AtomicLong(0)
    private val transcribeGeneration = AtomicLong(1L)

    /** B 線最後一次狀態（A 線恢復時還原用，避免異常文霸佔狀態列）。 */
    @Volatile
    private var lastBStatus = "待命中"
    @Volatile
    private var partialLastSent = ""
    @Volatile
    private var partialLastSentMs = 0L

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
        restTranslator = RestTranslator(apiKeyProvider = { settings.geminiApiKey })
        // A 線：transcribe 即時原文（TEXT，只跑字不跑音）。失敗只寫狀態不洗訊息流。
        transcribeEngine = GeminiLiveEngine(
            apiKeyProvider = { settings.geminiApiKey },
            modelNameProvider = { "gemini-3.5-transcribe-live" },
            onAudioChunkReceived = { },
            // A 線 final（endpoint 全文）：只提前更新原文區，不觸發 commit；
            // 語句生命週期仍歸 B 線定案所有。
            onTranscriptReceived = { _, orig, _ ->
                if (orig.isNotBlank()) {
                    val gen = transcribeGeneration.get()
                    val cur = _interimFlow.value
                    _interimFlow.value = if (cur == null || cur.utteranceId != gen) {
                        InterimSubtitle(
                            utteranceId = gen,
                            sourceText = orig,
                            translatedText = cur?.translatedText ?: InterimPolicy.TRANSLATING_PLACEHOLDER,
                            sourceLangName = activeSourceLang,
                            targetLangName = activeTargetLang
                        )
                    } else {
                        cur.copy(sourceText = orig)
                    }
                }
            },
            onConnectionStateChanged = { isConnected, msg ->
                if (!isConnected && (msg.contains("失敗") || msg.contains("拒絕") || msg.contains("中斷"))) {
                    _statusTextFlow.value = "即時字幕連線異常（翻譯語音不受影響）：$msg"
                    Log.w(TAG, "transcribe 線異常: $msg")
                } else if (isConnected) {
                    // 恢復時把被異常文霸佔的狀態列還給 B 線
                    InterimPolicy.restoreAfterAnomaly(_statusTextFlow.value, lastBStatus)?.let {
                        _statusTextFlow.value = it
                    }
                }
            },
            onInterimReceived = { _, _, _ -> },
            onTranscribeInterim = { text ->
                val gen = transcribeGeneration.get()
                val cur = _interimFlow.value
                _interimFlow.value = InterimSubtitle(
                    utteranceId = gen,
                    sourceText = text,
                    translatedText = cur?.takeIf { it.utteranceId == gen }?.translatedText ?: "翻譯中…",
                    sourceLangName = activeSourceLang,
                    targetLangName = activeTargetLang
                )
                partialInbox?.trySend(text)
            }
        )
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
                    // 下一句用新 generation，舊的暫存 REST 結果回來直接丟
                    transcribeGeneration.incrementAndGet()
                    updateNotification("Gemini 口譯：$trans")
                }
            },
            onConnectionStateChanged = { isConnected, msg ->
                lastBStatus = msg
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
                // id 與最終 commit 共用，確保「同一句」可追蹤；commit 後 engine 會 +1。
                // 譯文沿用 InterimPolicy：B 還沒譯文時保留畫面上的 REST 暫存，不洗回等待字。
                val cur = _interimFlow.value
                val (source, translated) = InterimPolicy.mergeBInterim(
                    curSource = cur?.sourceText.orEmpty(),
                    curTrans = cur?.translatedText.orEmpty(),
                    bOrig = orig,
                    bTrans = trans
                )
                _interimFlow.value = InterimSubtitle(
                    utteranceId = id,
                    sourceText = source,
                    translatedText = translated,
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
        var lastQueueLogMs = 0L
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
                // 播放儀表：隊列/丟棄數直接進狀態列，卡頓時一眼看出是網路慢還是喇叭跟不上
                val player = liveAudioPlayer
                val queueInfo = if (player != null) {
                    "・播放 ${player.queuedChunks()}/20・丟 ${player.droppedCount()}"
                } else ""
                val currentStatus = _statusTextFlow.value
                if (currentStatus.startsWith("正在背景聆聽") || currentStatus.startsWith("正在聆聽") || currentStatus.contains("[")) {
                    _statusTextFlow.value = "正在聆聽對話$wave$queueInfo"
                }
                // logcat 每 5 秒一行，供 adb 排查
                if (now - lastQueueLogMs > 5000L) {
                    lastQueueLogMs = now
                    Log.d(TAG, "播放儀表 queued=${player?.queuedChunks() ?: -1} dropped=${player?.droppedCount() ?: -1}")
                }
            }
        }

        startPartialPipeline()

        liveAudioPlayer?.start()
        geminiLiveEngine?.start(activeSourceLang, activeTargetLang)
        transcribeEngine?.start(activeSourceLang, activeTargetLang)
        audioRecorder?.onRawFrameCaptured = { frame, len ->
            // 同一包同時餵 B 線（翻譯+語音）與 A 線（即時原文）；
            // sendAudioFrame 內部會拷貝，不會互踩
            geminiLiveEngine?.sendAudioFrame(frame, len)
            transcribeEngine?.sendAudioFrame(frame, len)
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

    /**
     * C 線：暫存原文去抖後打 REST，結果只在同語句（同 generation、無更新）有效時才顯示。
     * 任何失敗都靜默略過，原文照滾、B 線照出，暫存譯文寧缺勿錯。
     */
    private fun startPartialPipeline() {
        partialScope?.cancel()
        partialSeq.set(0)
        partialLastSent = ""
        partialLastSentMs = 0L
        transcribeGeneration.incrementAndGet()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        partialScope = scope
        val inbox = Channel<String>(Channel.CONFLATED)
        partialInbox = inbox
        scope.launch {
            for (text in inbox) {
                val mySeq = partialSeq.incrementAndGet()
                delay(RestTranslator.DEBOUNCE_MS)
                if (mySeq != partialSeq.get()) continue
                val gen = transcribeGeneration.get()
                val snapshot = text.trim()
                val now = SystemClock.elapsedRealtime()
                if (!RestTranslator.shouldTranslate(now, partialLastSentMs, snapshot, partialLastSent)) continue
                partialLastSent = snapshot
                partialLastSentMs = SystemClock.elapsedRealtime()
                val translated = restTranslator.translatePartial(snapshot, activeTargetLang)
                if (mySeq != partialSeq.get() || gen != transcribeGeneration.get()) continue
                if (!translated.isNullOrBlank()) {
                    val cur = _interimFlow.value
                    if (cur != null && cur.utteranceId == gen) {
                        _interimFlow.value = cur.copy(translatedText = translated)
                    }
                }
            }
        }
    }

    private fun stopPartialPipeline() {
        try {
            partialInbox?.close()
        } catch (e: Exception) {
            // ignore
        }
        partialInbox = null
        partialScope?.cancel()
        partialScope = null
    }

    private fun stopForegroundTranslation() {
        _isRunningFlow.value = false
        _statusTextFlow.value = "口譯已停止"

        audioRecorder?.onRawFrameCaptured = null
        audioRecorder?.stopRecording()
        audioRecorder = null

        geminiLiveEngine?.stop()
        transcribeEngine?.stop()
        stopPartialPipeline()
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
        transcribeEngine?.stop()
        transcribeEngine = null
        audioRouter?.release()
        audioRouter = null
        audioRouterInstance = null
        super.onDestroy()
    }
}
