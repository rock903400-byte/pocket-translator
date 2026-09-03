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
import com.translator.pocket.audio.GoogleStreamingRecognizer
import com.translator.pocket.audio.LiveAudioTrackPlayer
import com.translator.pocket.audio.VadSegmenter
import com.translator.pocket.audio.WavEncoder
import com.translator.pocket.engine.BuiltinEngine
import com.translator.pocket.engine.CloudAiEngine
import com.translator.pocket.engine.GeminiLiveEngine
import com.translator.pocket.engine.ITranslationEngine
import com.translator.pocket.engine.MlKitTranslatorCache
import com.translator.pocket.model.AppSettings
import com.translator.pocket.model.AudioOutputTarget
import com.translator.pocket.model.EngineType
import com.translator.pocket.model.InterimSubtitle
import com.translator.pocket.model.LanguageCodes
import com.translator.pocket.model.TranslationMessage
import com.translator.pocket.model.TranslationMode
import com.translator.pocket.subtitle.LiveSubtitleState
import com.translator.pocket.subtitle.StreamingTranslationPipeline
import com.translator.pocket.tts.EarphoneTtsManager
import com.translator.pocket.util.LatencyLog
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
        const val ACTION_SWITCH_MODE = "com.translator.pocket.ACTION_SWITCH_MODE"

        /** 對話模式：只交換發話方向，不切換 ONE_WAY/TWO_WAY。 */
        const val ACTION_SWAP_DIRECTION = "com.translator.pocket.ACTION_SWAP_DIRECTION"

        /** 對話模式：明確指定由哪一方發話（輪次按鈕使用，而非按住說話）。 */
        const val ACTION_SET_TURN = "com.translator.pocket.ACTION_SET_TURN"
        const val EXTRA_TURN_INDEX = "com.translator.pocket.EXTRA_TURN_INDEX"

        /** 沉澱計時器的心跳間隔 */
        private const val TICK_INTERVAL_MS = 250L

        /** 通知列重建的最小間隔。句中定案後每秒可能有數次更新，不節流會很浪費。 */
        private const val NOTIFICATION_THROTTLE_MS = 1200L

        /** 外放播完後等喇叭餘音散去再恢復收音。 */
        private const val ECHO_RESUME_DELAY_MS = 300L

        // 全域狀態流，讓 Activity 與 Service 雙向響應
        private val _isRunningFlow = MutableStateFlow(false)
        val isRunningFlow = _isRunningFlow.asStateFlow()

        private val _statusTextFlow = MutableStateFlow("待命中")
        val statusTextFlow = _statusTextFlow.asStateFlow()

        private val _messageFlow = MutableSharedFlow<TranslationMessage>(extraBufferCapacity = 64)
        val messageFlow = _messageFlow.asSharedFlow()

        /**
         * 進行中、尚未定案的字幕。null 代表目前沒有進行中的語句。
         * 用 StateFlow 而非 SharedFlow：它每秒更新數次，收集端需要的是
         * 「永遠只看到最新狀態」，而不是漏幀或回放一整批陳舊資料。
         */
        private val _interimFlow = MutableStateFlow<InterimSubtitle?>(null)
        val interimFlow = _interimFlow.asStateFlow()

        var audioRouterInstance: AudioOutputRouter? = null
            private set
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var settings: AppSettings
    private var wakeLock: PowerManager.WakeLock? = null

    private var audioRecorder: AudioStreamRecorder? = null
    private var vadSegmenter: VadSegmenter? = null
    private var googleStreamingRecognizer: GoogleStreamingRecognizer? = null
    private var ttsManager: EarphoneTtsManager? = null
    private var liveAudioPlayer: LiveAudioTrackPlayer? = null
    private var geminiLiveEngine: GeminiLiveEngine? = null
    private var audioRouter: AudioOutputRouter? = null

    // 即時字幕管線（僅 Google 原生模式使用）
    private var subtitleState: LiveSubtitleState? = null
    private var pipeline: StreamingTranslationPipeline? = null
    private var pipelineScope: CoroutineScope? = null
    private val tickHandler = Handler(Looper.getMainLooper())
    private var tickRunnable: Runnable? = null

    private lateinit var cloudEngine: CloudAiEngine
    private lateinit var builtinEngine: BuiltinEngine

    private var activeSourceLang = "ja"
    private var activeTargetLang = "zh-TW"
    private var activeMode = TranslationMode.ONE_WAY

    // 對話模式的兩個固定語言（來自設定的語言選單），與目前由哪一方發話。
    // activeSourceLang/activeTargetLang 會隨輪次改變，這兩個不會。
    private var turnLanguageA = "ja"
    private var turnLanguageB = "zh-TW"
    private var currentTurnIndex = 0

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
            geminiApiKeyProvider = { settings.geminiApiKey },
            geminiModelProvider = { settings.geminiModelName }
        )
        builtinEngine = BuiltinEngine(this)

        // 初始化 Gemini Live 真人雙向音訊串流引擎
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
            onConnectionStateChanged = { isConnected, msg ->
                _statusTextFlow.value = msg
                if (!isConnected && (msg.contains("失敗") || msg.contains("異常") || msg.contains("拒絕") || msg.contains("中斷"))) {
                    serviceScope.launch {
                        _messageFlow.emit(
                            TranslationMessage(
                                originalText = "連線提示",
                                sourceLangName = "系統",
                                translatedText = "⚠️ $msg\n💡 建議：確認 API Key 已開通 Live API，或至設定切換為【高速 AI 模式】改用 Gemini Flash 極速口譯。",
                                targetLangName = "系統"
                            )
                        )
                    }
                }
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

            // Google 原生模式沒有自己的 AudioRecord 可以靜音（isMutedByPlayback 在這個模式下是 no-op），
            // 而 SpeechRecognizer 會確實聽到手機外放的譯文，進而幻聽、再翻、再唸，形成迴圈。
            // 只在外放時暫停收音；耳機與聽筒漏音可忽略，暫停反而會漏掉真實語音。
            if (settings.engineType == EngineType.BUILTIN &&
                audioRouter?.activeTargetFlow?.value == AudioOutputTarget.SPEAKER
            ) {
                if (isSpeaking) {
                    googleStreamingRecognizer?.pause()
                } else {
                    serviceScope.launch {
                        delay(ECHO_RESUME_DELAY_MS)
                        googleStreamingRecognizer?.resume()
                    }
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
            ACTION_SWAP_DIRECTION -> {
                swapActiveDirection()
            }
            ACTION_SET_TURN -> {
                val index = intent.getIntExtra(EXTRA_TURN_INDEX, 0)
                setSpeakingTurn(index)
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
        turnLanguageA = srcLangObj.code
        turnLanguageB = tgtLangObj.code
        currentTurnIndex = 0

        ttsManager?.setSpeechRate(settings.ttsSpeed)
        ttsManager?.setLanguage(activeTargetLang)

        // 若為 Google 原生模式：採用流式語音辨識 (同 Google 翻譯 App)
        if (settings.engineType == EngineType.BUILTIN) {
            startStreamingSubtitles()
            return
        }

        // 4. 初始化 VAD 與錄音器 (Groq / Gemini 模式)
        vadSegmenter = VadSegmenter(settings.vadSensitivity) { pcmBytes ->
            if (settings.engineType != EngineType.GEMINI_LIVE || geminiLiveEngine?.isConnectionReady != true) {
                handleCapturedSpeech(pcmBytes)
            }
        }

        audioRecorder = AudioStreamRecorder(vadSegmenter!!)

        var lastRmsUpdate = 0L
        audioRecorder?.onAudioLevelChanged = { rms ->
            val now = System.currentTimeMillis()
            if (now - lastRmsUpdate > 160L && _isRunningFlow.value) {
                lastRmsUpdate = now
                val wave = when {
                    rms > 700 -> " ▃▅▆█ [聲音宏亮]"
                    rms > 380 -> " ▃▅▆ [說話中]"
                    rms > 200 -> " ▃▅ [接收中]"
                    rms > 70  -> " ▂ [環境底噪]"
                    else      -> "   [安靜等待中]"
                }
                val currentStatus = _statusTextFlow.value
                if (currentStatus.startsWith("正在背景聆聽") || currentStatus.startsWith("正在聆聽") || currentStatus.contains("[")) {
                    _statusTextFlow.value = "正在聆聽對話$wave"
                }
            }
        }

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
            val hasApiKey = settings.groqApiKey.isNotBlank() || settings.geminiApiKey.isNotBlank()
            val engine: ITranslationEngine = if (hasApiKey) {
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
                val errorMsg = result.errorMessage ?: "未辨識到清晰語音"
                Log.w(TAG, errorMsg)
                _statusTextFlow.value = "⚠️ $errorMsg"

                val hint = if (errorMsg.contains("API")) {
                    "⚠️ $errorMsg"
                } else {
                    "⚠️ 收到語句但未轉譯成功 ($errorMsg)。請稍後再試或更換引擎。"
                }
                _messageFlow.emit(
                    TranslationMessage(
                        originalText = "系統提示",
                        sourceLangName = "提示",
                        translatedText = hint,
                        targetLangName = "提示"
                    )
                )
            }
        }
    }

    private fun switchTranslationMode() {
        activeMode = if (activeMode == TranslationMode.ONE_WAY) {
            TranslationMode.TWO_WAY
        } else {
            TranslationMode.ONE_WAY
        }
        settings.translationMode = activeMode
        currentTurnIndex = 0

        updateNotification("已切換為：${if (activeMode == TranslationMode.ONE_WAY) "即時轉錄" else "雙向對話"}")
    }

    /**
     * 只交換發話方向，不改變 ONE_WAY/TWO_WAY。
     * 舊版的 switchTranslationMode 把「切模式」與「交換語言」綁在一起送出，
     * 導致在對話模式下按一次通知列的交換鈕，模式就被切回單向。
     */
    private fun swapActiveDirection() {
        if (settings.engineType == EngineType.BUILTIN && activeMode == TranslationMode.TWO_WAY) {
            setSpeakingTurn(1 - currentTurnIndex)
            return
        }

        val temp = activeSourceLang
        activeSourceLang = activeTargetLang
        activeTargetLang = temp
        ttsManager?.setLanguage(activeTargetLang)
        updateNotification("已交換發話方向")
    }

    /**
     * 對話模式下明確指定由哪一方發話。用鎖定式按鈕而非按住說話 ——
     * 手機在兩人之間傳遞、或平放在桌上時，按住說話是錯的互動。
     *
     * SpeechRecognizer 一次只吃一個語言，判定必須發生在辨識之前，
     * 所以這裡不做自動語言偵測，只做明確切換。
     */
    private fun setSpeakingTurn(index: Int) {
        if (settings.engineType != EngineType.BUILTIN) return
        if (!_isRunningFlow.value) return
        if (index == currentTurnIndex) return

        currentTurnIndex = index
        if (index == 0) {
            activeSourceLang = turnLanguageA
            activeTargetLang = turnLanguageB
        } else {
            activeSourceLang = turnLanguageB
            activeTargetLang = turnLanguageA
        }

        pipeline?.setLanguages(activeSourceLang, activeTargetLang)
        subtitleState?.reset()
        _interimFlow.value = null
        ttsManager?.setLanguage(activeTargetLang)
        googleStreamingRecognizer?.setLanguage(activeSourceLang)

        // 兩個方向的模型已在 session 開始時預先下載，這裡通常是瞬間完成
        prepareOfflineTranslation()

        updateNotification("目前發話方：$activeSourceLang -> $activeTargetLang")
    }

    /**
     * 預先備妥 ML Kit 端上翻譯模型並暖機。失敗不中止 session ——
     * BuiltinEngine.translateText 會自動回退到線上免費翻譯通道。
     */
    private fun prepareOfflineTranslation() {
        serviceScope.launch {
            reportPrepareResult(builtinEngine.prepare(activeSourceLang, activeTargetLang) { mb ->
                _statusTextFlow.value = "首次使用需下載離線語言模型 (約 ${mb}MB)，請稍候..."
            })

            // 對話模式：把反方向的模型也一起準備好，輪次切換才會是瞬間的
            if (activeMode == TranslationMode.TWO_WAY) {
                builtinEngine.prepare(activeTargetLang, activeSourceLang) { mb ->
                    _statusTextFlow.value = "首次使用需下載離線語言模型 (約 ${mb}MB)，請稍候..."
                }
            }
        }
    }

    private fun reportPrepareResult(result: MlKitTranslatorCache.Prepare) {
        when (result) {
            is MlKitTranslatorCache.Prepare.Ready -> {
                Log.d(TAG, "端上翻譯模型已就緒並完成暖機")
            }

            is MlKitTranslatorCache.Prepare.Failed -> {
                Log.w(TAG, "端上模型準備失敗，改用線上翻譯: ${result.reason}")
                _statusTextFlow.value = "離線模型無法下載，已改用線上翻譯"
            }

            is MlKitTranslatorCache.Prepare.Unsupported -> {
                Log.w(TAG, "ML Kit 不支援語言: ${result.langCode}，改用線上翻譯")
                _statusTextFlow.value = "此語言無離線模型，已改用線上翻譯"
            }
        }
    }

    /**
     * Google 原生模式：串流辨識 -> 增量翻譯 -> 即時字幕 -> 定案朗讀。
     *
     * 舊版把 partial 假設丟掉、只在辨識器判定整句結束後才翻譯，
     * 延遲下限是「講完 + 1.5 秒」。這裡改成邊講邊翻，
     * 並用自己的沉澱計時器提早定案，不等辨識器的 endpointing。
     */
    private fun startStreamingSubtitles() {
        googleStreamingRecognizer?.stop()
        stopStreamingSubtitles()

        val state = LiveSubtitleState()
        subtitleState = state

        // session 級 scope：停止時必須確實砍掉還在飛的翻譯。
        // 不能用活整個 Service 的 serviceScope，否則按下停止後還會冒出字幕。
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        pipelineScope = scope

        pipeline = StreamingTranslationPipeline(
            engine = builtinEngine,
            scope = scope,
            onInterim = { utteranceId, source, translated ->
                _interimFlow.value = InterimSubtitle(
                    utteranceId = utteranceId,
                    sourceText = source,
                    translatedText = translated,
                    sourceLangName = activeSourceLang,
                    targetLangName = activeTargetLang
                )
            },
            onCommitted = { _, source, translated ->
                onSegmentCommitted(source, translated)
            },
            onInterimCleared = {
                _interimFlow.value = null
            }
        ).apply { setLanguages(activeSourceLang, activeTargetLang) }

        googleStreamingRecognizer = GoogleStreamingRecognizer(
            context = this,
            onPartialText = { partial ->
                LatencyLog.markOnce(LatencyLog.EVENT_FIRST_PARTIAL)
                pipeline?.submit(state.onPartial(partial, SystemClock.elapsedRealtime()))
            },
            onFinalText = { finalText ->
                pipeline?.submit(state.onFinal(finalText, SystemClock.elapsedRealtime()))
            },
            onAborted = {
                // 辨識器放棄了這一段，但已經聽到的文字是真的，該定案就定案
                pipeline?.submit(state.onUtteranceAborted())
            },
            onRmsChanged = { _ -> },
            onStateChanged = { text ->
                _statusTextFlow.value = text
            },
            onFatalError = { message ->
                handleRecognizerFatalError(message)
            }
        )
        googleStreamingRecognizer?.start(
            languageCode = activeSourceLang,
            preferOfflineRecognition = settings.preferOfflineRecognition,
            silenceLengthMs = if (activeMode == TranslationMode.TWO_WAY) {
                GoogleStreamingRecognizer.SILENCE_CONVERSATION_MS
            } else {
                GoogleStreamingRecognizer.SILENCE_TRANSCRIBE_MS
            }
        )

        startSettleTicker()

        _isRunningFlow.value = true
        _statusTextFlow.value = "Google 原生即時口譯已啟動 (邊講邊譯)"
        prepareOfflineTranslation()
    }

    /**
     * 語音辨識無法繼續（缺權限、裝置不支援、連續失敗）。
     * 舊版遇到這些情況會每 250ms 無限重試，使用者只看到畫面永遠停在「聆聽中」。
     */
    private fun handleRecognizerFatalError(message: String) {
        Log.e(TAG, "語音辨識中止: $message")
        _statusTextFlow.value = "⚠️ $message"
        serviceScope.launch {
            _messageFlow.emit(
                TranslationMessage(
                    originalText = "語音辨識",
                    sourceLangName = "系統",
                    translatedText = "⚠️ $message",
                    targetLangName = "系統"
                )
            )
        }
        stopForegroundTranslation()
    }

    /**
     * 沉澱計時器心跳。辨識器的 endpointing 常要 1.5~2 秒，
     * 這裡讓「我們」在對方安靜下來時就先定案。
     */
    private fun startSettleTicker() {
        stopSettleTicker()
        val runnable = object : Runnable {
            override fun run() {
                val state = subtitleState
                if (state != null && _isRunningFlow.value) {
                    pipeline?.submit(state.onTick(SystemClock.elapsedRealtime()))
                    tickHandler.postDelayed(this, TICK_INTERVAL_MS)
                }
            }
        }
        tickRunnable = runnable
        tickHandler.postDelayed(runnable, TICK_INTERVAL_MS)
    }

    private fun stopSettleTicker() {
        tickRunnable?.let { tickHandler.removeCallbacks(it) }
        tickRunnable = null
    }

    /**
     * 一段話定案：進入對話紀錄、更新通知、朗讀。
     *
     * ML Kit 端上模型只有簡體中文，目標語言是繁體時，這裡先立即顯示簡體版本
     * （不delay任何東西），再另外打線上通道升級成繁體、用同一個訊息 id 換掉那一列。
     */
    private fun onSegmentCommitted(originalText: String, translatedText: String) {
        if (translatedText.isBlank()) return

        serviceScope.launch {
            val needsTraditionalUpgrade = LanguageCodes.isTraditionalChinese(activeTargetLang)

            val message = TranslationMessage(
                originalText = originalText,
                sourceLangName = activeSourceLang,
                translatedText = translatedText,
                targetLangName = activeTargetLang,
                isProvisional = needsTraditionalUpgrade
            )
            _messageFlow.emit(message)
            updateNotification("口譯：$translatedText")

            val target = audioRouter?.activeTargetFlow?.value ?: AudioOutputTarget.AUTO_HEADPHONES
            if (target != AudioOutputTarget.MUTE) {
                // 串流版本：佇列積太多就跳到最新，避免愈落愈後面。
                // 不等繁體升級 —— 中文 TTS 對簡繁輸入的發音相同，聽感無差。
                ttsManager?.speakStreaming(translatedText)
            }

            if (needsTraditionalUpgrade) {
                val upgraded = builtinEngine.upgradeToTraditionalChinese(originalText, activeSourceLang)
                if (!upgraded.isNullOrBlank() && upgraded != translatedText) {
                    _messageFlow.emit(message.copy(translatedText = upgraded, isProvisional = false))
                }
            }
        }
    }

    private fun stopStreamingSubtitles() {
        stopSettleTicker()
        pipeline?.close()
        pipeline = null
        pipelineScope?.cancel()
        pipelineScope = null
        subtitleState = null
        _interimFlow.value = null
    }

    private fun stopForegroundTranslation() {
        _isRunningFlow.value = false
        _statusTextFlow.value = "口譯已停止"

        googleStreamingRecognizer?.stop()
        googleStreamingRecognizer = null
        stopStreamingSubtitles()

        audioRecorder?.onRawFrameCaptured = null
        audioRecorder?.stopRecording()
        audioRecorder = null
        vadSegmenter?.reset()
        vadSegmenter = null
        ttsManager?.stop()

        geminiLiveEngine?.stop()
        liveAudioPlayer?.stop()
        builtinEngine.release()
        LatencyLog.reset()

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

        // 對話模式下交換鈕只切換發話方向；單向模式下切換 轉錄/對話
        val switchIntent = Intent(this, LiveTranslationService::class.java).apply {
            action = if (activeMode == TranslationMode.TWO_WAY) ACTION_SWAP_DIRECTION else ACTION_SWITCH_MODE
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

    /**
     * 節流：createNotification 每次都要重建三個 PendingIntent 與整個 Notification，
     * 句中定案後每秒可能觸發數次。通知列少更新一兩次沒有任何影響。
     */
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
        ttsManager?.release()
        ttsManager = null
        liveAudioPlayer?.stop()
        liveAudioPlayer = null
        geminiLiveEngine?.stop()
        geminiLiveEngine = null
        builtinEngine.release()
        audioRouter?.release()
        audioRouter = null
        audioRouterInstance = null
        super.onDestroy()
    }
}
