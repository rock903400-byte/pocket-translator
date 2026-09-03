package com.translator.pocket

import com.translator.pocket.subtitle.LiveSubtitleState
import com.translator.pocket.subtitle.SubtitleAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 時間一律以參數傳入，所以這裡不需要任何時鐘或 sleep。
 */
class LiveSubtitleStateTest {

    private fun state() = LiveSubtitleState()

    private fun interims(actions: List<SubtitleAction>) =
        actions.filterIsInstance<SubtitleAction.UpdateInterim>()

    private fun commits(actions: List<SubtitleAction>) =
        actions.filterIsInstance<SubtitleAction.Commit>()

    // ── 節流 ──────────────────────────────────────────────

    @Test
    fun `第一個 partial 立即發出`() {
        val s = state()
        val actions = s.onPartial("こんに", 1000)
        assertEquals(1, interims(actions).size)
        assertEquals("こんに", interims(actions)[0].sourceText)
    }

    @Test
    fun `完全相同的 partial 不再發出`() {
        val s = state()
        s.onPartial("こんに", 1000)
        assertTrue(s.onPartial("こんに", 1100).isEmpty())
    }

    @Test
    fun `未達節流間隔的 partial 被抑制`() {
        val s = state()
        s.onPartial("こんに", 1000)
        assertTrue(s.onPartial("こんにち", 1100).isEmpty()) // 只過了 100ms
    }

    @Test
    fun `超過節流間隔就會再次發出`() {
        val s = state()
        s.onPartial("こんに", 1000)
        val actions = s.onPartial("こんにちは", 1300) // 300ms > 250ms
        assertEquals("こんにちは", interims(actions).single().sourceText)
    }

    @Test
    fun `太短的假設視為雜訊`() {
        val s = state()
        assertTrue(s.onPartial("あ", 1000).isEmpty())
    }

    // ── 改寫 ──────────────────────────────────────────────

    @Test
    fun `假設被改寫而非延伸時仍然發出`() {
        val s = state()
        s.onPartial("あいうえお", 1000)
        val actions = s.onPartial("あいXYZ", 1400)
        assertEquals("あいXYZ", interims(actions).single().sourceText)
    }

    @Test
    fun `假設變短時仍然發出`() {
        val s = state()
        s.onPartial("あいうえおかきく", 1000)
        val actions = s.onPartial("あいうえお", 1400)
        assertEquals("あいうえお", interims(actions).single().sourceText)
    }

    // ── 句中切段 ───────────────────────────────────────────

    @Test
    fun `終止標點後面還有內容時切段`() {
        val s = state()
        val actions = s.onPartial("これはペンです。それは本", 1000)

        assertEquals("これはペンです。", commits(actions).single().sourceText)
        assertEquals("それは本", interims(actions).single().sourceText)
    }

    @Test
    fun `終止標點在結尾時不切段`() {
        val s = state()
        val actions = s.onPartial("これはペンです。", 1000)
        assertTrue(commits(actions).isEmpty())
    }

    @Test
    fun `切段後只有剩下的部分會再被定案`() {
        val s = state()
        s.onPartial("これはペンです。それは本", 1000)
        val actions = s.onFinal("これはペンです。それは本です", 1500)

        // 前半段已經 commit 過，不能再出現一次
        assertEquals("それは本です", commits(actions).single().sourceText)
    }

    @Test
    fun `超長且開頭穩定時在長度上限切段`() {
        val s = state()
        val long = "あ".repeat(70)

        // 第一次只是記錄開頭，還不敢切
        assertTrue(commits(s.onPartial(long, 1000)).isEmpty())

        // 第二次開頭沒變，可以切
        val actions = s.onPartial(long + "い", 1400)
        assertEquals(60, commits(actions).single().sourceText.length)
    }

    @Test
    fun `未超過長度上限不切段`() {
        val s = state()
        val short = "あ".repeat(30)
        s.onPartial(short, 1000)
        assertTrue(commits(s.onPartial(short + "い", 1400)).isEmpty())
    }

    // ── 沉澱定案 ───────────────────────────────────────────

