package com.translator.pocket.tts

/**
 * 決定新的一段譯文該排隊還是清空重來。
 *
 * 口譯情境裡，**當下的資訊勝過完整的資訊**：
 * 若無上限地 QUEUE_ADD，在快節奏對話中佇列只會愈積愈長，
 * 最後使用者聽到的是三十秒前那句話的翻譯。寧可跳過落後的內容追上現在。
 */
object TtsQueuePolicy {

    /** 最多允許「正在唸的一句 + 排隊的一句」。第三句到達就清空跳到最新。 */
    const val MAX_PENDING = 2

    fun shouldFlush(pending: Int, max: Int = MAX_PENDING): Boolean = pending >= max
}
