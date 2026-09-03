package com.translator.pocket.subtitle

/** [LiveSubtitleState] 對外的決策結果。刻意做成不可變值，方便測試與跨執行緒傳遞。 */
sealed interface SubtitleAction {

    /** 更新畫面底部那張「進行中」字幕卡。 */
    data class UpdateInterim(val utteranceId: Long, val sourceText: String) : SubtitleAction

    /** 這段話已經定案，應該進入對話紀錄並朗讀。 */
    data class Commit(val utteranceId: Long, val sourceText: String) : SubtitleAction

    /** 收掉進行中的字幕卡。 */
    data object ClearInterim : SubtitleAction
}

/**
 * 把語音辨識器吐出的不穩定假設，轉成一串「要顯示什麼、什麼時候定案」的動作。
 *
 * 這是整個即時字幕的決策核心，刻意寫成純 Kotlin（零 Android import）、
 * 時間一律由呼叫端以 nowMs 傳入，所以不需要時鐘也不需要 sleep 就能完整測試。
 *
 * 執行緒：只從語音辨識器的主執行緒 callback 進入，回傳不可變動作，
 * 因此內部完全不需要鎖或 atomic。
 */
class LiveSubtitleState(
    /** 兩次字幕更新的最小間隔。第一次一律立即發出（leading edge）。 */
    private val minIntervalMs: Long = 250,
    /** 短於這個長度的假設視為雜訊。 */
    private val minChars: Int = 2,
    /** 進行中字幕超過這個長度就設法切段，避免無限重譯同一長句。 */
    private val maxLiveChars: Int = 60,
    /** 假設停止變動這麼久就直接定案，不等辨識器的 endpointing。 */
    private val settleCommitMs: Long = 700
) {

    companion object {
        /** 句子結束的標點。逗號不算，否則會切得太碎。 */
        private const val TERMINAL_PUNCTUATION = "。．.!?！？…"

        /** 找不到終止標點時可以退而求其次的切點。 */
        private const val SOFT_BOUNDARY = "、，,；;：: \t\n"
    }

    var currentUtteranceId: Long = 1L
        private set

    /** 目前假設中已經 commit 掉的字元數。 */
    private var committedChars = 0

    private var lastHypothesis = ""
    private var lastEmittedInterim = ""
    private var lastEmitMs = 0L
    private var lastChangeMs = 0L

    /** 已因沉澱而定案，等 onFinal 來去重。 */
    private var settled = false

    /** 長度切段用：連續兩次前 maxLiveChars 字沒變才敢切。 */
    private var lastHead = ""
    private var stableHeadCount = 0

    /** 收到一次 partial 假設。 */
    fun onPartial(rawHypothesis: String, nowMs: Long): List<SubtitleAction> {
        val hypothesis = rawHypothesis.trim()
        if (hypothesis.length < minChars) return emptyList()
        if (hypothesis == lastHypothesis) return emptyList() // 辨識器經常重複送同一句

        alignCommittedTo(hypothesis)
        lastHypothesis = hypothesis
        lastChangeMs = nowMs
        settled = false

        val actions = mutableListOf<SubtitleAction>()
        var committedSomething = false

        // 1. 句中出現終止標點且後面還有內容 -> 前半段可以安全定案
        val punctuationCut = terminalPunctuationCut(pending())
        if (punctuationCut > 0) {
            val head = sanitize(pending().substring(0, punctuationCut))
            if (head.length >= minChars) {
                actions += SubtitleAction.Commit(currentUtteranceId, head)
                committedChars += punctuationCut
                committedSomething = true
                resetHeadTracking()
            }
        }

        // 2. 太長且開頭已穩定 -> 在邊界硬切，避免一直重譯整段長句
        if (!committedSomething) {
            val lengthCut = stableLengthCut()
            if (lengthCut > 0) {
                val head = sanitize(pending().substring(0, lengthCut))
                if (head.length >= minChars) {
                    actions += SubtitleAction.Commit(currentUtteranceId, head)
                    committedChars += lengthCut
                    committedSomething = true
                    resetHeadTracking()
                }
            }
        }

        // 3. 更新進行中的字幕
        val live = pending().trim()
        when {
            live.isEmpty() -> {
                if (lastEmittedInterim.isNotEmpty()) {
                    actions += SubtitleAction.ClearInterim
                    lastEmittedInterim = ""
                }
            }

            live == lastEmittedInterim -> Unit // 沒變就不發

            // 第一次一律立即發出；剛 commit 過也要立刻讓卡片縮短，其餘走節流
            lastEmitMs == 0L || committedSomething || nowMs - lastEmitMs >= minIntervalMs -> {
                actions += SubtitleAction.UpdateInterim(currentUtteranceId, live)
                lastEmittedInterim = live
                lastEmitMs = nowMs
            }
        }

        return actions
    }

    /**
     * 沉澱計時器。呼叫端定期（例如每 250ms）呼叫。
     *
     * Google 的 endpointing 常要 1.5~2 秒才會給最終結果，這裡先行定案是最大的單項延遲改善。
     */
    fun onTick(nowMs: Long): List<SubtitleAction> {
        if (settled || lastHypothesis.isEmpty()) return emptyList()
        if (nowMs - lastChangeMs < settleCommitMs) return emptyList()

        settled = true
        val rest = sanitize(pending())
        committedChars = lastHypothesis.length

        val actions = mutableListOf<SubtitleAction>()
        if (rest.length >= minChars) {
            actions += SubtitleAction.Commit(currentUtteranceId, rest)
        }
        if (lastEmittedInterim.isNotEmpty()) {
            actions += SubtitleAction.ClearInterim
            lastEmittedInterim = ""
        }
        return actions
    }

    /**
     * 辨識器給出最終結果。
     *
     * 若稍早已因沉澱定案且文字相同，這裡必須什麼都不發 —— 否則同一句會出現兩次。
     */
    fun onFinal(rawHypothesis: String, nowMs: Long): List<SubtitleAction> {
        val hypothesis = rawHypothesis.trim()
        if (hypothesis.isEmpty()) return onUtteranceAborted()

        alignCommittedTo(hypothesis)
        lastHypothesis = hypothesis

        val actions = mutableListOf<SubtitleAction>()
        val rest = sanitize(pending())
        if (rest.isNotEmpty()) {
            actions += SubtitleAction.Commit(currentUtteranceId, rest)
        }
        if (lastEmittedInterim.isNotEmpty()) {
            actions += SubtitleAction.ClearInterim
        }

        startNextUtterance()
        return actions
    }

    /**
     * 辨識器放棄了這一段（ERROR_NO_MATCH / ERROR_SPEECH_TIMEOUT）。
     * 已經聽到的文字是真的，該定案就定案。
     */
    fun onUtteranceAborted(): List<SubtitleAction> {
        val actions = mutableListOf<SubtitleAction>()
        val rest = sanitize(pending())
        if (rest.isNotEmpty()) {
            actions += SubtitleAction.Commit(currentUtteranceId, rest)
        }
        if (lastEmittedInterim.isNotEmpty()) {
            actions += SubtitleAction.ClearInterim
        }

        startNextUtterance()
        return actions
    }

    /** 完全重來（切換語言、切換發話方、停止時使用）。 */
    fun reset() {
        startNextUtterance()
    }

    // ── 內部 ─────────────────────────────────────────────

    /** 目前假設中尚未定案的部分。 */
    private fun pending(): String =
        if (committedChars >= lastHypothesis.length) "" else lastHypothesis.substring(committedChars)

    /**
     * 假設有可能變短或被改寫。若新假設不是舊假設的延伸，
     * 把已定案長度夾到兩者的共同前綴，避免切錯位置。
     */
    private fun alignCommittedTo(hypothesis: String) {
        if (lastHypothesis.isEmpty()) return
        if (hypothesis.startsWith(lastHypothesis)) return // 單純變長，不用處理

        committedChars = minOf(committedChars, commonPrefixLength(hypothesis, lastHypothesis))
        resetHeadTracking()
    }

    /**
     * 找出句中終止標點的切點；標點後面必須還有實際內容，
     * 才能確定這句真的講完了（而不是剛好停在句號上）。
     * 回傳切點長度（含標點），0 表示不切。
     */
    private fun terminalPunctuationCut(segment: String): Int {
        var cut = 0
        for (i in segment.indices) {
            if (segment[i] in TERMINAL_PUNCTUATION && segment.substring(i + 1).isNotBlank()) {
                cut = i + 1
            }
        }
        return cut
    }

    /**
     * 段落過長時的硬切。需要開頭連續兩次未變，確認辨識器不再改寫前面那段。
     * 回傳切點長度，0 表示不切。
     */
    private fun stableLengthCut(): Int {
        val segment = pending()
        if (segment.length <= maxLiveChars) {
            resetHeadTracking()
            return 0
        }

        val head = segment.substring(0, maxLiveChars)
        if (head == lastHead) {
            stableHeadCount++
        } else {
            lastHead = head
            stableHeadCount = 1
        }
        if (stableHeadCount < 2) return 0

        return softBoundaryBefore(segment, maxLiveChars)
    }

    /**
     * 從 cap 往前找一個像樣的切點。中日文沒有空格，找不到就在 cap 硬切。
     */
    private fun softBoundaryBefore(segment: String, cap: Int): Int {
        for (i in cap - 1 downTo minChars) {
            if (segment[i] in TERMINAL_PUNCTUATION || segment[i] in SOFT_BOUNDARY) {
                return i + 1
            }
        }
        return cap
    }

    /** 切段後開頭可能留下逗號、頓號或空白，去掉才不會出現「、元氣ですか」這種譯文。 */
    private fun sanitize(text: String): String =
        text.trim().trimStart(*SOFT_BOUNDARY.toCharArray()).trim()

    private fun resetHeadTracking() {
        lastHead = ""
        stableHeadCount = 0
    }

    private fun startNextUtterance() {
        currentUtteranceId++
        committedChars = 0
        lastHypothesis = ""
        lastEmittedInterim = ""
        lastEmitMs = 0L
        lastChangeMs = 0L
        settled = false
        resetHeadTracking()
    }

    private fun commonPrefixLength(a: String, b: String): Int {
        val max = minOf(a.length, b.length)
        var i = 0
        while (i < max && a[i] == b[i]) i++
        return i
    }
}
