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
import kotlinx.coroutines.launch
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicReference

class TranslationEngine(
    private val context: Context
) {
    private val prefs = AppPreferences(context)
    
    // Mutex to prevent multiple audio chunks from being translated simultaneously
    private val translationMutex = Mutex()
    
    private var engine: Engine? = null
    private var activeBackend = "CPU"
    
    // Lock-free prewarmed conversation slot. Null means not yet ready.
    // Using AtomicReference to avoid deadlocks with the translationMutex.
    private data class PrewarmedSlot(val conv: Conversation, val lang: String)
    private val prewarmedSlot = AtomicReference<PrewarmedSlot?>(null)
    
    // Lock-free flag: true when a translation is actively running.
    // Used by MainActivity to drop chunks that arrive while busy.
    private val isTranslating = java.util.concurrent.atomic.AtomicBoolean(false)

    // Returns true if we successfully claimed the translation slot (not busy).
    // Returns false if already busy — caller should drop the chunk.
    fun tryStartTranslation(): Boolean = isTranslating.compareAndSet(false, true)

    @Volatile private var isInitialized = false
    @Volatile private var isInitializing = false

    private val engineJob = kotlinx.coroutines.SupervisorJob()
    private val engineScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + engineJob)

    suspend fun initialize(modelAbsolutePath: String, requestedBackend: String? = null): Boolean {
        if (isInitialized) return true
        if (isInitializing) return false
        isInitializing = true

        val startNano = android.os.SystemClock.elapsedRealtimeNanos()
        com.syncdialect.app.benchmark.BenchmarkEventLogger.logEvent(
            eventType = "MODEL_INIT_START",
            details = mapOf(
                "model_path" to modelAbsolutePath,
                "requested_backend" to (requestedBackend ?: "AUTO_GPU_THEN_CPU")
            ),
            timestampNanos = startNano
        )

        return withContext(Dispatchers.IO) {
            try {
                Log.d("TranslationEngine", "Loading model from: $modelAbsolutePath (requested: $requestedBackend)")

                var fallbackOccurred = false
                if (requestedBackend == "CPU") {
                    engine = buildEngine(modelAbsolutePath, Backend.CPU(numOfThreads = 4), Backend.CPU(numOfThreads = 4))
                    if (engine != null) {
                        activeBackend = "CPU"
                    }
                } else {
                    // Try GPU acceleration first for prefill/decode
                    engine = buildEngine(modelAbsolutePath, Backend.GPU(), Backend.CPU(numOfThreads = 4))
                    if (engine != null) {
                        activeBackend = "GPU"
                        Log.i("TranslationEngine", "Initialized with Hybrid Acceleration: GPU (LLM decoder) + CPU (Audio encoder)")
                    } else {
                        fallbackOccurred = true
                        Log.w("TranslationEngine", "GPU initialization failed or unsupported, falling back to CPU...")
                        engine = buildEngine(modelAbsolutePath, Backend.CPU(numOfThreads = 4), Backend.CPU(numOfThreads = 4))
                        if (engine != null) {
                            activeBackend = "CPU"
                            Log.i("TranslationEngine", "Initialized with CPU fallback (4 threads)")
                        }
                    }
                }

                if (engine == null) {
                    Log.e("TranslationEngine", "Engine initialization failed on requested backend")
                    isInitialized = false
                    val failNano = android.os.SystemClock.elapsedRealtimeNanos()
                    com.syncdialect.app.benchmark.BenchmarkEventLogger.logEvent(
                        eventType = "MODEL_INIT_FAILED",
                        details = mapOf("requested_backend" to (requestedBackend ?: "AUTO")),
                        timestampNanos = failNano
                    )
                    return@withContext false
                }

                isInitialized = true
                val readyNano = android.os.SystemClock.elapsedRealtimeNanos()
                com.syncdialect.app.benchmark.BenchmarkEventLogger.logEvent(
                    eventType = "MODEL_INIT_READY",
                    details = mapOf(
                        "active_backend" to activeBackend,
                        "fallback_occurred" to fallbackOccurred,
                        "init_duration_ms" to ((readyNano - startNano) / 1_000_000.0)
                    ),
                    timestampNanos = readyNano
                )
                Log.d("TranslationEngine", "Engine ready (2048 tokens on $activeBackend)")
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
     * Builds and initializes an [Engine] with the given main and audio backends.
     * Returns null (after cleaning up) if initialization fails,
     * so the caller can fall back to another backend.
     */
    private fun buildEngine(modelAbsolutePath: String, mainBackend: Backend, audioBackend: Backend): Engine? {
        var candidate: Engine? = null
        return try {
            val config = EngineConfig(
                modelPath = modelAbsolutePath,
                backend = mainBackend,
                audioBackend = audioBackend,
                maxNumTokens = 2048,
                cacheDir = context.cacheDir.absolutePath
            )
            candidate = Engine(config)
            candidate.initialize()
            Log.d("TranslationEngine", "Engine initialized successfully (Main: $mainBackend, Audio: $audioBackend)")
            candidate
        } catch (e: Exception) {
            Log.w("TranslationEngine", "Engine init failed for mainBackend $mainBackend: ${e.message}")
            try {
                candidate?.close()
            } catch (ignored: Exception) {}
            null
        }
    }

    fun prewarmConversation(targetLang: String) {
        // Prewarming is kept no-op to ensure 100% thread-safe stateless conversation creation per chunk
    }

    // Called by MainActivity when tryStartTranslation() returned true.
    // Runs the translation and always clears the isTranslating flag when done.
    suspend fun translateAudioStreamingExclusive(
        sourceLang: String,
        targetLang: String,
        audioData: ByteArray,
        onLanguageDetected: (String) -> Unit,
        onTokenGenerated: (String, Long?) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit,
        attribution: com.syncdialect.app.benchmark.TurnAttribution? = null
    ) {
        try {
            translateAudioStreaming(sourceLang, targetLang, audioData, onLanguageDetected, onTokenGenerated, onComplete, onError, attribution)
        } finally {
            isTranslating.set(false)
        }
    }

    suspend fun translateAudioStreaming(
        sourceLang: String,
        targetLang: String,
        audioData: ByteArray,
        onLanguageDetected: (String) -> Unit,
        onTokenGenerated: (String, Long?) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit,
        attribution: com.syncdialect.app.benchmark.TurnAttribution? = null
    ) {
        if (!isInitialized || engine == null) {
            onError("Engine not ready — please try again.")
            return
        }

        translationMutex.withLock {
            withContext(Dispatchers.IO) {
                val currentEngine = engine
                if (currentEngine == null) {
                    withContext(Dispatchers.Main) { onError("Engine not initialized") }
                    return@withContext
                }

                val samplerConfig = SamplerConfig(
                    topK = prefs.modelTopK,
                    topP = 1.0,
                    temperature = prefs.modelTemperature.toDouble(),
                    seed = 0
                )
                val convConfig = ConversationConfig(
                    samplerConfig = samplerConfig
                )

                val conv: Conversation
                try {
                    conv = currentEngine.createConversation(convConfig)
                } catch (e: Exception) {
                    Log.e("TranslationEngine", "Failed to create conversation: ${e.message}", e)
                    withContext(Dispatchers.Main) { onError("Failed to start translation session: ${e.message}") }
                    return@withContext
                }

                try {
                    Log.d("TranslationEngine", "Translating ${audioData.size} bytes from $sourceLang → $targetLang")

                    val wavData = pcmToWav(audioData, 16000, 1, 16)
                    val contents = Contents.of(
                        Content.Text("Translate this $sourceLang speech to $targetLang text:"),
                        Content.AudioBytes(wavData)
                    )

                    val startTime = System.currentTimeMillis()
                    val infStartNano = android.os.SystemClock.elapsedRealtimeNanos()
                    com.syncdialect.app.benchmark.BenchmarkEventLogger.logEvent(
                        eventType = "INFERENCE_EXECUTION_START",
                        details = mapOf(
                            "source_lang" to sourceLang,
                            "target_lang" to targetLang,
                            "audio_bytes" to audioData.size,
                            "backend" to activeBackend,
                            "audio_encoder_duration_ns" to null,
                            "prefill_duration_ns" to null
                        ),
                        timestampNanos = infStartNano,
                        attribution = attribution
                    )

                    var isFirstToken = true
                    var charCount = 0
                    var tokenCount = 0
                    
                    withContext(Dispatchers.Main) { onLanguageDetected(sourceLang) }

                    conv.sendMessageAsync(contents)
                        .takeWhile { charCount < 1200 }
                        .collect { chunk: Message ->
                            tokenCount++
                            val text = chunk.contents.contents
                                .filterIsInstance<Content.Text>()
                                .joinToString("") { it.text }
                                
                            if (text.isNotEmpty()) {
                                charCount += text.length
                                val tokenNano = android.os.SystemClock.elapsedRealtimeNanos()
                                val ttft = if (isFirstToken) {
                                    isFirstToken = false
                                    com.syncdialect.app.benchmark.BenchmarkEventLogger.logEvent(
                                        eventType = "FIRST_RAW_MODEL_OUTPUT",
                                        details = mapOf(
                                            "raw_text_length" to text.length,
                                            "raw_text_preview" to text.take(30),
                                            "token_index" to tokenCount
                                        ),
                                        timestampNanos = tokenNano,
                                        attribution = attribution
                                    )
                                    System.currentTimeMillis() - startTime
                                } else null

                                withContext(Dispatchers.Main) { onTokenGenerated(text, ttft) }
                            }
                        }

                    val infCompleteNano = android.os.SystemClock.elapsedRealtimeNanos()
                    val wasTruncated = charCount >= 1200
                    val durationMs = (infCompleteNano - infStartNano) / 1_000_000.0
                    val tokensPerSec = if (durationMs > 0) (tokenCount * 1000.0) / durationMs else 0.0
                    if (wasTruncated) {
                        com.syncdialect.app.benchmark.BenchmarkEventLogger.logEvent(
                            eventType = "TRUNCATION_LIMIT_REACHED",
                            details = mapOf("char_limit" to 1200),
                            attribution = attribution
                        )
                    }
                    com.syncdialect.app.benchmark.BenchmarkEventLogger.logEvent(
                        eventType = "INFERENCE_COMPLETION",
                        details = mapOf(
                            "char_count" to charCount,
                            "token_count" to tokenCount,
                            "duration_ms" to durationMs,
                            "tokens_per_sec" to tokensPerSec,
                            "truncated" to wasTruncated
                        ),
                        timestampNanos = infCompleteNano,
                        attribution = attribution
                    )

                    Log.d("TranslationEngine", "Translation complete. Chars: $charCount")
                    withContext(Dispatchers.Main) { onComplete() }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) {
                        Log.i("TranslationEngine", "Inference coroutine cancelled.")
                        throw e
                    }
                    val infErrNano = android.os.SystemClock.elapsedRealtimeNanos()
                    com.syncdialect.app.benchmark.BenchmarkEventLogger.logEvent(
                        eventType = "INFERENCE_ERROR",
                        details = mapOf("error" to (e.message ?: "Unknown inference error")),
                        timestampNanos = infErrNano,
                        attribution = attribution
                    )
                    Log.e("TranslationEngine", "Inference error: ${e.message}", e)
                    withContext(Dispatchers.Main) {
                        onError("Translation failed: ${e.message?.take(100)}")
                    }
                } finally {
                    try {
                        conv.close()
                    } catch (ignored: Exception) {}
                }
            }
        }
    }

    fun getActiveBackend(): String = activeBackend

    fun close() {
        try { engineJob.cancel() } catch (ignored: Exception) {}
        val old = prewarmedSlot.getAndSet(null)
        old?.conv?.close()
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
