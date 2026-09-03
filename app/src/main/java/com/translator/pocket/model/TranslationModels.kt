package com.translator.pocket.model

import java.util.concurrent.atomic.AtomicLong

internal object MessageIds {
    private val seq = AtomicLong(0)
    fun next(): Long = seq.incrementAndGet()
}

enum class AudioOutputTarget {
    AUTO_HEADPHONES,
    EARPIECE,
    SPEAKER,
    MUTE
}

data class LanguageOption(
    val code: String,
    val displayName: String,
    val whisperCode: String
) {
    override fun toString(): String = displayName
}

data class TranslationMessage(
    val id: Long = MessageIds.next(),
    val originalText: String,
    val sourceLangName: String,
    val translatedText: String,
    val targetLangName: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isProvisional: Boolean = false
)

data class InterimSubtitle(
    val utteranceId: Long,
    val sourceText: String,
    val translatedText: String,
    val sourceLangName: String,
    val targetLangName: String
)
