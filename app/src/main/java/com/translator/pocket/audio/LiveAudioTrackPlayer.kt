package com.translator.pocket.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 專為 Gemini Live 雙向語音串流打造的低延遲 AudioTrack 播放器
 * Gemini Live 預設輸出為 24,000Hz 16-bit 單聲道 PCM
 */
class LiveAudioTrackPlayer(
    private val sampleRate: Int = 24000
) {
    companion object {
        private const val TAG = "LiveAudioTrackPlayer"
    }

    private var audioTrack: AudioTrack? = null
    private val isPlaying = AtomicBoolean(false)

    fun start() {
        if (isPlaying.get()) return

        val minBufSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minBufSize, sampleRate * 2 / 5) // 約 200ms 緩衝區，保證不爆音同時低延遲

        try {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val format = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build()

            audioTrack = AudioTrack(
                attributes,
                format,
                bufferSize,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )

            audioTrack?.play()
            isPlaying.set(true)
            Log.d(TAG, "AudioTrack (24kHz) 播放器啟動成功")
        } catch (e: Exception) {
            Log.e(TAG, "初始化 AudioTrack 失敗", e)
        }
    }

    fun playChunk(pcmBytes: ByteArray) {
        if (!isPlaying.get() || audioTrack == null) {
            start()
        }
        try {
            audioTrack?.write(pcmBytes, 0, pcmBytes.size, AudioTrack.WRITE_NON_BLOCKING)
        } catch (e: Exception) {
            Log.w(TAG, "寫入音訊數據塊異常", e)
        }
    }

    fun stop() {
        isPlaying.set(false)
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "停止 AudioTrack 警告", e)
        } finally {
            try {
                audioTrack?.release()
            } catch (e: Exception) {
                // ignore
            }
            audioTrack = null
        }
    }
}
