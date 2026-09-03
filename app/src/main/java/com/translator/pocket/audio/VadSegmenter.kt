package com.translator.pocket.audio

import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.sqrt

class VadSegmenter(
    private val sensitivityLevel: Int = 5, // 1 (超靈敏) ~ 10 (需大聲說話)
    private val onSentenceReady: (ByteArray) -> Unit
) {
    // 基準門檻：sensitivityLevel 5 對應基準約 280 RMS
    private val baseThreshold: Double = (sensitivityLevel * 30.0 + 130.0).coerceIn(160.0, 500.0)

    // 動態環境底噪追蹤（指數滑動平均）
    private var noiseFloor: Double = 60.0

    // 靜音逾時斷句時間 (毫秒)
    private val silenceTimeoutMs: Long = 750L

    // 最少語音時長（約 0.6 秒，19,200 bytes，過濾瞬間碰撞與雜音）
    private val minSpeechBytes: Int = 16000 * 2 * 6 / 10

    // 最長單句上限（超過 7 秒強制分段，防止單一請求過大延遲增加）
    private val maxSpeechBytes: Int = 16000 * 2 * 7

    var onRmsCalculated: ((Double) -> Unit)? = null

    private val speechBuffer = ByteArrayOutputStream()
    private var isSpeaking = false
    private var consecutiveSilenceMs = 0L
    private var consecutiveActiveFrames = 0 // 防抖消抖計數器

    /**
     * 接收 20ms 的 PCM Frame (通常為 640 bytes)
     */
    @Synchronized
    fun processFrame(frame: ByteArray, length: Int) {
        if (length <= 0) return

        val rms = calculateRms(frame, length)
        onRmsCalculated?.invoke(rms)

        // 動態門檻：必須高於環境底噪 100 RMS，且不低於基準門檻
        val effectiveThreshold = max(baseThreshold, noiseFloor + 100.0)
        val isFrameActive = rms > effectiveThreshold

        if (isFrameActive) {
            consecutiveActiveFrames++
            if (!isSpeaking && consecutiveActiveFrames >= 2) {
                // 連續 2 個 frame (40ms) 以上超過門檻，正式進入說話狀態
                isSpeaking = true
                consecutiveSilenceMs = 0L
                speechBuffer.write(frame, 0, length)
            } else if (isSpeaking) {
                consecutiveSilenceMs = 0L
                speechBuffer.write(frame, 0, length)

                // 超過最大時長強制斷句發送
                if (speechBuffer.size() >= maxSpeechBytes) {
                    flushSentence()
                }
            }
        } else {
            consecutiveActiveFrames = 0

            // 若不是在說話，平滑適應背景底噪 (只吸收平靜音量)
            if (!isSpeaking) {
                if (rms < effectiveThreshold) {
                    noiseFloor = noiseFloor * 0.95 + rms * 0.05
                }
            } else {
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
        consecutiveActiveFrames = 0

        if (audioData.size >= minSpeechBytes) {
            onSentenceReady(audioData)
        }
    }

    @Synchronized
    fun reset() {
        speechBuffer.reset()
        isSpeaking = false
        consecutiveSilenceMs = 0L
        consecutiveActiveFrames = 0
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
