package com.translator.pocket.audio

import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

class VadSegmenter(
    private val sensitivityLevel: Int = 5, // 1 (超靈敏) ~ 10 (需大聲說話)
    private val onSentenceReady: (ByteArray) -> Unit
) {
    // 門檻計算：sensitivityLevel 越小門檻越低
    // 預設 5 對應門檻約 600 RMS
    private val energyThreshold: Double = (sensitivityLevel * 100.0 + 150.0).coerceIn(300.0, 2000.0)

    // 靜音逾時斷句時間 (毫秒)
    private val silenceTimeoutMs: Long = 650L

    // 最少語音時長（低於此時長視為雜訊、咳嗽或清喉嚨）
    private val minSpeechBytes: Int = 16000 * 2 * 6 / 10 // 約 0.6 秒 (19,200 bytes)

    // 最長單句上限（超過 5 秒強制分段，防止單一請求過大延遲增加）
    private val maxSpeechBytes: Int = 16000 * 2 * 5 // 5 秒 (160,000 bytes)

    private val speechBuffer = ByteArrayOutputStream()
    private var isSpeaking = false
    private var consecutiveSilenceMs = 0L

    /**
     * 接收 20ms 的 PCM Frame (通常為 640 bytes)
     */
    @Synchronized
    fun processFrame(frame: ByteArray, length: Int) {
        if (length <= 0) return

        val rms = calculateRms(frame, length)
        val isVoiceActive = rms > energyThreshold

        if (isVoiceActive) {
            isSpeaking = true
            consecutiveSilenceMs = 0L
            speechBuffer.write(frame, 0, length)

            // 超過最大時長強制斷句發送
            if (speechBuffer.size() >= maxSpeechBytes) {
                flushSentence()
            }
        } else {
            if (isSpeaking) {
                // 說話過程中的微小停頓，仍保留音訊避免尾音被切掉
                speechBuffer.write(frame, 0, length)
                consecutiveSilenceMs += 20L // 每個 frame 約 20ms

                if (consecutiveSilenceMs >= silenceTimeoutMs) {
                    flushSentence()
                }
            }
        }
    }

    private fun flushSentence() {
        val audioData = speechBuffer.toByteArray()
        speechBuffer.reset()
        isSpeaking = false
        consecutiveSilenceMs = 0L

        if (audioData.size >= minSpeechBytes) {
            onSentenceReady(audioData)
        }
    }

    @Synchronized
    fun reset() {
        speechBuffer.reset()
        isSpeaking = false
        consecutiveSilenceMs = 0L
    }

    private fun calculateRms(buffer: ByteArray, length: Int): Double {
        var sum = 0.0
        val sampleCount = length / 2
        if (sampleCount == 0) return 0.0

        for (i in 0 until length - 1 step 2) {
            // Little Endian 16-bit PCM 轉 Short
            val low = buffer[i].toInt() and 0xFF
            val high = buffer[i + 1].toInt()
            val sample = (high shl 8) or low
            sum += sample * sample
        }

        return sqrt(sum / sampleCount)
    }
}
