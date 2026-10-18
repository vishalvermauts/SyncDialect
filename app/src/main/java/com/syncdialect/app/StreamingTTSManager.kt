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
    private val maxBufferedWords = 8
    private val maxBufferedChars = 40

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

    private var currentLanguageTag: String? = null

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val watchdogRunnable = Runnable {
        val stillSpeaking = try { tts?.isSpeaking == true } catch (e: Exception) { false }
        if (!stillSpeaking || activeUtterances.isNotEmpty()) {
            Log.w("TTS", "Watchdog triggered: active=${activeUtterances.size}, hardwareSpeaking=$stillSpeaking. Forcing unmute.")
            activeUtterances.clear()
            checkSpeakingState()
        }
    }

    private fun setupUtteranceListener() {
        tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Log.d("TTS", "onStart: $utteranceId")
                handler.removeCallbacks(watchdogRunnable)
                handler.postDelayed(watchdogRunnable, 5000) // 5 second safety watchdog
                handler.post { onSpeakingStateChanged?.invoke(true) }
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
        val hardwareSpeaking = try { tts?.isSpeaking == true } catch (e: Exception) { false }
        if (activeUtterances.isEmpty() || !hardwareSpeaking) {
            if (!hardwareSpeaking && activeUtterances.isNotEmpty()) {
                activeUtterances.clear()
            }
            handler.removeCallbacks(watchdogRunnable)
            handler.post {
                onSpeakingStateChanged?.invoke(false)
            }
        }
    }

    fun isSpeaking(): Boolean {
        return activeUtterances.isNotEmpty() || (tts?.isSpeaking == true)
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
        if (languageCode == currentLanguageTag) return // Avoid resetting active TTS voice & audio pipelines
        currentLanguageTag = languageCode
        
        val locale = Locale(languageCode)
        val result = tts!!.setLanguage(locale)

        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.e("TTS", "Language data missing or not supported for ${locale.language}")
            val installIntent = Intent()
            installIntent.action = TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA
            onMissingData(installIntent)
            return
        }

        // Prefer user's saved voice if it matches the locale and obeys offline constraint
        val savedVoiceName = prefs.selectedVoiceName
        if (savedVoiceName != null) {
            val matchingVoice = tts!!.voices?.find { it.name == savedVoiceName }
            if (matchingVoice != null && matchingVoice.locale.language == locale.language) {
                if (!forceOfflineMode || !matchingVoice.isNetworkConnectionRequired) {
                    tts!!.voice = matchingVoice
                    Log.d("TTS", "Selected saved voice: ${matchingVoice.name}")
                    return
                }
            }
        }

        // Otherwise select optimal voice adhering to offline constraint
        try {
            val bestVoice = selectOptimalVoice(locale)
            if (bestVoice != null) {
                if (forceOfflineMode && bestVoice.isNetworkConnectionRequired) {
                    Log.w("TTS", "No offline voice installed for ${locale.language}")
                    val installIntent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
                    onMissingData(installIntent)
                    return
                }
                tts!!.voice = bestVoice
                prefs.selectedVoiceName = bestVoice.name
                Log.d("TTS", "Selected voice: ${bestVoice.name} (offlineOnly=$forceOfflineMode)")
            } else if (forceOfflineMode) {
                Log.w("TTS", "No voice found for ${locale.language}")
                val installIntent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
                onMissingData(installIntent)
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
            val all = tts!!.voices?.filter { it.locale.language == locale.language } ?: emptyList()
            if (forceOfflineMode) all.filter { !it.isNetworkConnectionRequired } else all
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun setSpecificVoice(voiceName: String) {
        if (!isReady || tts == null) return
        try {
            val specificVoice = tts!!.voices?.find { it.name == voiceName }
            if (specificVoice != null) {
                if (forceOfflineMode && specificVoice.isNetworkConnectionRequired) {
                    Log.w("TTS", "Requested voice $voiceName requires network, ignoring in offline mode")
                    return
                }
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

    private var isFirstPhraseInTurn = true

    fun onTurnStarted() {
        isFirstPhraseInTurn = true
        buffer.clear()
        val hardwareSpeaking = try { tts?.isSpeaking == true } catch (e: Exception) { false }
        if (!hardwareSpeaking) {
            activeUtterances.clear()
            handler.removeCallbacks(watchdogRunnable)
            handler.post { onSpeakingStateChanged?.invoke(false) }
        }
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
                    isFirstPhraseInTurn = false
                }

                // Keep the remainder in the buffer
                val remainder = currentText.substring(lastBoundary + 1)
                buffer.clear()
                buffer.append(remainder)
            }
        } else {
            // Adaptive early dispatch: First phrase triggers early (3 words) for low TTFS; later phrases buffer up to 8 words for prosody
            val targetWordCount = if (isFirstPhraseInTurn) 3 else maxBufferedWords
            val targetCharCount = if (isFirstPhraseInTurn) 20 else maxBufferedChars

            val words = currentText.trim().split(Regex("\\s+"))
            if (words.size >= targetWordCount || currentText.length >= targetCharCount) {
                val lastSpace = currentText.lastIndexOf(' ')
                if (lastSpace > 0) {
                    val phraseToSpeak = sanitize(currentText.substring(0, lastSpace))
                    if (phraseToSpeak.isNotEmpty()) {
                        speak(phraseToSpeak)
                        isFirstPhraseInTurn = false
                    }
                    val remainder = currentText.substring(lastSpace + 1)
                    buffer.clear()
                    buffer.append(remainder)
                } else if (currentText.length >= targetCharCount) {
                    // For languages without spaces (CJK), speak the chunk directly
                    val phraseToSpeak = sanitize(currentText)
                    if (phraseToSpeak.isNotEmpty()) {
                        speak(phraseToSpeak)
                        isFirstPhraseInTurn = false
                    }
                    buffer.clear()
                }
            }
        }
    }

    /**
     * Exact model control-token filtering.
     * Replaces unsafe greedy replacement that damaged real words (e.g. "turn" -> "return").
     */
    private val controlTokensRegex = Regex("<start_of_turn>|<end_of_turn>|<eos>|<bos>|<pad>|<[^>]+>|\\b(start_of_turn|end_of_turn)\\b")

    private fun sanitize(text: String): String {
        return text.replace(controlTokensRegex, "")
            .replace("*", "")
            .trim()
    }

    private fun speak(text: String) {
        val clean = sanitize(text)
        if (clean.isBlank()) return
        // Do not log raw user speech/text in production logs for privacy
        val id = "utt_${System.currentTimeMillis()}_${utteranceCounter++}"
        Log.d("TTS", "Dispatching utterance $id (length=${clean.length})")
        activeUtterances.add(id)
        handler.removeCallbacks(watchdogRunnable)
        handler.postDelayed(watchdogRunnable, 5000)
        handler.post { onSpeakingStateChanged?.invoke(true) }
        val result = tts?.speak(clean, TextToSpeech.QUEUE_ADD, null, id)
        if (result != TextToSpeech.SUCCESS) {
            Log.e("TTS", "tts.speak returned error code $result for id $id")
            activeUtterances.remove(id)
            checkSpeakingState()
        }
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
        isFirstPhraseInTurn = true
    }

    fun stop() {
        tts?.stop()
        buffer.clear()
        activeUtterances.clear()
        handler.removeCallbacks(watchdogRunnable)
        isFirstPhraseInTurn = true
        onSpeakingStateChanged?.invoke(false)
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        activeUtterances.clear()
        onSpeakingStateChanged?.invoke(false)
    }
}
