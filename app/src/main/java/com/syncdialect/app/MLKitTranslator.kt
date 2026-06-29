package com.syncdialect.app

import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.common.model.DownloadConditions
import kotlinx.coroutines.tasks.await

class MLKitTranslator {
    private var translator: Translator? = null
    var isReady = false
        private set
        
    suspend fun initialize(sourceLanguage: String, targetLanguage: String): Boolean {
        isReady = false
        val source = mapLanguage(sourceLanguage)
        val target = mapLanguage(targetLanguage)
        
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(source)
            .setTargetLanguage(target)
            .build()
            
        translator?.close()
        translator = Translation.getClient(options)
        
        val conditions = DownloadConditions.Builder().build()
        return try {
            translator?.downloadModelIfNeeded(conditions)?.await()
            isReady = true
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    suspend fun translateText(text: String): String {
        if (text.isBlank() || !isReady) return ""
        return try {
            translator?.translate(text)?.await() ?: ""
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }
    
    fun close() {
        translator?.close()
    }
    
    private fun mapLanguage(lang: String): String {
        return when (lang.lowercase()) {
            "english" -> TranslateLanguage.ENGLISH
            "spanish" -> TranslateLanguage.SPANISH
            "french" -> TranslateLanguage.FRENCH
            "hindi" -> TranslateLanguage.HINDI
            "german" -> TranslateLanguage.GERMAN
            "chinese" -> TranslateLanguage.CHINESE
            "japanese" -> TranslateLanguage.JAPANESE
            "korean" -> TranslateLanguage.KOREAN
            "italian" -> TranslateLanguage.ITALIAN
            "portuguese" -> TranslateLanguage.PORTUGUESE
            "russian" -> TranslateLanguage.RUSSIAN
            else -> TranslateLanguage.ENGLISH
        }
    }
}
