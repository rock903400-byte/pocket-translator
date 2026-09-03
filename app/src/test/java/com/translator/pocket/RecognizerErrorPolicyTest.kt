package com.translator.pocket

import com.translator.pocket.audio.RecognizerAction
import com.translator.pocket.audio.RecognizerErrorPolicy
import com.translator.pocket.audio.RecognizerErrorPolicy.ERROR_AUDIO
import com.translator.pocket.audio.RecognizerErrorPolicy.ERROR_CLIENT
import com.translator.pocket.audio.RecognizerErrorPolicy.ERROR_INSUFFICIENT_PERMISSIONS
import com.translator.pocket.audio.RecognizerErrorPolicy.ERROR_LANGUAGE_NOT_SUPPORTED
import com.translator.pocket.audio.RecognizerErrorPolicy.ERROR_LANGUAGE_UNAVAILABLE
import com.translator.pocket.audio.RecognizerErrorPolicy.ERROR_NETWORK
import com.translator.pocket.audio.RecognizerErrorPolicy.ERROR_NO_MATCH
import com.translator.pocket.audio.RecognizerErrorPolicy.ERROR_RECOGNIZER_BUSY
import com.translator.pocket.audio.RecognizerErrorPolicy.ERROR_SERVER
import com.translator.pocket.audio.RecognizerErrorPolicy.ERROR_SPEECH_TIMEOUT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognizerErrorPolicyTest {

    private fun decide(error: Int, failures: Int = 1, preferOffline: Boolean = false) =
        RecognizerErrorPolicy.decide(error, failures, preferOffline)

    // ── 自然停頓 ───────────────────────────────────────────

    @Test
    fun `未聽清與靜音逾時是自然停頓`() {
        assertTrue(RecognizerErrorPolicy.isNormalPause(ERROR_NO_MATCH))
        assertTrue(RecognizerErrorPolicy.isNormalPause(ERROR_SPEECH_TIMEOUT))
        assertFalse(RecognizerErrorPolicy.isNormalPause(ERROR_NETWORK))
        assertFalse(RecognizerErrorPolicy.isNormalPause(ERROR_CLIENT))
    }

    @Test
    fun `自然停頓用同一個實例零延遲重啟`() {
        // 這是語句交界不吃字的關鍵：不重建、不延遲
        assertEquals(RecognizerAction.Restart(0L), decide(ERROR_NO_MATCH, failures = 0))
        assertEquals(RecognizerAction.Restart(0L), decide(ERROR_SPEECH_TIMEOUT, failures = 0))
    }

    // ── 重試與退避 ─────────────────────────────────────────

    @Test
    fun `退避序列為 100 200 400 800 1600 並以 3000 為上限`() {
        assertEquals(100L, RecognizerErrorPolicy.backoffFor(1))
        assertEquals(200L, RecognizerErrorPolicy.backoffFor(2))
        assertEquals(400L, RecognizerErrorPolicy.backoffFor(3))
        assertEquals(800L, RecognizerErrorPolicy.backoffFor(4))
        assertEquals(1600L, RecognizerErrorPolicy.backoffFor(5))
        assertEquals(3000L, RecognizerErrorPolicy.backoffFor(6))
        assertEquals(3000L, RecognizerErrorPolicy.backoffFor(50))
    }

    @Test
    fun `退避不會因為位移溢位而變成負數`() {
        assertEquals(3000L, RecognizerErrorPolicy.backoffFor(1000))
    }

    @Test
    fun `辨識器忙碌時沿用同一個實例退避重試`() {
        assertEquals(RecognizerAction.Restart(100L), decide(ERROR_RECOGNIZER_BUSY, failures = 1))
        assertEquals(RecognizerAction.Restart(200L), decide(ERROR_RECOGNIZER_BUSY, failures = 2))
    }

    @Test
    fun `網路類錯誤沿用同一個實例`() {
        assertTrue(decide(ERROR_NETWORK, failures = 1) is RecognizerAction.Restart)
        assertTrue(decide(ERROR_SERVER, failures = 1) is RecognizerAction.Restart)
    }

    // ── 需要重建的錯誤 ──────────────────────────────────────

    @Test
    fun `客戶端與音訊錯誤才需要重建實例`() {
        assertTrue(decide(ERROR_CLIENT, failures = 1) is RecognizerAction.Recreate)
        assertTrue(decide(ERROR_AUDIO, failures = 1) is RecognizerAction.Recreate)
    }

    @Test
    fun `連續失敗三次後升級為重建`() {
        assertTrue(decide(ERROR_NETWORK, failures = 2) is RecognizerAction.Restart)
        assertTrue(decide(ERROR_NETWORK, failures = 3) is RecognizerAction.Recreate)
        assertTrue(decide(ERROR_NETWORK, failures = 5) is RecognizerAction.Recreate)
    }

    // ── 放棄 ──────────────────────────────────────────────

    @Test
    fun `權限不足立即放棄而不是無限重試`() {
        // 舊版對這個錯誤每 250ms 重試一次，使用者只看到畫面永遠停在聆聽中
        val action = decide(ERROR_INSUFFICIENT_PERMISSIONS, failures = 1)
        assertTrue(action is RecognizerAction.Abort)
        assertTrue((action as RecognizerAction.Abort).message.contains("麥克風"))
    }

    @Test
    fun `連續失敗八次後放棄`() {
        assertTrue(decide(ERROR_NETWORK, failures = 7) is RecognizerAction.Recreate)
        assertTrue(decide(ERROR_NETWORK, failures = 8) is RecognizerAction.Abort)
    }

    // ── 離線語音包 ─────────────────────────────────────────

    @Test
    fun `語言不支援時先關掉離線偏好重試一次`() {
        assertTrue(
            decide(ERROR_LANGUAGE_UNAVAILABLE, failures = 1, preferOffline = true)
                is RecognizerAction.RetryWithoutOffline
        )
        assertTrue(
            decide(ERROR_LANGUAGE_NOT_SUPPORTED, failures = 1, preferOffline = true)
                is RecognizerAction.RetryWithoutOffline
        )
    }

    @Test
    fun `已經沒用離線偏好還是語言不支援就放棄`() {
        assertTrue(
            decide(ERROR_LANGUAGE_UNAVAILABLE, failures = 1, preferOffline = false)
                is RecognizerAction.Abort
        )
    }

    @Test
    fun `權限不足優先於連續失敗次數`() {
        assertTrue(
            decide(ERROR_INSUFFICIENT_PERMISSIONS, failures = 20) is RecognizerAction.Abort
        )
    }

    // ── 描述文字 ───────────────────────────────────────────

    @Test
    fun `每個已知錯誤碼都有可讀的描述`() {
        val known = listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14)
        for (code in known) {
            val text = RecognizerErrorPolicy.describe(code)
            assertFalse("錯誤碼 $code 缺少描述", text.contains("狀態代碼"))
        }
        assertTrue(RecognizerErrorPolicy.describe(999).contains("999"))
    }
}
