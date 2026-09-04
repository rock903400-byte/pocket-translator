package com.translator.pocket

import com.translator.pocket.engine.GeminiLiveEngine
import com.translator.pocket.model.InterimSubtitle
import com.translator.pocket.model.MessageIds
import com.translator.pocket.model.TranslationMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiLiveEngineTest {

    @Test
    fun `顯示名直通真實模型`() {
        assertEquals(
            "gemini-3.5-live-translate-preview",
            GeminiLiveEngine.normalizeModelName("Gemini 3.5 Live Translate")
        )
        assertEquals(
            "gemini-3.5-live-translate-preview",
            GeminiLiveEngine.normalizeModelName("gemini-3.5-live-translate")
        )
        assertEquals(
            "gemini-3.5-live-translate-preview",
            GeminiLiveEngine.normalizeModelName("")
        )
    }

    @Test
    fun `舊幻覺模型遷移至真實模型`() {
        assertEquals(
            "gemini-3.5-live-translate-preview",
            GeminiLiveEngine.normalizeModelName("gemini-2.5-flash-native-audio-latest")
        )
        assertEquals(
            "gemini-3.5-live-translate-preview",
            GeminiLiveEngine.normalizeModelName("gemini-2.0-flash-live-preview-04-09")
        )
    }

    @Test
    fun `合法模型ID保持不變`() {
        assertEquals(
            "gemini-3.5-live-translate-preview",
            GeminiLiveEngine.normalizeModelName("gemini-3.5-live-translate-preview")
        )
        assertEquals(
            "custom-model-1.0",
            GeminiLiveEngine.normalizeModelName("custom-model-1.0")
        )
    }

    @Test
    fun `繁中映射至zh-Hant`() {
        assertEquals("zh-Hant", GeminiLiveEngine.toLiveTranslateCode("zh-TW"))
        assertEquals("zh-Hant", GeminiLiveEngine.toLiveTranslateCode("zh-HK"))
        assertEquals("zh-Hans", GeminiLiveEngine.toLiveTranslateCode("zh-CN"))
        assertEquals("ja", GeminiLiveEngine.toLiveTranslateCode("ja"))
        assertEquals("en", GeminiLiveEngine.toLiveTranslateCode("en-US"))
    }

    @Test
    fun `MessageIds嚴格遞增不重複`() {
        val a = MessageIds.next()
        val b = MessageIds.next()
        val c = MessageIds.next()
        assertTrue(a < b)
        assertTrue(b < c)
        assertNotEquals(a, b)
    }

    @Test
    fun `interim與commit共用同一id可追蹤`() {
        val utteranceId = 42L
        val interim = InterimSubtitle(
            utteranceId = utteranceId,
            sourceText = "你好",
            translatedText = "Hello",
            sourceLangName = "zh-TW",
            targetLangName = "en"
        )
        val committed = TranslationMessage(
            id = utteranceId,
            originalText = "你好",
            sourceLangName = "zh-TW",
            translatedText = "Hello",
            targetLangName = "en"
        )
        assertEquals(interim.utteranceId, committed.id)
    }

    @Test
    fun `連續語句id遞增不碰撞`() {
        val first = TranslationMessage(
            id = 7L,
            originalText = "a",
            sourceLangName = "ja",
            translatedText = "b",
            targetLangName = "zh-TW"
        )
        val second = TranslationMessage(
            id = 8L,
            originalText = "c",
            sourceLangName = "ja",
            translatedText = "d",
            targetLangName = "zh-TW"
        )
        assertNotEquals(first.id, second.id)
        assertTrue(second.id > first.id)
    }
}
