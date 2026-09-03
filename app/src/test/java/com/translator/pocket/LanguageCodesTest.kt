package com.translator.pocket

import com.translator.pocket.model.LanguageCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageCodesTest {

    /** AppSettings.supportedLanguages 內的九種語言代碼 */
    private val appCodes = listOf("ja", "en", "ko", "zh-TW", "de", "fr", "es", "th", "vi")

    @Test
    fun `繁體中文在四種 API 各有不同寫法`() {
        assertEquals("zh-TW", LanguageCodes.toBcp47("zh-TW"))
        // ML Kit 端上模型只有簡體
        assertEquals("zh", LanguageCodes.toMlKitTag("zh-TW"))
        // translate_a 是唯一拿得到繁體的通道
        assertEquals("zh-TW", LanguageCodes.toGtxTag("zh-TW"))
        assertEquals("zh-TW", LanguageCodes.toTtsTag("zh-TW"))
    }

    @Test
    fun `九種語言在每個對照表都有值`() {
        for (code in appCodes) {
            assertTrue("toBcp47 缺 $code", LanguageCodes.toBcp47(code).isNotBlank())
            assertTrue("toGtxTag 缺 $code", LanguageCodes.toGtxTag(code).isNotBlank())
            assertTrue("toTtsTag 缺 $code", LanguageCodes.toTtsTag(code).isNotBlank())
            assertTrue("toMlKitTag 缺 $code", LanguageCodes.toMlKitTag(code) != null)
        }
    }

    @Test
    fun `辨識器代碼帶地區`() {
        assertEquals("ja-JP", LanguageCodes.toBcp47("ja"))
        assertEquals("en-US", LanguageCodes.toBcp47("en"))
        assertEquals("ko-KR", LanguageCodes.toBcp47("ko"))
        assertEquals("vi-VN", LanguageCodes.toBcp47("vi"))
    }

    @Test
    fun `ML Kit 代碼不帶地區`() {
        assertEquals("ja", LanguageCodes.toMlKitTag("ja"))
        assertEquals("en", LanguageCodes.toMlKitTag("en"))
        assertEquals("th", LanguageCodes.toMlKitTag("th"))
    }

    @Test
    fun `未知代碼在 ML Kit 對照表回傳 null 而不是英文`() {
        // 舊版對未知代碼回傳 TranslateLanguage.ENGLISH，會靜默翻成錯的語言
        assertNull(LanguageCodes.toMlKitTag("xx"))
        assertNull(LanguageCodes.toMlKitTag(""))
        assertNull(LanguageCodes.toMlKitTag("klingon"))
    }

    @Test
    fun `未知代碼在其餘對照表原樣回傳`() {
        assertEquals("xx", LanguageCodes.toBcp47("xx"))
        assertEquals("xx", LanguageCodes.toGtxTag("xx"))
        assertEquals("xx", LanguageCodes.toTtsTag("xx"))
    }

    @Test
    fun `大小寫與空白不影響對照`() {
        assertEquals("zh", LanguageCodes.toMlKitTag("ZH-tw"))
        assertEquals("zh-TW", LanguageCodes.toGtxTag("  zh-TW  "))
        assertEquals("ja-JP", LanguageCodes.toBcp47("JA"))
    }

    @Test
    fun `只有繁體中文需要線上升級`() {
        assertTrue(LanguageCodes.isTraditionalChinese("zh-TW"))
        assertTrue(LanguageCodes.isTraditionalChinese("zh-tw"))
        assertFalse(LanguageCodes.isTraditionalChinese("zh"))
        assertFalse(LanguageCodes.isTraditionalChinese("ja"))
        assertFalse(LanguageCodes.isTraditionalChinese("en"))
    }

    @Test
    fun `簡體中文與繁體中文分開對照`() {
        assertEquals("zh-CN", LanguageCodes.toGtxTag("zh"))
        assertEquals("zh-TW", LanguageCodes.toGtxTag("zh-TW"))
    }
}
