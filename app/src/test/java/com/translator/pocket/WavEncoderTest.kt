package com.translator.pocket

import com.translator.pocket.audio.WavEncoder
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavEncoderTest {

    @Test
    fun testPcmToWavHeader() {
        val dummyPcm = ByteArray(32000) // 1 second of 16kHz 16-bit mono audio
        val wav = WavEncoder.pcmToWav(dummyPcm, sampleRate = 16000, channels = 1, bitsPerSample = 16)

        // WAV total size should be 44 + PCM size
        assertEquals(44 + 32000, wav.size)

        val buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)

        // RIFF chunk ID
        val riff = ByteArray(4)
        buffer.get(riff)
        assertEquals("RIFF", String(riff))

        // Chunk size = 36 + dataSize
        val chunkSize = buffer.int
        assertEquals(36 + 32000, chunkSize)

        // WAVE
        val wave = ByteArray(4)
        buffer.get(wave)
        assertEquals("WAVE", String(wave))

        // fmt
        val fmt = ByteArray(4)
        buffer.get(fmt)
        assertEquals("fmt ", String(fmt))

        val subchunk1Size = buffer.int
        assertEquals(16, subchunk1Size)

        val audioFormat = buffer.short
        assertEquals(1.toShort(), audioFormat) // PCM

        val numChannels = buffer.short
        assertEquals(1.toShort(), numChannels)

        val sampleRate = buffer.int
        assertEquals(16000, sampleRate)

        val byteRate = buffer.int
        assertEquals(32000, byteRate)

        val blockAlign = buffer.short
        assertEquals(2.toShort(), blockAlign)

        val bitsPerSample = buffer.short
        assertEquals(16.toShort(), bitsPerSample)

        // data subchunk
        val data = ByteArray(4)
        buffer.get(data)
        assertEquals("data", String(data))

        val dataSize = buffer.int
        assertEquals(32000, dataSize)
    }
}
