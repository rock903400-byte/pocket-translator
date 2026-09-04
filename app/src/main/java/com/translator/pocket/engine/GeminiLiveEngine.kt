package com.translator.pocket.engine

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Google Gemini Live API (雙向 WebSocket 實時語音串流引擎)
 * 端到端 聲音進 -> 聲音出 (Audio-in -> Audio-out)
 *
 * 顯示名「Gemini 3.5 Live Translate」即 models/gemini-3.5-live-translate-preview（Rate Limit 頁面已確認有 1.34K TPM 使用）
 * 經實測此 Key 的 live-translate-preview 以 AUDIO+translationConfig+thinkingBudget0 可穩定 setupComplete 並回 input/outputTranscription
 * - 音訊輸入為 realtimeInput.audio (16kHz PCM)，輸出為 24kHz PCM
 * - 伺服器訊息以 binary frame 傳回，內容為 UTF-8 JSON
 */
class GeminiLiveEngine(
    private val apiKeyProvider: () -> String,
    private val modelNameProvider: () -> String = { DEFAULT_LIVE_MODEL },
    private val onAudioChunkReceived: (ByteArray) -> Unit,
    private val onTranscriptReceived: (originalText: String, translatedText: String) -> Unit,
    private val onConnectionStateChanged: (isConnected: Boolean, message: String) -> Unit,
    private val onInterimReceived: (originalText: String, translatedText: String) -> Unit = { _, _ -> }
) {
    companion object {
        private const val TAG = "GeminiLiveEngine"
        private const val WS_HOST = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

        /** 經 Rate Limit 實測真實可用的 Live Translate 模型（此 Key 已有 3 次調用） */
        const val DEFAULT_LIVE_MODEL = "gemini-3.5-live-translate-preview"

        /** 兼容顯示名，確保「Gemini 3.5 Live Translate」直通，舊幻覺遷移至此 */
        fun normalizeModelName(raw: String): String {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return DEFAULT_LIVE_MODEL
            val lower = trimmed.lowercase()
            if (lower == "gemini 3.5 live translate" ||
                lower == "gemini 3.5 live translate preview" ||
                lower == "gemini-3.5-live-translate" ||
                lower == "models/gemini-3.5-live-translate"
            ) {
                return DEFAULT_LIVE_MODEL
            }
            if (lower.contains("2.0-flash-live") ||
                lower == "gemini-2.5-flash-native-audio-latest" ||
                lower == "models/gemini-2.5-flash-native-audio-latest"
            ) {
                return DEFAULT_LIVE_MODEL
            }
            if (trimmed.contains(" ")) return DEFAULT_LIVE_MODEL
            return trimmed
        }

        /** 為追趕 Google 翻譯 App 的體感，改為 50ms 聚合（原 100ms）：延遲 -50ms，頻寬略增但可接受 */
        private const val SEND_CHUNK_BYTES = 1600

        /** 逐字稿保險：由 2.5s 縮至 0.8s，字幕更快出現 */
        private const val TRANSCRIPT_FLUSH_DELAY_MS = 800L

        /**
         * App 內部語言代碼 -> Live Translate 支援的 BCP-47 代碼。
         * 官方僅接受 zh-Hant / zh-Hans，傳入 zh-TW 會被拒絕。
         */
        fun toLiveTranslateCode(appLangCode: String): String {
            val code = appLangCode.trim()
            return when {
                code.equals("zh-TW", true) || code.equals("zh-Hant", true) ||
                    code.equals("zh-HK", true) || code.equals("zh-MO", true) -> "zh-Hant"
                code.equals("zh-CN", true) || code.equals("zh-Hans", true) ||
                    code.equals("zh-SG", true) || code.equals("zh", true) -> "zh-Hans"
                code.contains("-") -> code.substringBefore("-").lowercase()
                else -> code.lowercase()
            }
        }
    }

    private val client = OkHttpClient.Builder()
        .pingInterval(0, TimeUnit.SECONDS) // Live API 伺服器不回 PONG，15s 心跳會導致 1002 斷線，改為禁用
        .readTimeout(0, TimeUnit.MILLISECONDS) // 保持長連線
        .retryOnConnectionFailure(true)
        .build()

    var onPlaybackStateChanged: ((Boolean) -> Unit)? = null

    private var webSocket: WebSocket? = null
    private val isRunning = AtomicBoolean(false)
    private val isReady = AtomicBoolean(false)

    /** 只有在收到 setupComplete 之後才算真的可以送音訊 */
    val isConnectionReady: Boolean get() = isReady.get()

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var reconnectJob: Job? = null
    private var flushJob: Job? = null
    private var reconnectAttempts = 0

    private var activeTargetLangCode = "zh-Hant"
    private var lastAttemptedModel: String = DEFAULT_LIVE_MODEL

    /** 累積 20ms 小封包，湊滿 50ms 再送，兼顧延遲與封包效率 */
    private val pendingAudio = ByteArrayOutputStream(SEND_CHUNK_BYTES * 2)

    private val transcriptLock = Any()
    private val inputTranscript = StringBuilder()
    private val outputTranscript = StringBuilder()

    fun start(sourceLang: String, targetLang: String) {
        activeTargetLangCode = toLiveTranslateCode(targetLang)
        isRunning.set(true)
        reconnectAttempts = 0
        connect()
    }

    private fun connect() {
        val apiKey = apiKeyProvider().trim()
        if (apiKey.isEmpty()) {
            onConnectionStateChanged(false, "請先在設定中填入有效的 Gemini API Key")
            return
        }

        synchronized(pendingAudio) { pendingAudio.reset() }
        clearTranscripts()

        // AQ. 與 AIza 皆以 x-goog-api-key + ?key 方式驗證成功（Bearer 會 401/403）
        val url = "$WS_HOST?key=$apiKey"
        val request = Request.Builder()
            .url(url)
            .addHeader("x-goog-api-key", apiKey)
            .build()

        onConnectionStateChanged(false, "正在連線至 Gemini Live 同步口譯伺服器...")

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "Gemini Live WebSocket 連線已建立，發送初始化 Setup...")
                sendSetupMessage(ws)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleServerMessage(text)
            }

            // Gemini Live 的回應是 binary frame (UTF-8 JSON)，沒有這個 override 等於完全收不到任何回應
            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                handleServerMessage(bytes.utf8())
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                if (ws !== webSocket) return
                val respBody = try { response?.body?.string() } catch (e: Exception) { null }
                val code = response?.code
                val msg = t.message ?: ""
                val isPingTimeout = msg.contains("ping", true) && msg.contains("pong", true)
                Log.e(TAG, "Gemini Live 連線失敗: ${t.message}, HTTP: $code, Body: $respBody", t)
                isReady.set(false)

                if (isPingTimeout) {
                    Log.w(TAG, "偵測到 WebSocket ping 超時，已禁用 ping，改為靜默重連")
                    if (isRunning.get()) {
                        onConnectionStateChanged(false, "連線保活超時，自動重連中...")
                        scheduleReconnect()
                    }
                    return
                }

                val detail = if (code != null) {
                    " (HTTP $code: ${respBody?.take(200) ?: t.localizedMessage})"
                } else {
                    " (${t.localizedMessage ?: t.javaClass.simpleName})"
                }
                onConnectionStateChanged(false, "Gemini Live 連線失敗$detail")

                if (isRunning.get() && code != 400 && code != 401 && code != 403 && code != 404) {
                    scheduleReconnect()
                }
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                if (ws !== webSocket) return
                Log.w(TAG, "Gemini Live 伺服器關閉連線: $code - $reason")
                isReady.set(false)
                handleServerClose(code, reason)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                if (ws !== webSocket) return
                Log.d(TAG, "Gemini Live 連線已關閉: $code - $reason")
                isReady.set(false)
            }
        })
    }

    /**
     * 伺服器主動關閉多半代表 setup 被拒絕 (模型名稱錯、欄位不合法、金鑰無權限)。
     * 這類錯誤重連幾次也不會好，直接把原因顯示給使用者。
     */
    private fun handleServerClose(code: Int, reason: String) {
        val permanent = code == 1007 || code == 1008 || code == 1003 ||
            reason.contains("Unknown name", true) ||
            reason.contains("INVALID_ARGUMENT", true) ||
            reason.contains("not found", true) ||
            reason.contains("PERMISSION_DENIED", true) ||
            reason.contains("API key", true)

        val shown = if (reason.isBlank()) "伺服器關閉連線 (code $code)" else reason.take(240)
        val modelInfo = if (lastAttemptedModel.isNotBlank()) " (model=$lastAttemptedModel)" else ""
        Log.e(TAG, "handleServerClose code=$code reason=$reason model=$lastAttemptedModel")

        if (permanent) {
            isRunning.set(false)
            val hint = if (reason.contains("Unknown name", true) || reason.contains("not found", true)) {
                " → 請確認模型名稱為 $DEFAULT_LIVE_MODEL（顯示名「Gemini 3.5 Live Translate」已自動映射）"
            } else ""
            onConnectionStateChanged(false, "Gemini Live 設定被拒絕：$shown$modelInfo$hint")
        } else {
            onConnectionStateChanged(false, "Gemini Live 連線中斷 ($code)：$shown$modelInfo")
            if (isRunning.get()) scheduleReconnect()
        }
    }

    private fun sendSetupMessage(ws: WebSocket) {
        val rawModel = modelNameProvider().trim().ifEmpty { DEFAULT_LIVE_MODEL }
        val normalized = normalizeModelName(rawModel)
        val finalModel = if (normalized.startsWith("models/")) normalized else "models/$normalized"
        lastAttemptedModel = finalModel

        // 依 Rate Limit 實測：live-translate 與 native-audio 皆需 AUDIO+translationConfig+thinking0；transcribe-live 僅 TEXT
        val isTranscribeLive = finalModel.contains("transcribe-live", ignoreCase = true)
        val isNativeAudio = finalModel.contains("native-audio", ignoreCase = true) || finalModel.contains("2.5-flash-native", ignoreCase = true)
        val isLiveTranslate = finalModel.contains("live-translate", ignoreCase = true)

        val responseModality = if (isTranscribeLive) "TEXT" else "AUDIO"
        val generationConfig = JSONObject().apply {
            put("responseModalities", JSONArray().apply { put(responseModality) })
            if (!isNativeAudio && !isTranscribeLive && !isLiveTranslate) {
                put("inputAudioTranscription", JSONObject())
                put("outputAudioTranscription", JSONObject())
            }
            if (isLiveTranslate || isNativeAudio) {
                put("translationConfig", JSONObject().apply {
                    put("targetLanguageCode", activeTargetLangCode)
                })
                put("thinkingConfig", JSONObject().apply {
                    put("thinkingBudget", 0)
                    put("includeThoughts", false)
                })
            }
        }

        val setupJson = JSONObject().apply {
            put("setup", JSONObject().apply {
                put("model", finalModel)
                put("generationConfig", generationConfig)
                // transcribe-live 不需要 systemInstruction；native-audio 可選 systemInstruction，但已有 translationConfig 就足夠
                if (!isNativeAudio && !isTranscribeLive) {
                    put("systemInstruction", JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put(
                                    "text",
                                    "You are an elite real-time simultaneous interpreter. Translate incoming speech instantly and speak it out naturally in the target language. Never add greetings or commentary."
                                )
                            })
                        })
                    })
                }
                // native-audio 若需額外口吻，可在此追加 systemInstruction（translationConfig 已處理語言）
            })
        }

        ws.send(setupJson.toString())
        Log.d(TAG, "已送出 setup：model=$finalModel, target=$activeTargetLangCode")
        onConnectionStateChanged(false, "已送出設定，等待 Gemini Live 就緒...")
        // 注意：isReady 必須等 setupComplete 回來才能打開
    }

    /**
     * 接收手機 16kHz PCM 音訊塊，累積成 100ms 再實時串流傳送給 Gemini
     */
    fun sendAudioFrame(pcm16kBytes: ByteArray, length: Int) {
        if (!isReady.get() || !isRunning.get()) return

        val payload: ByteArray? = synchronized(pendingAudio) {
            pendingAudio.write(pcm16kBytes, 0, length)
            if (pendingAudio.size() >= SEND_CHUNK_BYTES) {
                val out = pendingAudio.toByteArray()
                pendingAudio.reset()
                out
            } else {
                null
            }
        }

        if (payload == null) return

        try {
            val base64Data = Base64.encodeToString(payload, Base64.NO_WRAP)
            val audioPayload = JSONObject().apply {
                put("realtimeInput", JSONObject().apply {
                    put("audio", JSONObject().apply {
                        put("mimeType", "audio/pcm;rate=16000")
                        put("data", base64Data)
                    })
                })
            }
            webSocket?.send(audioPayload.toString())
        } catch (e: Exception) {
            Log.w(TAG, "傳送音訊 Frame 例外", e)
        }
    }

    private fun handleServerMessage(jsonText: String) {
        try {
            val root = JSONObject(jsonText)

            if (root.has("setupComplete")) {
                isReady.set(true)
                reconnectAttempts = 0
                Log.d(TAG, "收到 setupComplete，Gemini Live 已就緒")
                onConnectionStateChanged(true, "Gemini Live 同步口譯已就緒 (關螢幕仍持續運作)")
                return
            }

            if (root.has("goAway")) {
                Log.w(TAG, "伺服器通知即將斷線 (goAway)，預先重連")
                isReady.set(false)
                if (isRunning.get()) scheduleReconnect()
                return
            }

            val serverContent = root.optJSONObject("serverContent") ?: return

            // 1. 原文逐字稿：收到立刻推 interim，體感接近 Google 翻譯 App
            serverContent.optJSONObject("inputTranscription")?.optString("text")?.let {
                if (it.isNotEmpty()) {
                    synchronized(transcriptLock) { inputTranscript.append(it) }
                    scheduleTranscriptFlush()
                    emitInterim()
                }
            }

            // 2. 翻譯後逐字稿：同上，立刻推 interim
            serverContent.optJSONObject("outputTranscription")?.optString("text")?.let {
                if (it.isNotEmpty()) {
                    synchronized(transcriptLock) { outputTranscript.append(it) }
                    scheduleTranscriptFlush()
                    emitInterim()
                }
            }

            // 3. 串流語音 PCM 24kHz
            val parts = serverContent.optJSONObject("modelTurn")?.optJSONArray("parts")
            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val part = parts.optJSONObject(i) ?: continue

                    val dataBase64 = part.optJSONObject("inlineData")?.optString("data", "").orEmpty()
                    if (dataBase64.isNotEmpty()) {
                        val audioBytes = Base64.decode(dataBase64, Base64.DEFAULT)
                        onPlaybackStateChanged?.invoke(true)
                        onAudioChunkReceived(audioBytes)
                    }

                    // 過濾思考痕跡：native-audio 會以 thought:true 送出內部推理，不應顯示為翻譯
                    if (part.optBoolean("thought", false)) {
                        Log.d(TAG, "忽略 thought 內容: ${part.optString("text", "").take(120)}")
                        continue
                    }
                    val text = part.optString("text", "")
                    if (text.isNotBlank()) {
                        synchronized(transcriptLock) { outputTranscript.append(text) }
                        scheduleTranscriptFlush()
                        emitInterim()
                    }
                }
            }

            if (serverContent.optBoolean("turnComplete", false) ||
                serverContent.optBoolean("generationComplete", false)
            ) {
                onPlaybackStateChanged?.invoke(false)
                flushTranscripts()
            }

            // 使用者插話 (Barge-in / Interrupted)
            if (serverContent.optBoolean("interrupted", false)) {
                onPlaybackStateChanged?.invoke(false)
                flushTranscripts()
                Log.d(TAG, "偵測到說話插話 (Barge-in)，Gemini 已即時暫停輸出")
            }
        } catch (e: Exception) {
            Log.w(TAG, "解析伺服器訊息錯誤: ${e.message} / ${jsonText.take(200)}")
        }
    }

    /** 收到逐字稿立刻快照推 interim（StateFlow 自帶 conflated，不怕洗版） */
    private fun emitInterim() {
        val orig: String
        val trans: String
        synchronized(transcriptLock) {
            orig = inputTranscript.toString().trim()
            trans = outputTranscript.toString().trim()
        }
        if (orig.isNotEmpty() || trans.isNotEmpty()) {
            try {
                onInterimReceived(orig, trans)
            } catch (e: Exception) {
                Log.w(TAG, "emitInterim 例外", e)
            }
        }
    }

    /** 沒收到 turnComplete 時的保險：安靜一段時間就先把字幕送出去 */
    private fun scheduleTranscriptFlush() {
        flushJob?.cancel()
        flushJob = scope.launch {
            delay(TRANSCRIPT_FLUSH_DELAY_MS)
            if (isActive) flushTranscripts()
        }
    }

    private fun flushTranscripts() {
        flushJob?.cancel()
        flushJob = null

        val original: String
        val translated: String
        synchronized(transcriptLock) {
            original = inputTranscript.toString().trim()
            translated = outputTranscript.toString().trim()
            inputTranscript.setLength(0)
            outputTranscript.setLength(0)
        }

        if (translated.isNotEmpty() || original.isNotEmpty()) {
            onTranscriptReceived(
                original.ifEmpty { "(語音輸入)" },
                translated.ifEmpty { "(語音已輸出)" }
            )
        }
    }

    private fun clearTranscripts() {
        synchronized(transcriptLock) {
            inputTranscript.setLength(0)
            outputTranscript.setLength(0)
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        val attempt = ++reconnectAttempts
        if (attempt > 5) {
            isRunning.set(false)
            onConnectionStateChanged(false, "Gemini Live 連線重試多次仍失敗，請檢查網路或 API Key")
            return
        }
        val backoffMs = (2000L * attempt).coerceAtMost(15000L)
        reconnectJob = scope.launch {
            delay(backoffMs)
            if (isActive && isRunning.get()) {
                Log.d(TAG, "正在嘗試重新建立 Gemini Live 連線... (第 $attempt 次)")
                connect()
            }
        }
    }

    fun stop() {
        isRunning.set(false)
        isReady.set(false)
        reconnectJob?.cancel()
        reconnectJob = null
        flushJob?.cancel()
        flushJob = null
        clearTranscripts()
        synchronized(pendingAudio) { pendingAudio.reset() }

        val ws = webSocket
        webSocket = null
        try {
            ws?.close(1000, "User stopped")
        } catch (e: Exception) {
            // ignore
        }
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        onConnectionStateChanged(false, "Gemini Live 已關閉")
    }
}
