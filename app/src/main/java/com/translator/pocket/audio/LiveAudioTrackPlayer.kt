package com.translator.pocket.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 專為 Gemini Live 雙向語音串流打造的低延遲 AudioTrack 播放器
 * Gemini Live 預設輸出為 24,000Hz 16-bit 單聲道 PCM
 *
 * 線程模型：OkHttp 回調線程只做 offer 入隊（永不 block），
 * 真正的 AudioTrack.write 跑在專屬播放線程，避免反壓 WebSocket 接收。
 * 隊列滿時丟棄最舊塊（保延遲不保完整），並計數供排查。
 */
class LiveAudioTrackPlayer(
    private val sampleRate: Int = 24000
) {
    companion object {
        private const val TAG = "LiveAudioTrackPlayer"

        /** 最多緩存 20 塊；每塊約數十 ms，滿了代表播放跟不上，丟舊保新 */
        const val MAX_QUEUED_CHUNKS = 20
    }

    private var audioTrack: AudioTrack? = null
    private val isPlaying = AtomicBoolean(false)
    private val queue = LinkedBlockingQueue<ByteArray>(MAX_QUEUED_CHUNKS)
    private var playbackThread: Thread? = null
    private val droppedChunks = AtomicLong(0)

    fun start() {
        if (isPlaying.get()) return

        val minBufSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        // 低延遲：100ms 緩衝（原 200ms），聲音更快出來；minBuf 保底避免爆音
        val bufferSize = maxOf(minBufSize, sampleRate / 10 * 2)

        try {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
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
            queue.clear()
            isPlaying.set(true)
            playbackThread = Thread({ playbackLoop() }, "LiveAudioPlayback").apply {
                isDaemon = true
                start()
            }
            Log.d(TAG, "AudioTrack (24kHz) 播放器啟動成功")
        } catch (e: Exception) {
            Log.e(TAG, "初始化 AudioTrack 失敗", e)
        }
    }

    /**
     * 由 OkHttp 回調線程呼叫：只入隊，永不 block。
     * 隊列滿時丟棄最舊一塊再入隊，保證延遲不堆積。
     */
    fun playChunk(pcmBytes: ByteArray) {
        if (!isPlaying.get() || audioTrack == null) {
            start()
        }
        if (!isPlaying.get()) return
        if (!queue.offer(pcmBytes)) {
            queue.poll()
            droppedChunks.incrementAndGet()
            if (!queue.offer(pcmBytes)) {
                Log.w(TAG, "播放隊列已滿且重試失敗，丟棄一塊 (累計丟棄=${droppedChunks.get()})")
            } else if (droppedChunks.get() % 10L == 1L) {
                Log.w(TAG, "播放跟不上，已丟棄舊塊保延遲 (累計=${droppedChunks.get()})")
            }
        }
    }

    private fun playbackLoop() {
        while (isPlaying.get()) {
            try {
                val chunk = queue.take()
                val track = audioTrack ?: continue
                var offset = 0
                while (offset < chunk.size && isPlaying.get()) {
                    val written = track.write(chunk, offset, chunk.size - offset, AudioTrack.WRITE_BLOCKING)
                    if (written < 0) {
                        Log.w(TAG, "AudioTrack 寫入返回錯誤碼: $written")
                        break
                    }
                    offset += written
                }
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                Log.w(TAG, "播放線程異常", e)
            }
        }
    }

    fun queuedChunks(): Int = queue.size

    fun droppedCount(): Long = droppedChunks.get()

    fun stop() {
        isPlaying.set(false)
        playbackThread?.interrupt()
        playbackThread = null
        queue.clear()
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
