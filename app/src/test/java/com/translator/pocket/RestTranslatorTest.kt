package com.translator.pocket

import com.sun.net.httpserver.HttpServer
import com.translator.pocket.engine.RestTranslator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

class RestTranslatorTest {

    private fun okBody(text: String) =
        """{"candidates":[{"content":{"parts":[{"text":${
            JSONObjectQuote(text)
        }}]}}]}"""

    private fun JSONObjectQuote(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun server(status: Int, body: String): Pair<HttpServer, String> {
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        srv.createContext("/") { exchange ->
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        srv.start()
        return srv to "http://127.0.0.1:${srv.address.port}"
    }

    @Test
    fun `成功回傳譯文`() {
        val (srv, url) = server(200, okBody("Hello, good"))
        try {
            val t = RestTranslator(
                apiKeyProvider = { "test-key" },
                baseUrl = url
            )
            val result = runBlocking { t.translatePartial("你好", "en") }
            assertEquals("Hello, good", result)
        } finally {
            srv.stop(0)
        }
    }

    @Test
    fun `429回null不拋錯`() {
        val (srv, url) = server(429, "rate limited")
        try {
            val t = RestTranslator(apiKeyProvider = { "test-key" }, baseUrl = url)
            assertNull(runBlocking { t.translatePartial("你好", "en") })
        } finally {
            srv.stop(0)
        }
    }

    @Test
    fun `壞JSON回null不拋錯`() {
        val (srv, url) = server(200, "not json {{{")
        try {
            val t = RestTranslator(apiKeyProvider = { "test-key" }, baseUrl = url)
            assertNull(runBlocking { t.translatePartial("你好", "en") })
        } finally {
            srv.stop(0)
        }
    }

    @Test
    fun `空字與空Key直接回null不打網路`() {
        val hits = AtomicInteger(0)
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        srv.createContext("/") { exchange ->
            hits.incrementAndGet()
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.close()
        }
        srv.start()
        try {
            val url = "http://127.0.0.1:${srv.address.port}"
            val noKey = RestTranslator(apiKeyProvider = { "" }, baseUrl = url)
            assertNull(runBlocking { noKey.translatePartial("你好", "en") })
            val ok = RestTranslator(apiKeyProvider = { "k" }, baseUrl = url)
            assertNull(runBlocking { ok.translatePartial("   ", "en") })
            assertEquals(0, hits.get())
        } finally {
            srv.stop(0)
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
        // 差2字以上打
        assertTrue(RestTranslator.shouldTranslate(2000, 1000, "你好嗎", "你好"))
        // 變短（修正）打
        assertTrue(RestTranslator.shouldTranslate(2000, 1000, "你", "你好嗎"))
        // 只差1字不打
        assertFalse(RestTranslator.shouldTranslate(2000, 1000, "你好呀", "你好嗎"))
    }

    @Test
    fun `prompt包含原文與目標語言`() {
        val p = RestTranslator.buildPrompt("你好", "en")
        assertTrue(p.contains("你好"))
        assertTrue(p.contains("English"))
    }
}
