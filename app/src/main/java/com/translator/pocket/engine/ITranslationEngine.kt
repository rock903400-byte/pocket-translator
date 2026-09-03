package com.translator.pocket.engine

import com.translator.pocket.model.TranslationResult

interface ITranslationEngine {
    /**
     * 接收音訊 WAV 檔案二進位資料並完成語音識別與即時翻譯
     */
    suspend fun translateSpeech(
        wavBytes: ByteArray,
        sourceLangCode: String,
        targetLangCode: String
    ): TranslationResult
}
