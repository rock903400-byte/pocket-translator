package com.translator.pocket

import com.translator.pocket.engine.GtxCircuitBreaker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GtxCircuitBreakerTest {

    @Test
    fun `未達門檻時繼續嘗試`() {
        assertFalse(GtxCircuitBreaker.shouldDisable(0))
        assertFalse(GtxCircuitBreaker.shouldDisable(1))
        assertFalse(GtxCircuitBreaker.shouldDisable(2))
    }

    @Test
    fun `連續三次失敗後停用`() {
        assertTrue(GtxCircuitBreaker.shouldDisable(3))
        assertTrue(GtxCircuitBreaker.shouldDisable(10))
    }
}
