package com.translator.pocket

import com.translator.pocket.audio.VadSegmenter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VadSegmenterTest {

    @Test
    fun testSilenceIgnored() {
        var triggeredCount = 0
        val segmenter = VadSegmenter(sensitivityLevel = 5) {
            triggeredCount++
        }

        // Send 100 frames of pure silence (all 0s)
        val silenceFrame = ByteArray(640)
        repeat(100) {
            segmenter.processFrame(silenceFrame, silenceFrame.size)
        }

        // Should not trigger on silence
        assertEquals(0, triggeredCount)
    }

    @Test
    fun testSpeechWithSilencePauseTriggersSentence() {
        var capturedAudioBytes: ByteArray? = null
        val segmenter = VadSegmenter(sensitivityLevel = 5) { pcm ->
            capturedAudioBytes = pcm
        }

        // Generate synthetic voice frame (sine-like samples with high amplitude)
        val voiceFrame = ByteArray(640)
        for (i in 0 until 640 step 2) {
            val sample = (5000 * Math.sin(i * 0.1)).toInt().toShort()
            voiceFrame[i] = (sample.toInt() and 0xFF).toByte()
            voiceFrame[i + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
        }

        // 1. Send 50 frames of voice (about 1 second of speech)
        repeat(50) {
            segmenter.processFrame(voiceFrame, voiceFrame.size)
        }

        // 2. Send 40 frames of silence (about 800ms of silence pause)
        val silenceFrame = ByteArray(640)
        repeat(40) {
            segmenter.processFrame(silenceFrame, silenceFrame.size)
        }

        // Should have triggered onSentenceReady
        assertTrue("語音結尾停頓應自動觸發語句完成回呼", capturedAudioBytes != null)
        assertTrue("收集的語句長度應大於 50 個 frame", capturedAudioBytes!!.size >= 50 * 640)
    }
}
