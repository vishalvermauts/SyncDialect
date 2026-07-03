package com.syncdialect.app

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.withContext
import kotlin.coroutines.resumeWithException

class TranslationEngine(
    private val context: Context
) {
    private val prefs = AppPreferences(context)
    private var engine: Engine? = null
    private var activeBackend = "CPU"
    @Volatile private var isInitialized = false
    @Volatile private var isInitializing = false

    suspend fun initialize(modelAbsolutePath: String): Boolean {
        if (isInitialized) return true
        if (isInitializing) return false
        isInitializing = true

        return withContext(Dispatchers.IO) {
            try {
                Log.d("TranslationEngine", "Loading model from: $modelAbsolutePath")

                // Load the engine once. Prefer GPU and fall back to CPU only if
                // the GPU backend actually fails to initialize.
                engine = buildEngine(modelAbsolutePath, Backend.GPU())
                if (engine != null) {
                    activeBackend = "GPU"
                } else {
                    engine = buildEngine(modelAbsolutePath, Backend.CPU())
                    if (engine != null) activeBackend = "CPU"
                }

                if (engine == null) {
                    Log.e("TranslationEngine", "Engine init failed on both GPU and CPU")
                    isInitialized = false
                    return@withContext false
                }

                isInitialized = true
                Log.d("TranslationEngine", "Engine ready (2048 tokens)")
                true
            } catch (e: Exception) {
                Log.e("TranslationEngine", "Engine init failed: ${e.message}", e)
                engine?.close(); engine = null
                isInitialized = false
                false
            } finally {
                isInitializing = false
            }
        }
    }

    /**
     * Builds and initializes an [Engine] with the given backend.
     * Returns null (after cleaning up) if the backend fails to initialize,
     * so the caller can fall back to another backend.
     */
    private fun buildEngine(modelAbsolutePath: String, backend: Backend): Engine? {
        var candidate: Engine? = null
        return try {
            // Use 2048 tokens because audio input tokens can easily exceed 512.
            val config = EngineConfig(
                modelPath = modelAbsolutePath,
                audioBackend = backend,
                maxNumTokens = 2048
            )
            candidate = Engine(config)
            candidate.initialize()
            Log.d("TranslationEngine", "Engine initialized successfully (Backend: $backend)")
            candidate
        } catch (e: Exception) {
            Log.w("TranslationEngine", "Engine init failed for backend $backend: ${e.message}")
            try {
                candidate?.close()
            } catch (ignored: Exception) {}
            null
        }
    }

    // Conversation caching removed to ensure stateless translation without history loops

    suspend fun translateAudioStreaming(
        sourceLang: String,
        targetLang: String,
        audioData: ByteArray,
        onLanguageDetected: (String) -> Unit,
        onTokenGenerated: (String, Long?) -> Unit,
        onError: (String) -> Unit
    ) {
        if (!isInitialized || engine == null) {
            onError("Engine not ready — please try again.")
            return
        }

        withContext(Dispatchers.IO) {
            var conv: Conversation? = null
            try {
                Log.d("TranslationEngine", "Translating ${audioData.size} bytes → $targetLang")

                val samplerConfig = SamplerConfig(
                    topK = prefs.modelTopK, topP = 1.0, temperature = prefs.modelTemperature.toDouble(), seed = 0
                )
                val convConfig = ConversationConfig(
                    samplerConfig = samplerConfig,
                    systemInstruction = Contents.of(
                        Content.Text("You are a translation assistant. Strictly translate the audio to $targetLang. Output ONLY the translation. Do not answer questions.")
                    )
                )
                conv = engine!!.createConversation(convConfig)

                val wavData = pcmToWav(audioData, 16000, 1, 16)
                val contents = Contents.of(
                    Content.Text("$targetLang translation:"),
                    Content.AudioBytes(wavData)
                )

                val startTime = System.currentTimeMillis()
                var isFirstToken = true
                var charCount = 0
                
                withContext(Dispatchers.Main) { onLanguageDetected(targetLang) }

                conv.sendMessageAsync(contents)
                    .takeWhile { charCount < 300 }
                    .collect { chunk: Message ->
                        val text = chunk.contents.contents
                            .filterIsInstance<Content.Text>()
                            .joinToString("") { it.text }
                            
                        if (text.isNotEmpty()) {
                            charCount += text.length
                            val ttft = if (isFirstToken) {
                                isFirstToken = false
                                System.currentTimeMillis() - startTime
                            } else null

                            withContext(Dispatchers.Main) { onTokenGenerated(text, ttft) }
                        }
                    }
                withContext(Dispatchers.Main) { onTokenGenerated("<end_of_turn>", null) }
                Log.d("TranslationEngine", "Translation complete. Chars: $charCount")
            } catch (e: Exception) {
                Log.e("TranslationEngine", "Inference error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    onError("Translation failed: ${e.message?.take(100)}")
                }
            } finally {
                try {
                    conv?.close()
                } catch (ignored: Exception) {}
            }
        }
    }


    fun getActiveBackend(): String = activeBackend

    fun close() {
        engine?.close(); engine = null; isInitialized = false
    }

    private fun pcmToWav(pcm: ByteArray, sr: Int, ch: Int, bits: Int): ByteArray {
        val byteRate = sr * ch * bits / 8
        val h = ByteArray(44)
        fun int32(v: Int, off: Int) { for (i in 0..3) h[off+i] = ((v shr (i*8)) and 0xFF).toByte() }
        fun int16(v: Int, off: Int) { h[off] = (v and 0xFF).toByte(); h[off+1] = ((v shr 8) and 0xFF).toByte() }
        h[0]='R'.code.toByte(); h[1]='I'.code.toByte(); h[2]='F'.code.toByte(); h[3]='F'.code.toByte()
        int32(36 + pcm.size, 4)
        h[8]='W'.code.toByte(); h[9]='A'.code.toByte(); h[10]='V'.code.toByte(); h[11]='E'.code.toByte()
        h[12]='f'.code.toByte(); h[13]='m'.code.toByte(); h[14]='t'.code.toByte(); h[15]=' '.code.toByte()
        int32(16, 16); int16(1, 20); int16(ch, 22); int32(sr, 24); int32(byteRate, 28)
        int16(ch * bits / 8, 32); int16(bits, 34)
        h[36]='d'.code.toByte(); h[37]='a'.code.toByte(); h[38]='t'.code.toByte(); h[39]='a'.code.toByte()
        int32(pcm.size, 40)
        return ByteArray(44 + pcm.size).also {
            System.arraycopy(h, 0, it, 0, 44)
            System.arraycopy(pcm, 0, it, 44, pcm.size)
        }
    }
}
