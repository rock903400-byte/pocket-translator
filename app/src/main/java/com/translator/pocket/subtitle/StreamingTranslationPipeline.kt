package com.translator.pocket.subtitle

import android.util.Log
import com.translator.pocket.engine.BuiltinEngine
import com.translator.pocket.util.LatencyLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * 執行 [LiveSubtitleState] 決策出來的動作：翻譯、丟掉過期結果、把結果交出去。
 *
 * 這個類別刻意做得很薄 —— 所有值得測的判斷都在 [LiveSubtitleState] 裡。
 *
 * 為什麼用 Channel 而不是 Flow.debounce + collectLatest：
 * collectLatest 會取消進行中的 collector，但 ML Kit 的呼叫是 suspendCancellableCoroutine
 * 包住 GMS Task —— 取消協程只是丟掉 continuation，Task 照樣跑完、CPU 照樣付，
 * 還可能同時有兩個翻譯在飛。CONFLATED channel 搭配單一序列消費者，
 * 則永遠不會啟動一個打算丟棄的翻譯。
 */
class StreamingTranslationPipeline(
    private val engine: BuiltinEngine,
    private val scope: CoroutineScope,
    private val onInterim: (utteranceId: Long, sourceText: String, translatedText: String) -> Unit,
    private val onCommitted: (utteranceId: Long, sourceText: String, translatedText: String) -> Unit,
    private val onInterimCleared: () -> Unit
) {

    companion object {
        private const val TAG = "StreamingPipeline"
    }

    @Volatile
    private var sourceLang = "ja"

    @Volatile
    private var targetLang = "zh-TW"

    /** 進行中的字幕可以丟棄：翻譯途中來了新假設，舊的整個跳過。 */
    private val interimInbox = Channel<InterimJob>(Channel.CONFLATED)

    /** 已定案的段落不可丟棄，每一段都必須送達畫面與 TTS。 */
    private val commitQueue = Channel<SubtitleAction.Commit>(capacity = 32)

    /** 過期防護：只有序號等於最新值的 interim 結果才會被採用。 */
    private val latestInterimSeq = AtomicLong(0)

    private data class InterimJob(val seq: Long, val utteranceId: Long, val sourceText: String)

    init {
        scope.launch {
            for (job in interimInbox) {
                if (job.seq != latestInterimSeq.get()) continue // 已被更新的假設取代
                val translated = engine.translateText(job.sourceText, sourceLang, targetLang)
                if (job.seq != latestInterimSeq.get()) continue // 翻譯期間又被取代
                LatencyLog.markOnce(LatencyLog.EVENT_FIRST_INTERIM_TRANSLATION)
                onInterim(job.utteranceId, job.sourceText, translated)
            }
        }

        scope.launch {
            for (commit in commitQueue) {
                val translated = engine.translateText(commit.sourceText, sourceLang, targetLang)
                LatencyLog.mark(LatencyLog.EVENT_COMMIT)
                onCommitted(commit.utteranceId, commit.sourceText, translated)
            }
        }
    }

    fun setLanguages(src: String, tgt: String) {
        sourceLang = src
        targetLang = tgt
    }

    /** 從語音辨識器的主執行緒呼叫，絕不阻塞。 */
    fun submit(actions: List<SubtitleAction>) {
        for (action in actions) {
            when (action) {
                is SubtitleAction.UpdateInterim -> {
                    val seq = latestInterimSeq.incrementAndGet()
                    interimInbox.trySend(InterimJob(seq, action.utteranceId, action.sourceText))
                }

                is SubtitleAction.Commit -> {
                    if (commitQueue.trySend(action).isFailure) {
                        Log.w(TAG, "commit 佇列已滿，丟棄一段：${action.sourceText.take(20)}")
                    }
                }

                SubtitleAction.ClearInterim -> {
                    // 讓所有在飛的 interim 翻譯過期，否則字幕卡會被慢一步的結果重新叫回來
                    latestInterimSeq.incrementAndGet()
                    onInterimCleared()
                }
            }
        }
    }

    fun close() {
        latestInterimSeq.incrementAndGet()
        interimInbox.close()
        commitQueue.close()
    }
}
