package com.translator.pocket

import com.translator.pocket.model.InterimPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InterimPolicyTest {

    @Test
    fun `異常狀態才還原否則不碰`() {
        assertEquals(
            "已就緒",
            InterimPolicy.restoreAfterAnomaly("即時字幕連線異常：xxx", "已就緒")
        )
        assertNull(InterimPolicy.restoreAfterAnomaly("正在聆聽中", "已就緒"))
        assertNull(InterimPolicy.restoreAfterAnomaly("已就緒", "已就緒"))
    }

    @Test
    fun `B有譯文就用B的`() {
        val (s, t) = InterimPolicy.mergeBInterim("舊原文", "REST譯文", "新原文", "B譯文")
        assertEquals("新原文", s)
        assertEquals("B譯文", t)
    }

    @Test
    fun `B沒譯文時保留REST暫存不洗回等待字`() {
        val (s, t) = InterimPolicy.mergeBInterim("舊原文", "REST譯文", "新原文", "")
        assertEquals("新原文", s)
        assertEquals("REST譯文", t)
    }

    @Test
    fun `兩邊都空才用等待字`() {
        val (s, t) = InterimPolicy.mergeBInterim("", "", "", "")
        assertEquals("…", s)
        assertEquals("翻譯中…", t)
    }

    @Test
    fun `B原文空時沿用畫面原文`() {
        val (s, t) = InterimPolicy.mergeBInterim("滾動中", "翻譯中…", "", "B譯文")
        assertEquals("滾動中", s)
        assertEquals("B譯文", t)
    }
}
