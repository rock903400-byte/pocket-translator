package com.translator.pocket.util

import android.os.SystemClock
import android.util.Log
import com.translator.pocket.BuildConfig

/**
 * 把「感覺很慢」變成可以比較的數字。
 *
 * 用法：`adb logcat -s PTLatency`
 * 每個語句以 [onSpeechStart] 起算，之後每個 [mark] 印出距離開口的毫秒數。
 * Release 版完全不做事。
 */
object LatencyLog {

    private const val TAG = "PTLatency"

    const val EVENT_FIRST_PARTIAL = "首個 partial"
    const val EVENT_FIRST_INTERIM_TRANSLATION = "首個譯文"
    const val EVENT_COMMIT = "commit"
    const val EVENT_TTS_START = "TTS 開始"

    @Volatile
    private var utteranceStartMs = 0L

    @Volatile
    private var firstMarkDone = false

    fun onSpeechStart() {
        if (!BuildConfig.DEBUG) return
        utteranceStartMs = SystemClock.elapsedRealtime()
        firstMarkDone = false
        Log.d(TAG, "──────── 偵測到語音開始 ────────")
    }

    fun mark(event: String) {
        if (!BuildConfig.DEBUG) return
        val start = utteranceStartMs
        if (start == 0L) return
        Log.d(TAG, "$event: +${SystemClock.elapsedRealtime() - start}ms")
    }

    /** 只記錄一個語句內的第一次，避免每秒 4 次的 interim 洗版。 */
    fun markOnce(event: String) {
        if (!BuildConfig.DEBUG) return
        if (firstMarkDone) return
        firstMarkDone = true
        mark(event)
    }

    fun reset() {
        if (!BuildConfig.DEBUG) return
        utteranceStartMs = 0L
        firstMarkDone = false
    }
}
