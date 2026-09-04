package com.translator.pocket.model

/**
 * 即時字幕顯示決策（純 Kotlin，零 Android 依賴，方便單元測試）。
 *
 * 背景：A 線（transcribe 即時原文）與 B 線（live-translate 定案）同時寫同一張
 * interim 卡，id 空間又不互通。這裡收斂三條規則：
 * 1. A 異常不准霸佔狀態列：恢復時還原 B 的最後狀態。
 * 2. B 的 endpoint 快照不准把已有的暫存譯文洗回「翻譯中…」。
 */
object InterimPolicy {

    const val ANOMALY_PREFIX = "即時字幕連線異常"
    const val TRANSLATING_PLACEHOLDER = "翻譯中…"
    const val LISTENING_PLACEHOLDER = "…"

    /**
     * A 線恢復連線時：只有當目前狀態仍是 A 的異常文，才還原成 B 的最後狀態，
     * 否則不碰（B 可能已有更新）。
     * 回傳 null 代表不用還原。
     */
    fun restoreAfterAnomaly(currentStatus: String, lastBStatus: String): String? =
        if (currentStatus.startsWith(ANOMALY_PREFIX)) lastBStatus else null

    /**
     * B 線 endpoint 快照合併：
     * - 原文：B 有就用 B 的，否則沿用畫面上的。
     * - 譯文：B 有就用 B 的；B 還沒有時，沿用畫面上的 REST 暫存譯文，
     *   絕不洗回等待字（這就是定案前那次閃爍的根因）。
     */
    fun mergeBInterim(
        curSource: String,
        curTrans: String,
        bOrig: String,
        bTrans: String
    ): Pair<String, String> {
        val source = bOrig.ifBlank { curSource.ifBlank { LISTENING_PLACEHOLDER } }
        val kept = curTrans.ifBlank { TRANSLATING_PLACEHOLDER }
        val trans = bTrans.ifBlank { kept }
        return source to trans
    }
}
