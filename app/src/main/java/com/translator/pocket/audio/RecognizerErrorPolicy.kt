package com.translator.pocket.audio

/** 收到辨識器錯誤後該做什麼。 */
sealed interface RecognizerAction {

    /** 沿用同一個辨識器實例重新開始聆聽。這是最快的路徑。 */
    data class Restart(val delayMs: Long) : RecognizerAction

    /** binder 或音訊通道卡住了，必須銷毀重建。 */
    data class Recreate(val delayMs: Long) : RecognizerAction

    /** 關掉離線偏好後再試一次（裝置沒有該語言的離線語音包）。 */
    data class RetryWithoutOffline(val delayMs: Long) : RecognizerAction

    /** 沒救了，停止並告訴使用者原因。 */
    data class Abort(val message: String) : RecognizerAction
}

/**
 * 語音辨識錯誤的處置規則。
 *
 * 舊版對所有錯誤一律「銷毀 + 重建 + 延遲 250ms」，於是：
 * - 每次自然停頓（NO_MATCH）都要付一次重建成本，交界處會吃字
 * - 權限不足（9）會每 250ms 無限重試，使用者看到的是永遠靜靜地什麼都不做
 *
 * 刻意寫成純 Kotlin：這裡自己定義錯誤碼常數而不 import SpeechRecognizer，
 * 才能用一般 JUnit 完整測試。這些是平台凍結的公開常數，不會變動。
 */
object RecognizerErrorPolicy {

    // android.speech.SpeechRecognizer.ERROR_* 的對應值
    const val ERROR_NETWORK_TIMEOUT = 1
    const val ERROR_NETWORK = 2
    const val ERROR_AUDIO = 3
    const val ERROR_SERVER = 4
    const val ERROR_CLIENT = 5
    const val ERROR_SPEECH_TIMEOUT = 6
    const val ERROR_NO_MATCH = 7
    const val ERROR_RECOGNIZER_BUSY = 8
    const val ERROR_INSUFFICIENT_PERMISSIONS = 9
    const val ERROR_TOO_MANY_REQUESTS = 10
    const val ERROR_SERVER_DISCONNECTED = 11
    const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
    const val ERROR_LANGUAGE_UNAVAILABLE = 13
    const val ERROR_CANNOT_CHECK_SUPPORT = 14

    /** 連續失敗這麼多次後，不再相信同一個實例。 */
    const val FAILURES_BEFORE_RECREATE = 3

    /** 連續失敗這麼多次後放棄。 */
    const val FAILURES_BEFORE_ABORT = 8

    private const val BACKOFF_BASE_MS = 100L
    private const val BACKOFF_CAP_MS = 3000L

    /**
     * 這個錯誤其實是說話者的自然停頓，不是故障。
     * 應該立刻重新聆聽，並且把已聽到的內容定案。
     */
    fun isNormalPause(error: Int): Boolean =
        error == ERROR_NO_MATCH || error == ERROR_SPEECH_TIMEOUT

    /**
     * @param consecutiveFailures 含這一次在內的連續失敗次數（成功辨識後應歸零）
     * @param preferOffline 目前是否開著離線辨識偏好
     */
    fun decide(error: Int, consecutiveFailures: Int, preferOffline: Boolean): RecognizerAction {
        // 先處理沒有重試意義的情況
        when (error) {
            ERROR_INSUFFICIENT_PERMISSIONS ->
                return RecognizerAction.Abort("缺少麥克風權限，請到系統設定開啟後再試")

            ERROR_LANGUAGE_NOT_SUPPORTED, ERROR_LANGUAGE_UNAVAILABLE -> {
                return if (preferOffline) {
                    // 裝置沒有這個語言的離線語音包，關掉離線偏好再試一次
                    RecognizerAction.RetryWithoutOffline(BACKOFF_BASE_MS)
                } else {
                    RecognizerAction.Abort("此裝置的語音辨識不支援所選語言，請改選其他語言")
                }
            }
        }

        if (consecutiveFailures >= FAILURES_BEFORE_ABORT) {
            return RecognizerAction.Abort("語音辨識連續失敗，請檢查網路連線或重新啟動口譯")
        }

        // 自然停頓：同一個實例、零延遲，這是交界不吃字的關鍵
        if (isNormalPause(error)) {
            return RecognizerAction.Restart(0L)
        }

        val backoff = backoffFor(consecutiveFailures)

        // binder / 音訊通道卡住，換一個實例才有意義
        if (error == ERROR_CLIENT || error == ERROR_AUDIO || error == ERROR_CANNOT_CHECK_SUPPORT) {
            return RecognizerAction.Recreate(maxOf(backoff, 500L))
        }

        // 其他錯誤重試幾次仍不好，就升級成重建
        if (consecutiveFailures >= FAILURES_BEFORE_RECREATE) {
            return RecognizerAction.Recreate(backoff)
        }

        return RecognizerAction.Restart(backoff)
    }

    /** 100 / 200 / 400 / 800 / 1600 / 3000（上限）。 */
    fun backoffFor(consecutiveFailures: Int): Long {
        if (consecutiveFailures <= 1) return BACKOFF_BASE_MS
        val shift = (consecutiveFailures - 1).coerceAtMost(20)
        val value = BACKOFF_BASE_MS shl shift
        return if (value <= 0L || value > BACKOFF_CAP_MS) BACKOFF_CAP_MS else value
    }

    fun describe(error: Int): String = when (error) {
        ERROR_NETWORK_TIMEOUT -> "連線逾時"
        ERROR_NETWORK -> "網路異常"
        ERROR_AUDIO -> "音訊錯誤"
        ERROR_SERVER -> "伺服器錯誤"
        ERROR_CLIENT -> "客戶端重置"
        ERROR_SPEECH_TIMEOUT -> "靜音等待"
        ERROR_NO_MATCH -> "未聽清"
        ERROR_RECOGNIZER_BUSY -> "辨識忙碌"
        ERROR_INSUFFICIENT_PERMISSIONS -> "權限不足"
        ERROR_TOO_MANY_REQUESTS -> "請求過於頻繁"
        ERROR_SERVER_DISCONNECTED -> "伺服器中斷"
        ERROR_LANGUAGE_NOT_SUPPORTED -> "語言不支援"
        ERROR_LANGUAGE_UNAVAILABLE -> "語言暫不可用"
        ERROR_CANNOT_CHECK_SUPPORT -> "無法確認支援狀態"
        else -> "狀態代碼 $error"
    }
}
