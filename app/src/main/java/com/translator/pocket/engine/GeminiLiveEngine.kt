package com.translator.pocket.engine

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Google Gemini Multimodal Live API (雙向 WebSocket 實時語音串流引擎)
 * 端到端 聲音進 -> 聲音出 (Audio-in -> Audio-out)
 */
class GeminiLiveEngine(
    private val apiKeyProvider: () -> String,
    private val voiceName: String = "Puck",
    private val onAudioChunkReceived: (ByteArray) -> Unit,
    private val onTranscriptReceived: (originalText: String, translatedText: String) -> Unit,
    private val onConnectionStateChanged: (isConnected: Boolean, message: String) -> Unit
) {
    companion object {
        private const val TAG = "GeminiLiveEngine"
        private const val MODEL_NAME = "models/gemini-2.0-flash-exp"
        private const val WS_HOST = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent"
    }

    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS) // 心跳保活
        .readTimeout(0, TimeUnit.MILLISECONDS) // 保持長連線
        .build()

    private var webSocket: WebSocket? = null
    private val isRunning = AtomicBoolean(false)
    private val isReady = AtomicBoolean(false)

    private val scope = CoroutineScope(Dispatchers.IO)
    private var reconnectJob: Job? = null

    private var activeSourceLangName = "外語"
    private var activeTargetLangName = "繁體中文"

    fun start(sourceLang: String, targetLang: String) {
        activeSourceLangName = sourceLang
        activeTargetLangName = targetLang
        isRunning.set(true)
        connect()
    }

    private fun connect() {
        val apiKey = apiKeyProvider().trim()
        if (apiKey.isEmpty()) {
            onConnectionStateChanged(false, "請先在設定中填入有效的 Gemini API Key")
            return
        }

        val url = "$WS_HOST?key=$apiKey"
        val request = Request.Builder().url(url).build()

        onConnectionStateChanged(false, "正在連線至 Gemini Live 擬真同步口譯伺服器...")

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "Gemini Live WebSocket 連線已建立，發送初始化 Setup...")
                sendSetupMessage(ws)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleServerMessage(text)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Gemini Live 連線失敗: ${t.message}", t)
                isReady.set(false)
                onConnectionStateChanged(false, "連線中斷: ${t.localizedMessage}")

                if (isRunning.get()) {
                    scheduleReconnect()
                }
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Gemini Live 連線正常關閉: $code - $reason")
                isReady.set(false)
            }
        })
    }

    private fun sendSetupMessage(ws: WebSocket) {
        val systemPrompt = "You are an elite real-time simultaneous interpreter. When you receive speech audio in any language, translate it instantaneously and speak it out in fluent, natural, professional Traditional Chinese (Taiwan, 繁體中文). Embody a calm, articulate human interpreter. Never output conversational pleasantries, greetings, or meta commentary. Only speak the exact spoken translation in real time."

        val setupJson = JSONObject().apply {
            put("setup", JSONObject().apply {
                put("model", MODEL_NAME)
                put("generationConfig", JSONObject().apply {
                    put("responseModalities", JSONArray().apply {
                        put("AUDIO")
                    })
                    put("speechConfig", JSONObject().apply {
                        put("voiceConfig", JSONObject().apply {
                            put("prebuiltVoiceConfig", JSONObject().apply {
                                put("voiceName", voiceName)
                            })
                        })
                    })
                })
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", systemPrompt)
                        })
                    })
                })
            })
        }

        ws.send(setupJson.toString())
        isReady.set(true)
        onConnectionStateChanged(true, "Gemini Live 真人同步口譯已就緒 (關螢幕仍持續運作)")
    }

    /**
     * 接收手機 16kHz PCM 音訊塊並實時串流傳送給 Gemini
     */
    fun sendAudioFrame(pcm16kBytes: ByteArray, length: Int) {
        if (!isReady.get() || !isRunning.get()) return

        try {
            val base64Data = Base64.encodeToString(pcm16kBytes, 0, length, Base64.NO_WRAP)
            val audioPayload = JSONObject().apply {
                put("realtimeInput", JSONObject().apply {
                    put("mediaChunks", JSONArray().apply {
                        put(JSONObject().apply {
                            put("mimeType", "audio/pcm;rate=16000")
                            put("data", base64Data)
                        })
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

            val serverContent = root.optJSONObject("serverContent")
            if (serverContent != null) {
                val modelTurn = serverContent.optJSONObject("modelTurn")
                if (modelTurn != null) {
                    val parts = modelTurn.optJSONArray("parts")
                    if (parts != null) {
                        for (i in 0 until parts.length()) {
                            val part = parts.getJSONObject(i)

                            // 1. 取得串流語音 PCM 24kHz
                            val inlineData = part.optJSONObject("inlineData")
                            if (inlineData != null) {
                                val mimeType = inlineData.optString("mimeType", "")
                                val dataBase64 = inlineData.optString("data", "")
                                if (dataBase64.isNotEmpty()) {
                                    val audioBytes = Base64.decode(dataBase64, Base64.DEFAULT)
                                    onAudioChunkReceived(audioBytes)
                                }
                            }

                            // 2. 取得文字字幕
                            val text = part.optString("text", "")
                            if (text.isNotBlank()) {
                                onTranscriptReceived("(外語語音)", text)
                            }
                        }
                    }
                }

                // 處理使用者插話 (Barge-in / Interrupted)
                val interrupted = serverContent.optBoolean("interrupted", false)
                if (interrupted) {
                    Log.d(TAG, "偵測到說話插話 (Barge-in)，Gemini 已即時暫停輸出")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "解析伺服器訊息錯誤: ${e.message}")
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(3000)
            if (isActive && isRunning.get()) {
                Log.d(TAG, "正在嘗試重新建立 Gemini Live 連線...")
                connect()
            }
        }
    }

    fun stop() {
        isRunning.set(false)
        isReady.set(false)
        reconnectJob?.cancel()
        reconnectJob = null

        try {
            webSocket?.close(1000, "User stopped")
        } catch (e: Exception) {
            // ignore
        }
        webSocket = null
        onConnectionStateChanged(false, "Gemini Live 已關閉")
    }
}
