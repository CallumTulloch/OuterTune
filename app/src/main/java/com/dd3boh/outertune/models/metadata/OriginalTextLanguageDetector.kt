package com.dd3boh.outertune.models.metadata

import com.carrotsearch.labs.langid.LangIdV3
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class OriginalTextLanguageScore(val language: String, val confidence: Float)

fun interface OriginalTextLanguageDetector {
    suspend fun identify(text: String): List<OriginalTextLanguageScore>
}

/** The bundled BSD model runs offline. Its mutable classifier is shared only under this lock. */
object LangidOriginalTextLanguageDetector : OriginalTextLanguageDetector {
    private val mutex = Mutex()
    private val classifier by lazy { LangIdV3() }

    override suspend fun identify(text: String): List<OriginalTextLanguageScore> = withContext(Dispatchers.Default) {
        mutex.withLock {
            classifier.reset()
            classifier.append(text)
            // rank() reuses both the list and its entries and does not sort them.
            classifier.rank(true).map { OriginalTextLanguageScore(it.langCode, it.confidence.toFloat()) }
                .filter { it.confidence.isFinite() && it.confidence in 0f..1f }
                .sortedByDescending(OriginalTextLanguageScore::confidence)
        }
    }
}
