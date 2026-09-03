package com.translator.pocket.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class AudioStreamRecorder(
    private val vadSegmenter: VadSegmenter
) {
    companion object {
        private const val TAG = "AudioStreamRecorder"
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val FRAME_SIZE_BYTES = 640 // 20ms at 16kHz 16-bit mono (320 samples * 2 bytes)
    }

    private var audioRecord: AudioRecord? = null
    private val isRecording = AtomicBoolean(false)
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    var onRawFrameCaptured: ((ByteArray, Int) -> Unit)? = null
    val isMutedByPlayback = AtomicBoolean(false)
    private var echoCanceler: android.media.audiofx.AcousticEchoCanceler? = null

    @SuppressLint("MissingPermission")
    fun startRecording(): Boolean {
        if (isRecording.get()) return true

        val minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBufSize == AudioRecord.ERROR || minBufSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "無效的 AudioRecord 緩衝區大小: $minBufSize")
            return false
        }

        val bufferSize = maxOf(minBufSize, FRAME_SIZE_BYTES * 4)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, // 優先使用語音辨識降噪音訊源
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord 初始化失敗")
                release()
                return false
            }

            // 嘗試開啟系統硬體迴音消除器 (AEC) 防止喇叭回授
            if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) {
                try {
                    echoCanceler = android.media.audiofx.AcousticEchoCanceler.create(audioRecord!!.audioSessionId)
                    echoCanceler?.enabled = true
                    Log.d(TAG, "硬體級 AcousticEchoCanceler 啟動成功")
                } catch (e: Exception) {
                    Log.w(TAG, "啟動硬體 AEC 失敗，將依靠軟體自靜音門控", e)
                }
            }

            audioRecord?.startRecording()
            isRecording.set(true)
            vadSegmenter.reset()

            recordingJob = scope.launch {
                val buffer = ByteArray(FRAME_SIZE_BYTES)
                Log.d(TAG, "AudioRecord 開始背景循環採樣...")

                while (isActive && isRecording.get()) {
                    val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (bytesRead > 0) {
                        // 若喇叭正在外放朗讀翻譯語音，暫停收集麥克風音訊，徹底杜絕自己錄自己
                        if (!isMutedByPlayback.get()) {
                            onRawFrameCaptured?.invoke(buffer, bytesRead)
                            vadSegmenter.processFrame(buffer, bytesRead)
                        }
                    } else if (bytesRead < 0) {
                        Log.e(TAG, "AudioRecord 讀取錯誤碼: $bytesRead")
                        break
                    }
                }
            }

            return true
        } catch (e: Exception) {
            Log.e(TAG, "啟動 AudioRecord 例外", e)
            release()
            return false
        }
    }

    fun stopRecording() {
        isRecording.set(false)
        recordingJob?.cancel()
        recordingJob = null

        try {
            if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord?.stop()
            }
        } catch (e: Exception) {
            Log.w(TAG, "停止 AudioRecord 時發生警告", e)
        } finally {
            release()
        }
    }

    private fun release() {
        try {
            echoCanceler?.release()
        } catch (e: Exception) {
            // ignore
        }
        echoCanceler = null

        try {
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "釋放 AudioRecord 警告", e)
        }
        audioRecord = null
    }

    fun isRunning(): Boolean = isRecording.get()
}
