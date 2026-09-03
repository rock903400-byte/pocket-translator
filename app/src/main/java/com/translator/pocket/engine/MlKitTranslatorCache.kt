package com.translator.pocket.engine

import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.translator.pocket.model.LanguageCodes
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resumeWithException

/**
 * 讓 ML Kit 的 [Translator] 在整個口譯 session 內保持存活。
 *
 * 舊版每翻一句就 getClient() -> downloadModelIfNeeded() -> translate() -> close()，
 * 光是建構與關閉就佔掉 100~400ms，第一次還要再付 300~800ms 的模型載入。
 * 即時字幕一秒要翻 4 次，這個成本必須一次付清、之後歸零。
 */
class MlKitTranslatorCache {

    companion object {
        private const val TAG = "MlKitTranslatorCache"

        /** 單一語言模型的概略大小，用於提示使用者，不需要精確。 */
        const val APPROX_MODEL_MB = 30
    }

    sealed interface Prepare {
        /** 模型就緒（已下載並完成暖機），或來源與目標同語言而不需翻譯。 */
        data object Ready : Prepare

        /** 模型下載或載入失敗，呼叫端應改走線上翻譯，而不是中止整個 session。 */
        data class Failed(val reason: String) : Prepare

        /** ML Kit 沒有這個語言的端上模型。 */
        data class Unsupported(val langCode: String) : Prepare
    }

    private val translators = ConcurrentHashMap<String, Translator>()

    /**
     * 明確指定下載條件且刻意「不」要求 Wi-Fi：隨身口譯的使用情境就是在國外用行動網路，
     * 要求 Wi-Fi 等於在最需要它的時候不能用。預設值本來就不要求，寫明以免日後版本悄悄改變。
     */
    private val downloadConditions = DownloadConditions.Builder().build()

    /**
     * 建立（或取得）語言對的翻譯器，必要時下載模型，並做一次暖機翻譯。
     *
     * @param onDownloadStarted 只有在模型尚未下載時才會被呼叫，參數為概略 MB 數。
     */
    suspend fun prepare(
        srcAppCode: String,
        tgtAppCode: String,
        onDownloadStarted: (approxMb: Int) -> Unit = {}
    ): Prepare {
        val src = mlKitTag(srcAppCode) ?: return Prepare.Unsupported(srcAppCode)
        val tgt = mlKitTag(tgtAppCode) ?: return Prepare.Unsupported(tgtAppCode)
        if (src == tgt) return Prepare.Ready

        return try {
            val translator = obtain(src, tgt)

            // 先問過再提示，模型早就在的話使用者什麼都不會看到
            if (!isDownloaded(src) || !isDownloaded(tgt)) {
                onDownloadStarted(APPROX_MODEL_MB)
            }
            translator.downloadModelIfNeeded(downloadConditions).awaitTask()

            // 暖機：首次翻譯要載入模型進記憶體，把這個成本付在「正在準備」而不是使用者的第一句話
            runCatching { translator.translate("hello").awaitTask() }

            Log.d(TAG, "端上翻譯模型已就緒: $src>$tgt")
            Prepare.Ready
        } catch (e: Exception) {
            Log.w(TAG, "準備端上翻譯模型失敗 ($src>$tgt)", e)
            Prepare.Failed(e.localizedMessage ?: "模型下載失敗")
        }
    }

    /**
     * 以快取的翻譯器翻譯。回傳 null 代表這條路走不通（語言不支援或模型未就緒），
     * 呼叫端應改走線上翻譯。
     */
    suspend fun translate(text: String, srcAppCode: String, tgtAppCode: String): String? {
        if (text.isBlank()) return null
        val src = mlKitTag(srcAppCode) ?: return null
        val tgt = mlKitTag(tgtAppCode) ?: return null
        if (src == tgt) return text

        return try {
            obtain(src, tgt).translate(text).awaitTask()
        } catch (e: Exception) {
            Log.w(TAG, "端上翻譯失敗 ($src>$tgt): ${e.message}")
            null
        }
    }

    /** 釋放所有翻譯器。冪等，可安全重複呼叫。 */
    fun close() {
        val snapshot = translators.values.toList()
        translators.clear()
        snapshot.forEach { runCatching { it.close() } }
    }

    private fun obtain(srcTag: String, tgtTag: String): Translator =
        translators.computeIfAbsent("$srcTag>$tgtTag") {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(srcTag)
                    .setTargetLanguage(tgtTag)
                    .build()
            )
        }

    /** App 代碼 -> ML Kit 語言標籤，並用 ML Kit 自己的表驗證。不支援時回傳 null。 */
    private fun mlKitTag(appCode: String): String? =
        LanguageCodes.toMlKitTag(appCode)?.let { TranslateLanguage.fromLanguageTag(it) }

    private suspend fun isDownloaded(langTag: String): Boolean = try {
        RemoteModelManager.getInstance()
            .isModelDownloaded(TranslateRemoteModel.Builder(langTag).build())
            .awaitTask()
    } catch (e: Exception) {
        false // 問不到就當作沒有，最壞只是多顯示一次下載提示
    }
}

/**
 * 以 resumeWith(Result.success(...)) 而非 resume(...) 交還結果：
 * Task<Void> 成功時 result 為 null，走 resume() 會撞上 Kotlin 的非空檢查。
 */
private suspend fun <T> Task<T>.awaitTask(): T =
    suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { result -> continuation.resumeWith(Result.success(result)) }
        addOnFailureListener { exception -> continuation.resumeWithException(exception) }
    }