    @Test
    fun `沉澱時間未到不定案`() {
        val s = state()
        s.onPartial("こんにちは", 1000)
        assertTrue(s.onTick(1500).isEmpty()) // 只過了 500ms
    }

    @Test
    fun `沉澱時間到就定案且收掉字幕卡`() {
        val s = state()
        s.onPartial("こんにちは", 1000)
        val actions = s.onTick(1800) // 800ms > 700ms

        assertEquals("こんにちは", commits(actions).single().sourceText)
        assertTrue(actions.contains(SubtitleAction.ClearInterim))
    }

    @Test
    fun `沉澱定案只會發生一次`() {
        val s = state()
        s.onPartial("こんにちは", 1000)
        s.onTick(1800)
        assertTrue(s.onTick(2500).isEmpty())
    }

    @Test
    fun `新的 partial 會重置沉澱計時`() {
        val s = state()
        s.onPartial("こんに", 1000)
        s.onPartial("こんにちは", 1500)
        assertTrue(s.onTick(2000).isEmpty()) // 距離上次變動只有 500ms
        assertTrue(commits(s.onTick(2300)).isNotEmpty())
    }

    // ── 去重（最容易寫錯的一條） ──────────────────────────────

    @Test
    fun `沉澱定案後收到相同的最終結果必須什麼都不發`() {
        val s = state()
        s.onPartial("こんにちは", 1000)
        s.onTick(1800)

        assertTrue(
            "同一句被定案兩次會在畫面上出現重複",
            s.onFinal("こんにちは", 2000).isEmpty()
        )
    }

    @Test
    fun `沉澱定案後最終結果多出來的部分才要定案`() {
        val s = state()
        s.onPartial("こんにちは", 1000)
        s.onTick(1800)

        val actions = s.onFinal("こんにちは元気ですか", 2000)
        assertEquals("元気ですか", commits(actions).single().sourceText)
    }

    @Test
    fun `切段留下的前導標點會被清掉`() {
        val s = state()
        s.onPartial("こんにちは", 1000)
        s.onTick(1800)

        val actions = s.onFinal("こんにちは、元気ですか", 2000)
        assertEquals("元気ですか", commits(actions).single().sourceText)
    }

    // ── 語句結束 ───────────────────────────────────────────

    @Test
    fun `最終結果會定案並收掉字幕卡`() {
        val s = state()
        s.onPartial("こんに", 1000)
        val actions = s.onFinal("こんにちは", 1500)

        assertEquals("こんにちは", commits(actions).single().sourceText)
        assertTrue(actions.contains(SubtitleAction.ClearInterim))
    }

    @Test
    fun `辨識器放棄時已聽到的內容仍然定案`() {
        val s = state()
        s.onPartial("こんにちは", 1000)
        val actions = s.onUtteranceAborted()
        assertEquals("こんにちは", commits(actions).single().sourceText)
    }

    @Test
    fun `辨識器放棄且沒有內容時只收掉字幕卡`() {
        val s = state()
        val actions = s.onUtteranceAborted()
        assertTrue(commits(actions).isEmpty())
    }

    @Test
    fun `語句編號逐句遞增`() {
        val s = state()
        assertEquals(1L, s.currentUtteranceId)

        s.onPartial("こんにちは", 1000)
        s.onFinal("こんにちは", 1500)
        assertEquals(2L, s.currentUtteranceId)

        s.onPartial("さようなら", 2000)
        s.onFinal("さようなら", 2500)
        assertEquals(3L, s.currentUtteranceId)
    }

    @Test
    fun `新語句的第一個 partial 一樣立即發出`() {
        val s = state()
        s.onPartial("こんにちは", 1000)
        s.onFinal("こんにちは", 1100)

        // 距離上次發出只有 50ms，但這是新語句的第一次，必須立即發出
        val actions = s.onPartial("さような", 1150)
        assertEquals("さような", interims(actions).single().sourceText)
    }

    @Test
    fun `reset 之後不會殘留前一句的內容`() {
        val s = state()
        s.onPartial("こんにちは", 1000)
        s.reset()

        assertTrue(s.onTick(5000).isEmpty())
        assertEquals("さような", interims(s.onPartial("さような", 5100)).single().sourceText)
    }
}
