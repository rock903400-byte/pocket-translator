package com.translator.pocket.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class AudioStreamRecorder {

    companion object {
        private const val TAG = "AudioStreamRecorder"
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val FRAME_SIZE_BYTES = 640
    }

    private var audioRecord: AudioRecord? = null
    private val isRecording = AtomicBoolean(false)
    private var recordingJob: Job? = null
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var onRawFrameCaptured: ((ByteArray, Int) -> Unit)? = null
    var onAudioLevelChanged: ((Double) -> Unit)? = null
    val isMutedByPlayback = AtomicBoolean(false)

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
                MediaRecorder.AudioSource.MIC,
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

            audioRecord?.startRecording()
            isRecording.set(true)
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

            recordingJob = scope.launch {
                val buffer = ByteArray(FRAME_SIZE_BYTES)
                Log.d(TAG, "AudioRecord 開始背景循環採樣...")

                while (isActive && isRecording.get()) {
                    val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (bytesRead > 0) {
                        if (!isMutedByPlayback.get()) {
                            val copy = buffer.copyOf(bytesRead)
                            onRawFrameCaptured?.invoke(copy, bytesRead)
                            val rms = calculateRms(copy, bytesRead)
                            onAudioLevelChanged?.invoke(rms)
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
        scope.cancel()

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
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "釋放 AudioRecord 警告", e)
        }
        audioRecord = null
    }

    fun isRunning(): Boolean = isRecording.get()

    private fun calculateRms(buffer: ByteArray, length: Int): Double {
        var sum = 0.0
        val sampleCount = length / 2
        if (sampleCount == 0) return 0.0
        for (i in 0 until length - 1 step 2) {
            val low = buffer[i].toInt() and 0xFF
            val high = buffer[i + 1].toInt()
            val sample = (high shl 8) or low
            sum += sample * sample
        }
        return kotlin.math.sqrt(sum / sampleCount)
    }
}
