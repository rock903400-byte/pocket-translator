package com.translator.pocket

import com.translator.pocket.engine.RestTranslator
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RestTranslatorTest {

    private fun okBody(text: String): String {
        val q = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        return """{"candidates":[{"content":{"parts":[{"text":$q}]}}]}"""
    }

    private fun translatorOf(server: MockWebServer): RestTranslator {
        // MockWebServer 的 url 尾巴是 "/"，去尾後當 baseUrl
        val base = server.url("/").toString().trimEnd('/')
        return RestTranslator(apiKeyProvider = { "test-key" }, baseUrl = base)
    }

    @Test
    fun `成功回傳譯文`() {
        val server = MockWebServer()
        try {
            server.enqueue(MockResponse().setResponseCode(200).setBody(okBody("Hello, good")))
            val result = runBlocking { translatorOf(server).translatePartial("你好", "en") }
            assertEquals("Hello, good", result)
            val req = server.takeRequest()
            assertTrue(req.path!!.contains(":generateContent"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `429回null不拋錯`() {
        val server = MockWebServer()
        try {
            server.enqueue(MockResponse().setResponseCode(429).setBody("rate limited"))
            assertNull(runBlocking { translatorOf(server).translatePartial("你好", "en") })
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `壞JSON回null不拋錯`() {
        val server = MockWebServer()
        try {
            server.enqueue(MockResponse().setResponseCode(200).setBody("not json {{{"))
            assertNull(runBlocking { translatorOf(server).translatePartial("你好", "en") })
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `空字與空Key直接回null不打網路`() {
        val server = MockWebServer()
        try {
            val noKey = RestTranslator(apiKeyProvider = { "" }, baseUrl = server.url("/").toString().trimEnd('/'))
            assertNull(runBlocking { noKey.translatePartial("你好", "en") })
            val ok = translatorOf(server)
            assertNull(runBlocking { ok.translatePartial("   ", "en") })
            assertEquals(0, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `parse成功與失敗`() {
        assertEquals("Hi", RestTranslator.parseTranslatedText(okBody("  Hi ")))
        assertNull(RestTranslator.parseTranslatedText("""{"error":{"code":429}}"""))
        assertNull(RestTranslator.parseTranslatedText(""))
        assertNull(RestTranslator.parseTranslatedText("{{{"))
    }

    @Test
    fun `去抖規則`() {
        // 空字不打
        assertFalse(RestTranslator.shouldTranslate(1000, 0, "  ", ""))
        // 相同不打
        assertFalse(RestTranslator.shouldTranslate(1000, 0, "你好", "你好"))
        // 500ms內不打
        assertFalse(RestTranslator.shouldTranslate(1200, 1000, "你好嗎", "你好"))
        // 差1字就打（中文1字即1詞）
        assertTrue(RestTranslator.shouldTranslate(2000, 1000, "你好嗎", "你好"))
        // 變短（修正）打
        assertTrue(RestTranslator.shouldTranslate(2000, 1000, "你", "你好嗎"))
        // 改字修正也重打（譯文要跟著修正）
        assertTrue(RestTranslator.shouldTranslate(2000, 1000, "你好呀", "你好嗎"))
    }

    @Test
    fun `prompt包含原文與目標語言`() {
        val p = RestTranslator.buildPrompt("你好", "en")
        assertTrue(p.contains("你好"))
        assertTrue(p.contains("English"))
    }

    @Test
    fun `escape與unescape往返`() {
        val raw = "他說：「你好\"\n換行\\斜線"
        val escaped = RestTranslator.escapeJsonString(raw)
        assertTrue(escaped.startsWith("\"") && escaped.endsWith("\""))
        assertEquals(raw, RestTranslator.unescapeJsonString(escaped.substring(1, escaped.length - 1)))
    }

    @Test
    fun `requestBody是合法JSON且含prompt`() {
        val body = RestTranslator.buildRequestBody("你好\"引號\"", "zh-Hant")
        assertTrue(body.contains("generationConfig"))
        assertTrue(body.contains("Traditional Chinese"))
        // 引號必須被跳脫，否則送出即 400
        assertTrue(body.contains("\\\"引號\\\""))
    }
}
