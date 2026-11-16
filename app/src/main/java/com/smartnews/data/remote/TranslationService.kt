package com.smartnews.data.remote

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { c ->
    addOnSuccessListener { c.resume(it) }
    addOnFailureListener { c.resumeWithException(it) }
    addOnCanceledListener { c.cancel() }
}

/**
 * On-device machine translation (Google ML Kit). Free, private, works offline once the
 * language models (~30 MB each) have been downloaded on first use.
 */
@Singleton
class TranslationService @Inject constructor() {
    private val identifier by lazy { LanguageIdentification.getClient() }
    private val translators = ConcurrentHashMap<String, Translator>()
    private val ready = ConcurrentHashMap.newKeySet<String>()
    private val downloadLock = Mutex()

    /** Fast script check first; ML Kit language identification otherwise. Returns null if unknown. */
    suspend fun detect(text: String): String? {
        val letters = text.filter { it.isLetter() }
        if (letters.isEmpty()) return null
        val arabic = letters.count { it in '؀'..'ۿ' || it in 'ݐ'..'ݿ' }
        if (arabic > letters.length * 0.4) return "ar"
        val tag = runCatching { identifier.identifyLanguage(text.take(500)).await() }.getOrNull()
        return when {
            tag == null || tag == "und" -> if (letters.all { it.code < 0x250 }) "en" else null
            else -> tag.substringBefore('-')
        }
    }

    fun supported(lang: String) = TranslateLanguage.fromLanguageTag(lang) != null

    private suspend fun client(from: String, to: String): Translator {
        val key = "$from>$to"
        val t = translators.getOrPut(key) {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(TranslateLanguage.fromLanguageTag(from)!!)
                    .setTargetLanguage(TranslateLanguage.fromLanguageTag(to)!!)
                    .build()
            )
        }
        if (key !in ready) downloadLock.withLock {
            if (key !in ready) {
                t.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
                ready += key
            }
        }
        return t
    }

    /** Throws if the model can't be downloaded (e.g. offline the first time). */
    suspend fun translate(text: String, from: String, to: String): String {
        if (text.isBlank() || from == to) return text
        return client(from, to).translate(text).await()
    }
}
