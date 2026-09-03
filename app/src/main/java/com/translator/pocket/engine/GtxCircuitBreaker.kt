package com.translator.pocket.engine

/**
 * translate_a 是非官方端點，高負載時會 429。決定連續失敗幾次後放棄升級成繁體，
 * 而不是每一句都再打一次注定失敗的請求。
 *
 * 抽成純函式方便測試；[BuiltinEngine] 用 AtomicInteger/AtomicBoolean 保存實際狀態。
 */
object GtxCircuitBreaker {

    /** 連續失敗這麼多次後停用，本 session 維持簡體到底。 */
    const val FAILURES_BEFORE_DISABLE = 3

    fun shouldDisable(consecutiveFailures: Int): Boolean =
        consecutiveFailures >= FAILURES_BEFORE_DISABLE
}
