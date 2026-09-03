package com.translator.pocket

import com.translator.pocket.tts.TtsQueuePolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsQueuePolicyTest {

    @Test
    fun `佇列還沒滿就照常排隊`() {
        assertFalse(TtsQueuePolicy.shouldFlush(0))
        assertFalse(TtsQueuePolicy.shouldFlush(1))
    }

    @Test
    fun `佇列達到上限就清空跳到最新`() {
        // 口譯情境：當下的資訊勝過完整的資訊
        assertTrue(TtsQueuePolicy.shouldFlush(2))
        assertTrue(TtsQueuePolicy.shouldFlush(5))
    }

    @Test
    fun `上限可調整`() {
        assertFalse(TtsQueuePolicy.shouldFlush(2, max = 3))
        assertTrue(TtsQueuePolicy.shouldFlush(3, max = 3))
    }

    @Test
    fun `預設上限為正在唸的一句加排隊的一句`() {
        assertTrue(TtsQueuePolicy.MAX_PENDING == 2)
    }
}
