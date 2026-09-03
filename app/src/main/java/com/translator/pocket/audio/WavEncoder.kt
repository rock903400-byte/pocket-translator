package com.translator.pocket.audio

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavEncoder {

    /**
     * 將 16kHz 16-bit 單聲道 PCM 音訊資料封裝為標準 WAV (RIFF) 格式
     */
    fun pcmToWav(
        pcmBytes: ByteArray,
        sampleRate: Int = 16000,
        channels: Short = 1,
        bitsPerSample: Short = 16
    ): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = (channels * bitsPerSample / 8).toShort()
        val dataSize = pcmBytes.size
        val chunkSize = dataSize + 36

        val header = ByteBuffer.allocate(44).apply {
            order(ByteOrder.LITTLE_ENDIAN)
            // RIFF Chunk
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(chunkSize)
            put("WAVE".toByteArray(Charsets.US_ASCII))

            // fmt Subchunk
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16) // Subchunk1Size (16 for PCM)
            putShort(1) // AudioFormat (1 for PCM)
            putShort(channels)
            putInt(sampleRate)
            putInt(byteRate)
            putShort(blockAlign)
            putShort(bitsPerSample)

            // data Subchunk
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataSize)
        }.array()

        val output = ByteArrayOutputStream(header.size + pcmBytes.size)
        output.write(header)
        output.write(pcmBytes)
        return output.toByteArray()
    }
}
