package com.syncdialect.app

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

class StreamingTTSManager(
    private val context: Context,
    private val onMissingData: (Intent?) -> Unit
) : TextToSpeech.OnInitListener {

    private val prefs = AppPreferences(context)
    private var tts: TextToSpeech? = null
    private var isReady = false
    private var buffer = StringBuilder()
    // Force-speak the buffer once it grows past this word or char length
    private val maxBufferedWords = 3
    private val maxBufferedChars = 25

    var onSpeakingStateChanged: ((Boolean) -> Unit)? = null
    private val activeUtterances = java.util.Collections.synchronizedSet(HashSet<String>())
    private var utteranceCounter = 0L

    init {
        tts = TextToSpeech(context, this, "com.google.android.tts")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isReady = true
            setupUtteranceListener()
            
            // Set Low Latency Audio Attributes for faster playback
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                tts?.setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setFlags(android.media.AudioAttributes.FLAG_LOW_LATENCY)
                        .build()
                )
            }
            
            // Default to English
            setLanguage("en")
            tts?.setSpeechRate(prefs.ttsSpeechRate)
            Log.d("TTS", "TextToSpeech Initialized")
        } else {
            Log.e("TTS", "Initialization Failed!")
        }
    }

    private fun setupUtteranceListener() {
        tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Log.d("TTS", "onStart: $utteranceId")
                onSpeakingStateChanged?.invoke(true)
            }

            override fun onDone(utteranceId: String?) {
                Log.d("TTS", "onDone: $utteranceId")
                if (utteranceId != null) {
                    activeUtterances.remove(utteranceId)
                }
                checkSpeakingState()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                Log.e("TTS", "onError: $utteranceId")
                if (utteranceId != null) {
                    activeUtterances.remove(utteranceId)
                }
                checkSpeakingState()
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.e("TTS", "onError: $utteranceId, code: $errorCode")
                if (utteranceId != null) {
                    activeUtterances.remove(utteranceId)
                }
                checkSpeakingState()
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                Log.d("TTS", "onStop: $utteranceId, interrupted: $interrupted")
                if (utteranceId != null) {
                    activeUtterances.remove(utteranceId)
                }
                checkSpeakingState()
            }
        })
    }

    private fun checkSpeakingState() {
        if (activeUtterances.isEmpty()) {
            onSpeakingStateChanged?.invoke(false)
        }
    }

    fun isSpeaking(): Boolean {
        return activeUtterances.isNotEmpty()
    }

    var forceOfflineMode: Boolean = false

    private fun selectOptimalVoice(locale: Locale): android.speech.tts.Voice? {
        val voices = tts?.voices ?: return null
        val matchedVoices = voices.filter { it.locale.language == locale.language }
        if (matchedVoices.isEmpty()) return null

        if (forceOfflineMode) {
            val offlineVoice = matchedVoices.find { !it.isNetworkConnectionRequired }
            if (offlineVoice != null) return offlineVoice
            return matchedVoices.first()
        }

        val highQualityVoice = matchedVoices.find {
            it.isNetworkConnectionRequired && it.quality >= android.speech.tts.Voice.QUALITY_HIGH
        }
        if (highQualityVoice != null) return highQualityVoice

        val networkVoice = matchedVoices.find { it.isNetworkConnectionRequired }
        if (networkVoice != null) return networkVoice

        return matchedVoices.first()
    }

    fun setLanguage(languageCode: String) {
        if (!isReady || tts == null) return
        
        val locale = Locale(languageCode)
        val result = tts!!.setLanguage(locale)

        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.e("TTS", "Language data missing or not supported")
            val installIntent = Intent()
            installIntent.action = TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA
            onMissingData(installIntent)
            return
        }

        // Prefer user's saved voice if it matches the locale
        val savedVoiceName = prefs.selectedVoiceName
        if (savedVoiceName != null) {
            val matchingVoice = tts!!.voices?.find { it.name == savedVoiceName }
            if (matchingVoice != null && matchingVoice.locale.language == locale.language) {
                tts!!.voice = matchingVoice
                Log.d("TTS", "Selected saved voice: ${matchingVoice.name}")
                return
            }
        }

        // Otherwise try to find a high-quality network voice for the locale
        try {
            val bestVoice = selectOptimalVoice(locale)
            if (bestVoice != null) {
                tts!!.voice = bestVoice
                Log.d("TTS", "Selected default voice: ${bestVoice.name}")
            }
        } catch (e: Exception) {
            Log.e("TTS", "Failed to select specific voice: ${e.message}")
        }
    }

    fun saveVoice(voiceName: String) {
        prefs.selectedVoiceName = voiceName
        setSpecificVoice(voiceName)
    }

    fun getAvailableVoices(languageCode: String): List<android.speech.tts.Voice> {
        if (!isReady || tts == null) return emptyList()
        val locale = Locale(languageCode)
        return try {
            tts!!.voices?.filter { it.locale.language == locale.language } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun setSpecificVoice(voiceName: String) {
        if (!isReady || tts == null) return
        try {
            val specificVoice = tts!!.voices?.find { it.name == voiceName }
            if (specificVoice != null) {
                tts!!.voice = specificVoice
                Log.d("TTS", "Manually selected voice: ${specificVoice.name}")
            }
        } catch (e: Exception) {
            Log.e("TTS", "Failed to set specific voice: ${e.message}")
        }
    }

    fun setSpeechRate(rate: Float) {
        if (!isReady || tts == null) return
        tts?.setSpeechRate(rate)
    }

    // Process incoming translated tokens
    fun processToken(token: String) {
        if (!isReady) return
        
        buffer.append(token)
        val currentText = buffer.toString()

        // Check for clause boundary
        if (currentText.contains(Regex("[.,!?\\n।。、？！،؛۔]"))) {
            // Find the last boundary index
            var lastBoundary = -1
            val boundaries = charArrayOf('.', ',', '!', '?', '\n', '।', '。', '、', '？', '！', '،', '؛', '۔')
            for (i in currentText.indices.reversed()) {
                if (currentText[i] in boundaries) {
                    lastBoundary = i
                    break
                }
            }

            if (lastBoundary != -1) {
                // Extract the phrase up to the boundary
                val phraseToSpeak = sanitize(currentText.substring(0, lastBoundary + 1))

                // Speak the phrase
                if (phraseToSpeak.isNotEmpty()) {
                    speak(phraseToSpeak)
                }

                // Keep the remainder in the buffer
                val remainder = currentText.substring(lastBoundary + 1)
                buffer.clear()
                buffer.append(remainder)
            }
        } else {
            // Incremental chunking: if we hit 3+ words or 25+ chars (for languages without spaces)
            val words = currentText.split(" ")
            if (words.size > maxBufferedWords || currentText.length >= maxBufferedChars) {
                // Speak at the last word boundary so playback isn't stalled mid-word.
                val lastSpace = currentText.lastIndexOf(' ')
                if (lastSpace > 0) {
                    val phraseToSpeak = sanitize(currentText.substring(0, lastSpace))
                    if (phraseToSpeak.isNotEmpty()) {
                        speak(phraseToSpeak)
                    }
                    val remainder = currentText.substring(lastSpace + 1)
                    buffer.clear()
                    buffer.append(remainder)
                } else if (currentText.length >= maxBufferedChars) {
                    // For languages without spaces, just speak the chunk
                    val phraseToSpeak = sanitize(currentText)
                    if (phraseToSpeak.isNotEmpty()) {
                        speak(phraseToSpeak)
                    }
                    buffer.clear()
                }
            }
        }
    }

    /** Strips Gemma system tags and markdown from a phrase before speaking. */
    private fun sanitize(text: String): String {
        return text.replace(Regex("<[^>]*>"), "")
            .replace("end_of_turn", "")
            .replace("start_of_turn", "")
            .replace("eos", "")
            .replace("turn", "")
            .replace("model", "")
            .replace("user", "")
            .replace("*", "")
            .trim()
    }

    private fun speak(text: String) {
        Log.d("TTS", "Speaking: $text")
        val id = "utt_${System.currentTimeMillis()}_${utteranceCounter++}"
        activeUtterances.add(id)
        onSpeakingStateChanged?.invoke(true)
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, id)
    }

    fun playText(text: String) {
        if (!isReady) return
        val cleanText = sanitize(text)
        if (cleanText.isNotEmpty()) {
            speak(cleanText)
        }
    }

    fun flush() {
        val remainder = sanitize(buffer.toString())
        if (remainder.isNotEmpty()) {
            speak(remainder)
        }
        buffer.clear()
    }

    fun stop() {
        tts?.stop()
        activeUtterances.clear()
        onSpeakingStateChanged?.invoke(false)
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        activeUtterances.clear()
        onSpeakingStateChanged?.invoke(false)
    }
}
