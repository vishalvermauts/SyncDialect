package com.example.auravoice

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

class StreamingTTSManager(
    private val context: Context,
    private val onMissingData: (Intent?) -> Unit
) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isReady = false
    private var buffer = StringBuilder()

    var onSpeakingStateChanged: ((Boolean) -> Unit)? = null
    private val activeUtterances = java.util.Collections.synchronizedSet(HashSet<String>())
    private var utteranceCounter = 0L

    init {
        tts = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isReady = true
            setupUtteranceListener()
            // Default to English
            setLanguage("en")
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

    fun setLanguage(languageCode: String) {
        if (!isReady || tts == null) return
        
        val locale = Locale(languageCode)
        val result = tts!!.setLanguage(locale)

        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.e("TTS", "Language data missing or not supported")
            val installIntent = Intent()
            installIntent.action = TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA
            onMissingData(installIntent)
        }
    }

    // Process incoming translated tokens
    fun processToken(token: String) {
        if (!isReady) return
        
        buffer.append(token)
        val currentText = buffer.toString()

        // Check for clause boundary
        if (currentText.contains(Regex("[.,!?\\n]"))) {
            // Find the last boundary index
            var lastBoundary = -1
            val boundaries = charArrayOf('.', ',', '!', '?', '\n')
            for (i in currentText.indices.reversed()) {
                if (currentText[i] in boundaries) {
                    lastBoundary = i
                    break
                }
            }

            if (lastBoundary != -1) {
                // Extract the phrase up to the boundary
                var phraseToSpeak = currentText.substring(0, lastBoundary + 1).trim()
                
                // Strip out any Gemma system tags like <turn> or <eos> or markdown
                phraseToSpeak = phraseToSpeak.replace(Regex("<[^>]*>"), "")
                    .replace("end_of_turn", "")
                    .replace("start_of_turn", "")
                    .replace("eos", "")
                    .replace("turn", "")
                    .replace("model", "")
                    .replace("user", "")
                    .replace("*", "")
                    .trim()
                
                // Speak the phrase
                if (phraseToSpeak.isNotEmpty()) {
                    speak(phraseToSpeak)
                }

                // Keep the remainder in the buffer
                val remainder = currentText.substring(lastBoundary + 1)
                buffer.clear()
                buffer.append(remainder)
            }
        }
    }

    private fun speak(text: String) {
        Log.d("TTS", "Speaking: $text")
        val id = "utt_${System.currentTimeMillis()}_${utteranceCounter++}"
        activeUtterances.add(id)
        onSpeakingStateChanged?.invoke(true)
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, id)
    }

    fun flush() {
        var remainder = buffer.toString()
        remainder = remainder.replace(Regex("<[^>]*>"), "")
            .replace("end_of_turn", "")
            .replace("start_of_turn", "")
            .replace("eos", "")
            .replace("turn", "")
            .replace("model", "")
            .replace("user", "")
            .replace("*", "")
            .trim()
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
