package com.syncdialect.app

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.animation.core.*
import androidx.compose.ui.draw.rotate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import kotlin.random.Random

data class ChatMessage(
    val text: String,
    val language: String,
    val isUser: Boolean = false,
    val id: String = java.util.UUID.randomUUID().toString()
)

class MainActivity : ComponentActivity() {

    private lateinit var audioRecorderHelper: AudioRecorderHelper
    private lateinit var translationEngine: TranslationEngine
    private lateinit var ttsEngine: StreamingTTSManager
    private lateinit var downloadManager: ModelDownloadManager

    private val messages = mutableStateListOf<ChatMessage>()
    private var isRecording = mutableStateOf(false)
    private var sourceLanguage = mutableStateOf("English")
    private var targetLanguage = mutableStateOf("Spanish")
    private var targetLanguageTag = mutableStateOf("es") // needed for TTS
    private var isModelReady = mutableStateOf(false)
    private var isModelLoading = mutableStateOf(false)
    private var isDownloading = mutableStateOf(false)
    private var downloadProgress = mutableStateOf(0f)
    private var forceOfflineTts = mutableStateOf(true)
    data class QueuedTurn(
        val turnId: String,
        val audioData: ByteArray,
        val sourceLang: String,
        val targetLang: String,
        val targetTag: String,
        val enqueuedNano: Long = android.os.SystemClock.elapsedRealtimeNanos(),
        val generationId: Long = 0L,
        val attribution: com.syncdialect.app.benchmark.TurnAttribution? = null
    )

    private val maxQueueCapacity = 4
    private val translationQueue = kotlinx.coroutines.channels.Channel<QueuedTurn>(maxQueueCapacity)
    private var queueWorkerJob: kotlinx.coroutines.Job? = null
    private var activeTurnJob: kotlinx.coroutines.Job? = null
    private val activeGenerationId = java.util.concurrent.atomic.AtomicLong(1L)

    private var currentScreen by mutableStateOf("Home")

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean -> }
    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        splashScreen.setKeepOnScreenCondition { false }

        val launchNano = android.os.SystemClock.elapsedRealtimeNanos()
        com.syncdialect.app.benchmark.BenchmarkEventLogger.logEvent(
            eventType = "APP_LAUNCH",
            details = mapOf("activity" to "MainActivity"),
            timestampNanos = launchNano
        )

        val appPrefs = AppPreferences(this)
        forceOfflineTts.value = appPrefs.forceOfflineTts

        audioRecorderHelper = AudioRecorderHelper(appPrefs)
        translationEngine = TranslationEngine(this)
        ttsEngine = StreamingTTSManager(this) { _ ->
            Log.d("MainActivity", "TTS notice: using available system or network voice")
        }
        ttsEngine.forceOfflineMode = forceOfflineTts.value
        ttsEngine.onSpeakingStateChanged = { isSpeaking ->
            audioRecorderHelper.isMuted = isSpeaking
        }
        downloadManager = ModelDownloadManager(this)

        val prefs = getSharedPreferences("SyncDialectPrefs", android.content.Context.MODE_PRIVATE)
        val isOnboardingComplete = prefs.getBoolean("isOnboardingComplete", false)
        currentScreen = if (isOnboardingComplete) "Home" else "Onboarding"
        sourceLanguage.value = prefs.getString("sourceLanguage", "English") ?: "English"
        targetLanguage.value = prefs.getString("targetLanguage", "Spanish") ?: "Spanish"
        targetLanguageTag.value = prefs.getString("targetLanguageTag", "es") ?: "es"

        initializeModelEngine()
        startQueueWorker()

        setContent {
            MaterialTheme {
                androidx.compose.runtime.LaunchedEffect(forceOfflineTts.value) {
                    appPrefs.forceOfflineTts = forceOfflineTts.value
                    ttsEngine.forceOfflineMode = forceOfflineTts.value
                    ttsEngine.setLanguage(targetLanguageTag.value)
                }
                SyncDialectApp()
            }
        }
    }

    private fun startQueueWorker() {
        if (queueWorkerJob?.isActive == true) return
        queueWorkerJob = lifecycleScope.launch(Dispatchers.IO) {
            for (turn in translationQueue) {
                processQueuedTurn(turn)
            }
        }
    }

    private suspend fun processQueuedTurn(turn: QueuedTurn) {
        val attr = turn.attribution
        val currentGen = activeGenerationId.get()
        if (turn.generationId != currentGen) {
            val staleNano = android.os.SystemClock.elapsedRealtimeNanos()
            com.syncdialect.app.benchmark.BenchmarkEventLogger.logEvent(
                eventType = "STALE_TURN_SUPPRESSED",
                details = mapOf(
                    "turn_id" to turn.turnId,
                    "turn_generation_id" to turn.generationId,
                    "active_generation_id" to currentGen
                ),
                timestampNanos = staleNano,
                turnIdOverride = turn.turnId,
                attribution = attr
            )
            android.util.Log.i("MainActivity", "Suppressed stale queued turn ${turn.turnId} (gen ${turn.generationId} != active $currentGen)")
            return
        }

        val dequeueNano = android.os.SystemClock.elapsedRealtimeNanos()
        val waitMs = (dequeueNano - turn.enqueuedNano) / 1_000_000.0
        com.syncdialect.app.benchmark.BenchmarkEventLogger.logEvent(
            eventType = "QUEUE_DEQUEUE",
            details = mapOf(
                "turn_id" to turn.turnId,
                "chunk_bytes" to turn.audioData.size,
                "queue_wait_ms" to waitMs,
                "generation_id" to turn.generationId
            ),
            timestampNanos = dequeueNano,
            turnIdOverride = turn.turnId,
            attribution = attr
        )
        val reqSubmitNano = android.os.SystemClock.elapsedRealtimeNanos()
        com.syncdialect.app.benchmark.BenchmarkEventLogger.logEvent(
            eventType = "INFERENCE_REQUEST_SUBMITTED",
            details = mapOf("turn_id" to turn.turnId, "chunk_bytes" to turn.audioData.size),
            timestampNanos = reqSubmitNano,
            turnIdOverride = turn.turnId,
            attribution = attr
        )
        val msgId = turn.turnId
        ttsEngine.setLanguage(turn.targetTag)
        ttsEngine.onTurnStarted(attribution = attr)
        var isFirstValidText = true

        try {
            kotlinx.coroutines.coroutineScope {
                activeTurnJob = coroutineContext[kotlinx.coroutines.Job]
                translationEngine.translateAudioStreamingExclusive(
                    sourceLang = turn.sourceLang,
                    targetLang = turn.targetLang,
                    audioData = turn.audioData,
                    onLanguageDetected = { _ -> },
                    onTokenGenerated = { token, latencyMs ->
                        if (activeGenerationId.get() != turn.generationId) {
                            return@translateAudioStreamingExclusive
                        }

                        val isEnd = token.contains("<end_of_turn>") || token.contains("end_of_turn") || token.contains("eos") || token.contains("<turn|>") || token.contains("<|turn>")
                        val clean = token.replace("<end_of_turn>", "")
                            .replace("<turn|>", "")
                            .replace("<|turn>", "")
                            .replace("<|channel>", "")
                            .replace("<channel|>", "")
                            .replace("\n", "")
                            .replace(Regex("[\\p{So}\\p{Cn}]"), "") // Strip non-text Unicode pictographs/emojis
                        if (clean.isNotEmpty() && !clean.contains("[SILENCE]")) {
                            if (isFirstValidText) {
                                isFirstValidText = false
                                val firstValidNano = android.os.SystemClock.elapsedRealtimeNanos()
                                com.syncdialect.app.benchmark.BenchmarkEventLogger.logEvent(
                                    eventType = "FIRST_VALID_TRANSLATED_TEXT",
                                    details = mapOf(
                                        "token_preview" to clean.take(30),
                                        "clean_length" to clean.length
                                    ),
                                    timestampNanos = firstValidNano,
                                    turnIdOverride = turn.turnId,
                                    attribution = attr
                                )
                            }
                            runOnUiThread {
                                val idx = messages.indexOfFirst { it.id == msgId }
                                if (idx == -1) {
                                    messages.add(ChatMessage(clean, turn.targetLang, false, msgId))
                                } else {
                                    messages[idx] = messages[idx].copy(text = messages[idx].text + clean)
                                }
                            }
                            ttsEngine.processToken(clean, attribution = attr)
                        }
                        if (isEnd) {
                            ttsEngine.flush(attribution = attr)
                        }
                    },
                    onComplete = {
                        if (activeGenerationId.get() == turn.generationId) {
                            ttsEngine.flush(attribution = attr)
                        }
                    },
                    onError = { err ->
                        if (activeGenerationId.get() == turn.generationId) {
                            runOnUiThread {
                                isRecording.value = false
                                audioRecorderHelper.stopRecording()
                                cancelAndClearQueue("inference_error")
                                messages.add(ChatMessage("Error: $err", "System"))
                            }
                        }
                    },
                    attribution = attr
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            val cancelNano = android.os.SystemClock.elapsedRealtimeNanos()
            com.syncdialect.app.benchmark.BenchmarkEventLogger.logEvent(
                eventType = "TURN_EXECUTION_CANCELLED",
                details = mapOf("turn_id" to turn.turnId, "generation_id" to turn.generationId),
                timestampNanos = cancelNano,
                turnIdOverride = turn.turnId,
                attribution = attr
            )
            android.util.Log.i("MainActivity", "Turn ${turn.turnId} was cancelled mid-execution.")
        } finally {
            activeTurnJob = null
        }
    }

    fun submitTurnForProcessing(
        audioData: ByteArray,
        turnId: String = "turn_${System.currentTimeMillis()}",
        sourceLang: String = sourceLanguage.value,
        targetLang: String = targetLanguage.value,
        targetTag: String = targetLanguageTag.value,
        segmentIdOverride: String? = null,
        attribution: com.syncdialect.app.benchmark.TurnAttribution? = null
    ): Boolean {
        val currentGen = activeGenerationId.get()
        val finalAttribution = attribution ?: if (segmentIdOverride != null) {
            com.syncdialect.app.benchmark.TurnAttribution(
                attemptId = com.syncdialect.app.benchmark.BenchmarkEventLogger.currentAttemptId,
                recordingId = com.syncdialect.app.benchmark.BenchmarkEventLogger.currentRecordingId,
                turnId = turnId,
                segmentId = segmentIdOverride,
                inferenceId = "inf_${System.currentTimeMillis()}"
            )
        } else null

        val queuedTurn = QueuedTurn(
            turnId = turnId,
            audioData = audioData,
            sourceLang = sourceLang,
            targetLang = targetLang,
            targetTag = targetTag,
            enqueuedNano = android.os.SystemClock.elapsedRealtimeNanos(),
            generationId = currentGen,
            attribution = finalAttribution
        )
        val offerResult = translationQueue.trySend(queuedTurn)
        if (offerResult.isSuccess) {
            com.syncdialect.app.benchmark.BenchmarkEventLogger.logEvent(
                eventType = "QUEUE_ENTRY",
                details = mapOf(
                    "turn_id" to turnId,
                    "chunk_bytes" to audioData.size,
                    "queue_capacity" to maxQueueCapacity,
                    "generation_id" to currentGen
                ),
                timestampNanos = queuedTurn.enqueuedNano,
                turnIdOverride = turnId,
                segmentIdOverride = segmentIdOverride,
                attribution = finalAttribution
            )
            return true
        } else {
            val dropNano = android.os.SystemClock.elapsedRealtimeNanos()
            com.syncdialect.app.benchmark.BenchmarkEventLogger.logEvent(
                eventType = "QUEUE_OVERFLOW",
                details = mapOf(
                    "reason" to "queue_capacity_exceeded",
                    "chunk_bytes" to audioData.size,
                    "queue_limit" to maxQueueCapacity,
                    "generation_id" to currentGen
                ),
                timestampNanos = dropNano,
                turnIdOverride = turnId,
                segmentIdOverride = segmentIdOverride,
                attribution = finalAttribution
            )
            com.syncdialect.app.benchmark.BenchmarkEventLogger.logEvent(
                eventType = "DROPPED_AUDIO",
                details = mapOf(
                    "reason" to "queue_overflow",
                    "chunk_bytes" to audioData.size
                ),
                timestampNanos = dropNano,
                turnIdOverride = turnId,
                segmentIdOverride = segmentIdOverride,
                attribution = finalAttribution
            )
            android.util.Log.w("MainActivity", "Translation queue overflow! Dropping audio chunk.")
            return false
        }
    }

    fun cancelAndClearQueue(reason: String) {
        val prevGen = activeGenerationId.get()
        val newGen = activeGenerationId.incrementAndGet()

        // 1. Cancel active in-flight translation job if running
        activeTurnJob?.cancel()
        activeTurnJob = null

        // 2. Stop TTS immediately and clear utterances
        ttsEngine.stop()

        // 3. Purge all pending queued items from the channel
        var dropped = 0
        while (true) {
            val item = translationQueue.tryReceive().getOrNull() ?: break
            dropped++
        }

        val purgeNano = android.os.SystemClock.elapsedRealtimeNanos()
        com.syncdialect.app.benchmark.BenchmarkEventLogger.logEvent(
            eventType = "CANCELLATION_TRIGGERED",
            details = mapOf(
                "reason" to reason,
                "prev_generation_id" to prevGen,
                "new_generation_id" to newGen,
                "dropped_turns" to dropped
            ),
            timestampNanos = purgeNano
        )

        if (dropped > 0) {
            com.syncdialect.app.benchmark.BenchmarkEventLogger.logEvent(
                eventType = "QUEUE_PURGED",
                details = mapOf("dropped_turns" to dropped, "reason" to reason),
                timestampNanos = purgeNano
            )
            android.util.Log.i("MainActivity", "Purged $dropped stale turns from queue due to: $reason (gen: $prevGen -> $newGen)")
        }
    }

    fun initializeModelEngine(onComplete: ((Boolean) -> Unit)? = null) {
        lifecycleScope.launch(Dispatchers.IO) {
            val modelPath = downloadManager.getModelAbsolutePath()
            if (File(modelPath).exists()) {
                isModelLoading.value = true
                val success = translationEngine.initialize(modelPath)
                isModelReady.value = success
                if (success) {
                    translationEngine.prewarmConversation(targetLanguage.value)
                }
                isModelLoading.value = false
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    onComplete?.invoke(success)
                }
            } else {
                isModelReady.value = false
                isModelLoading.value = false
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    onComplete?.invoke(false)
                }
            }
        }
    }

    private val BgTop = Color(0xFF050507)
    private val BgBottom = Color(0xFF14141C)
    private val PillBg = Color.White.copy(alpha = 0.08f)
    private val PillBorder = Color.White.copy(alpha = 0.16f)
    private val TextPrimary = Color.White
    private val TextSecondary = Color(0xFFA0A0A8)
    private val AccentCoral = Color(0xFFE8593C)
    private val AccentCoralDeep = Color(0xFFC2402A)
    private val NavInactive = Color(0xFF8A8A92)

    private fun getFlagForTag(tag: String): String {
        return when (tag) {
            "en" -> "🇺🇸"
            "es" -> "🇪🇸"
            "fr" -> "🇫🇷"
            "de" -> "🇩🇪"
            "hi" -> "🇮🇳"
            "ja" -> "🇯🇵"
            "ko" -> "🇰🇷"
            "zh" -> "🇨🇳"
            "it" -> "🇮🇹"
            "pt" -> "🇵🇹"
            "ru" -> "🇷🇺"
            "tr" -> "🇹🇷"
            "ar" -> "🇸🇦"
            "nl" -> "🇳🇱"
            "vi" -> "🇻🇳"
            "th" -> "🇹🇭"
            else -> "🌐"
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun SyncDialectApp() {
        val context = androidx.compose.ui.platform.LocalContext.current
        val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            if (!isGranted) {
                messages.add(ChatMessage("Microphone permission denied. Please grant it in Settings.", "System", false))
                isRecording.value = false
            }
        }
        var showLangSheet by remember { mutableStateOf(false) }
        var editingLangType by remember { mutableStateOf("source") } 
        
        // Pair of <Display Name, Tag>
        var downloadedLanguages by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
        val allLanguagesList = remember { 
            com.google.mlkit.nl.translate.TranslateLanguage.getAllLanguages().map { tag ->
                Pair(Locale(tag).displayLanguage.replaceFirstChar { c -> c.uppercase() }, tag)
            }.sortedBy { it.first }
        }
        
        val baseLanguages = remember { listOf(Pair("English", "en"), Pair("Spanish", "es")) }
        val refreshDownloadedLanguages: () -> Unit = {
            RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel::class.java)
                .addOnSuccessListener { models ->
                    val dynamicList = models.map { 
                        Pair(Locale(it.language).displayLanguage.replaceFirstChar { c -> c.uppercase() }, it.language)
                    }
                    downloadedLanguages = (baseLanguages + dynamicList).distinctBy { it.second }.sortedBy { it.first }
                }
                .addOnFailureListener {
                    downloadedLanguages = baseLanguages
                }
        }
        
        LaunchedEffect(currentScreen) {
            refreshDownloadedLanguages()
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(BgTop, BgBottom)))
        ) {
            BackgroundWaveform(modifier = Modifier.fillMaxSize())

            if (currentScreen == "Onboarding") {
                val prefs = androidx.compose.ui.platform.LocalContext.current.getSharedPreferences("SyncDialectPrefs", android.content.Context.MODE_PRIVATE)
                OnboardingScreen(
                    downloadedLanguages = allLanguagesList,
                    onComplete = { srcName, tgtName, tgtTag ->
                        sourceLanguage.value = srcName
                        targetLanguage.value = tgtName
                        targetLanguageTag.value = tgtTag
                        ttsEngine.setLanguage(tgtTag)
                        
                        val srcTag = allLanguagesList.find { it.first == srcName }?.second ?: "en"
                        val modelManager = RemoteModelManager.getInstance()
                        val conditions = com.google.mlkit.common.model.DownloadConditions.Builder().build()
                        modelManager.download(TranslateRemoteModel.Builder(srcTag).build(), conditions)
                        modelManager.download(TranslateRemoteModel.Builder(tgtTag).build(), conditions)
                        
                        prefs.edit().apply {
                            putBoolean("isOnboardingComplete", true)
                            putString("sourceLanguage", srcName)
                            putString("targetLanguage", tgtName)
                            putString("targetLanguageTag", tgtTag)
                            apply()
                        }
                        currentScreen = "Home"
                    }
                )
            } else if (currentScreen == "Home") {
                Column(modifier = Modifier.fillMaxSize()) {
                    TopBar()
                    Spacer(Modifier.height(20.dp))
                    LanguageSelectorBar(
                        sourceLabel = sourceLanguage.value.uppercase(),
                        sourceFlag = getFlagForTag(allLanguagesList.find { it.first == sourceLanguage.value }?.second ?: "en"),
                        targetLabel = targetLanguage.value.uppercase(),
                        targetFlag = getFlagForTag(targetLanguageTag.value),
                        onSourceClick = { editingLangType = "source"; showLangSheet = true },
                        onTargetClick = { editingLangType = "target"; showLangSheet = true },
                        onSwap = { 
                            cancelAndClearQueue("language_swap")
                            val tempName = sourceLanguage.value
                            sourceLanguage.value = targetLanguage.value
                            targetLanguage.value = tempName
                            
                            // Swap TTS target
                            val tempTag = targetLanguageTag.value
                            targetLanguageTag.value = allLanguagesList.find { it.first == targetLanguage.value }?.second ?: "en"
                            ttsEngine.setLanguage(targetLanguageTag.value)
                            
                            val prefs = getSharedPreferences("SyncDialectPrefs", android.content.Context.MODE_PRIVATE)
                            prefs.edit().apply {
                                putString("sourceLanguage", sourceLanguage.value)
                                putString("targetLanguage", targetLanguage.value)
                                putString("targetLanguageTag", targetLanguageTag.value)
                                apply()
                            }
                            
                            // Prewarm for new target language
                            translationEngine.prewarmConversation(targetLanguage.value)
                        },
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .fillMaxWidth()
                    )
                    Spacer(Modifier.height(28.dp))
                    
                    val listState = rememberLazyListState()
                    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
                        if (messages.isNotEmpty()) {
                            listState.animateScrollToItem(messages.size - 1)
                        }
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        item { Spacer(Modifier.height(4.dp)) }
                        items(messages) { msg ->
                            TranscriptRow(
                                speakerLabel = if (msg.language == sourceLanguage.value || msg.isUser) "You (${msg.language}):" else if (msg.language == "System") "System:" else "Speaker (${msg.language}):",
                                message = if (msg.text.isEmpty()) "..." else msg.text,
                                waveformOnStart = msg.language == sourceLanguage.value || msg.isUser,
                                isSystem = msg.language == "System"
                            )
                        }
                        item { Spacer(Modifier.height(140.dp)) } 
                    }
                }
                
                BottomControls(
                    isRecording = isRecording.value,
                    isLoading = isModelLoading.value,
                    onMicClick = {
                        if (isRecording.value) {
                            isRecording.value = false
                            audioRecorderHelper.stopRecording()
                            cancelAndClearQueue("user_mic_stop")
                        } else {
                            if (!isModelReady.value) {
                                messages.add(ChatMessage("Gemma Model is missing. Please go to Settings to download the 2.2GB offline engine.", "System", false))
                                return@BottomControls
                            }
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                return@BottomControls
                            }
                            
                            isRecording.value = true
                            audioRecorderHelper.startContinuousRecording { audioData ->
                                val turnId = "turn_${System.currentTimeMillis()}"
                                submitTurnForProcessing(audioData, turnId)
                            }
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                )
            } else if (currentScreen == "Settings") {
                Column(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 48.dp, bottom = 8.dp, start = 20.dp, end = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "SyncDialect",
                            color = TextPrimary,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Settings",
                            color = TextSecondary,
                            fontSize = 14.sp
                        )
                    }
                    SettingsScreen(
                        isModelReady = isModelReady,
                        isDownloading = isDownloading,
                        downloadProgress = downloadProgress,
                        targetLanguage = targetLanguage,
                        targetLanguageTag = targetLanguageTag,
                        forceOfflineTts = forceOfflineTts,
                        ttsEngine = ttsEngine,
                        downloadManager = downloadManager,
                        mainActivityContext = this@MainActivity,
                        onModelDownloaded = { initializeModelEngine() },
                        onLanguagePacksUpdated = refreshDownloadedLanguages
                    )
                }
                NavBar(modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
            }

            if (showLangSheet) {
                ModalBottomSheet(onDismissRequest = { showLangSheet = false }, containerColor = Color(0xFF1E1E1E)) {
                    LazyColumn(modifier = Modifier.padding(24.dp)) {
                        item {
                            val titleType = if (editingLangType == "source") "Source" else "Target"
                            Text("Select $titleType Language", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            Text("Only downloaded languages are shown. Manage them in Settings.", color = Color.Gray, fontSize = 12.sp)
                            Spacer(Modifier.height(16.dp))
                        }
                        items(downloadedLanguages) { langPair ->
                            val flag = getFlagForTag(langPair.second)
                            Text(
                                text = "$flag  ${langPair.first}",
                                color = Color.White,
                                fontSize = 18.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (editingLangType == "source") {
                                            cancelAndClearQueue("source_language_change")
                                            sourceLanguage.value = langPair.first
                                        } else {
                                            cancelAndClearQueue("target_language_change")
                                            targetLanguage.value = langPair.first
                                            targetLanguageTag.value = langPair.second
                                            ttsEngine.setLanguage(langPair.second)
                                            translationEngine.prewarmConversation(targetLanguage.value)
                                        }
                                        showLangSheet = false
                                    }
                                    .padding(vertical = 16.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun TopBar() {
        val clipboardManager = LocalClipboardManager.current
        Column(modifier = Modifier.fillMaxWidth().padding(top = 48.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center
            ) {
                // High Quality Text instead of Logo
                Text(
                    text = "SyncDialect",
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.5.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.SansSerif
                )
                // Action buttons on the right
                Row(modifier = Modifier.align(Alignment.CenterEnd)) {
                    IconButton(onClick = {
                        val allText = messages.joinToString("\n") { 
                            if (it.isUser) "[You]: ${it.text}" else "[Translated]: ${it.text}" 
                        }
                        clipboardManager.setText(AnnotatedString(allText))
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy text", tint = TextSecondary)
                    }
                    IconButton(onClick = {
                        messages.clear()
                    }) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = "Clear screen", tint = TextSecondary)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Live Translation Active",
                color = TextSecondary,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    @Composable
    private fun LanguageSelectorBar(
        sourceLabel: String,
        sourceFlag: String,
        targetLabel: String,
        targetFlag: String,
        onSourceClick: () -> Unit,
        onTargetClick: () -> Unit,
        onSwap: () -> Unit,
        modifier: Modifier = Modifier
    ) {
        var rotation by remember { mutableStateOf(0f) }
        val animatedRotation by animateFloatAsState(targetValue = rotation, animationSpec = tween(300), label = "swap_rotate")

        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            LanguagePill(label = sourceLabel, flag = sourceFlag, modifier = Modifier.weight(1f).clickable { onSourceClick() })
            MiniWaveform(barCount = 4, modifier = Modifier.padding(horizontal = 6.dp))
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.12f))
                    .clickable { 
                        rotation += 360f
                        onSwap() 
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "Swap Languages",
                    tint = TextPrimary,
                    modifier = Modifier.size(18.dp).rotate(animatedRotation)
                )
            }
            MiniWaveform(barCount = 4, modifier = Modifier.padding(horizontal = 6.dp))
            LanguagePill(label = targetLabel, flag = targetFlag, modifier = Modifier.weight(1f).clickable { onTargetClick() })
        }
    }

    @Composable
    private fun LanguagePill(label: String, flag: String, modifier: Modifier = Modifier) {
        Row(
            modifier = modifier
                .clip(RoundedCornerShape(24.dp))
                .background(PillBg)
                .border(0.75.dp, PillBorder, RoundedCornerShape(24.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = flag, fontSize = 16.sp)
            Spacer(Modifier.width(8.dp))
            Text(
                text = label,
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }

    @Composable
    private fun TranscriptRow(
        speakerLabel: String,
        message: String,
        waveformOnStart: Boolean,
        isSystem: Boolean = false
    ) {
        var showReportDialog by remember { mutableStateOf(false) }
        val context = androidx.compose.ui.platform.LocalContext.current

        Row(verticalAlignment = Alignment.Top) {
            if (waveformOnStart && !isSystem) {
                MiniWaveform(barCount = 5, tall = true, modifier = Modifier.padding(end = 10.dp, top = 2.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(text = speakerLabel, color = if (isSystem) AccentCoral else TextSecondary, fontSize = 13.sp)
                Spacer(Modifier.height(4.dp))
                Text(text = message, color = if (isSystem) AccentCoral else TextPrimary, fontSize = 16.sp, lineHeight = 22.sp)
                
                if (!isSystem && message.isNotBlank() && message != "...") {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Flag,
                            contentDescription = "Report Translation",
                            tint = TextSecondary,
                            modifier = Modifier
                                .size(16.dp)
                                .clickable { showReportDialog = true }
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "Report",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            modifier = Modifier.clickable { showReportDialog = true }
                        )
                    }
                }
            }
            if (!waveformOnStart && !isSystem) {
                MiniWaveform(barCount = 5, tall = true, modifier = Modifier.padding(start = 10.dp, top = 2.dp))
            }
        }

        if (showReportDialog) {
            AlertDialog(
                onDismissRequest = { showReportDialog = false },
                title = { Text("Report Translation") },
                text = { Text("Please report this translation if it contains offensive, inappropriate, or hallucinatory AI-generated content.") },
                confirmButton = {
                    TextButton(onClick = {
                        showReportDialog = false
                        val intent = Intent(Intent.ACTION_SENDTO).apply {
                            data = Uri.parse("mailto:")
                            putExtra(Intent.EXTRA_EMAIL, arrayOf("edumapstu@gmail.com"))
                            putExtra(Intent.EXTRA_SUBJECT, "App Report: AI Translation Issue")
                            putExtra(Intent.EXTRA_TEXT, "I am reporting the following translation:\n\n\"$message\"\n\nReason for reporting: ")
                        }
                        context.startActivity(intent)
                    }) {
                        Text("Send Report Email")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showReportDialog = false }) {
                        Text("Cancel")
                    }
                },
                containerColor = Color(0xFF1E1E1E),
                titleContentColor = Color.White,
                textContentColor = Color.LightGray
            )
        }
    }

    @Composable
    private fun MiniWaveform(
        barCount: Int = 4,
        tall: Boolean = false,
        modifier: Modifier = Modifier
    ) {
        val heights = remember(barCount) {
            List(barCount) { Random.nextFloat() * 0.6f + 0.4f }
        }
        val maxHeight = if (tall) 28.dp else 16.dp
        Row(
            modifier = modifier.height(maxHeight),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            heights.forEach { h ->
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .fillMaxHeight(h)
                        .background(TextPrimary.copy(alpha = 0.85f), RoundedCornerShape(1.dp))
                )
            }
        }
    }

    @Composable
    private fun BackgroundWaveform(modifier: Modifier = Modifier) {
        Canvas(modifier = modifier) {
            val barWidth = 3.dp.toPx()
            val gap = 5.dp.toPx()
            val count = (size.width / (barWidth + gap)).toInt()
            val centerY = size.height * 0.45f
            repeat(count) { i ->
                val x = i * (barWidth + gap)
                val distFromCenter = kotlin.math.abs(x - size.width / 2f) / (size.width / 2f)
                val amplitude = (1f - distFromCenter).coerceIn(0f, 1f)
                val barHeight = (Random.nextFloat() * 0.5f + 0.2f) * amplitude * size.height * 0.35f
                drawLine(
                    color = Color.White.copy(alpha = 0.04f + amplitude * 0.05f),
                    start = Offset(x, centerY - barHeight / 2f),
                    end = Offset(x, centerY + barHeight / 2f),
                    strokeWidth = barWidth,
                    cap = StrokeCap.Round
                )
            }
        }
    }

    @Composable
    private fun BottomControls(isRecording: Boolean, isLoading: Boolean, onMicClick: () -> Unit, modifier: Modifier = Modifier) {
        Box(modifier = modifier) {
            NavBar(modifier = Modifier.align(Alignment.BottomCenter))
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = (-14).dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                MicButton(isRecording = isRecording, isLoading = isLoading, onClick = onMicClick)
                Spacer(Modifier.height(10.dp))
                Text(
                    text = if (isLoading) "INITIALIZING AI..." else if (isRecording) "TAP TO STOP" else "TAP TO SPEAK",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }
        }
    }

    @Composable
    private fun MicButton(isRecording: Boolean, isLoading: Boolean, onClick: () -> Unit) {
        val infiniteTransition = rememberInfiniteTransition()
        val scale by infiniteTransition.animateFloat(
            initialValue = 1f, targetValue = if (isRecording) 1.2f else 1f,
            animationSpec = infiniteRepeatable(animation = tween(800, easing = FastOutSlowInEasing), repeatMode = RepeatMode.Reverse),
            label = "mic_pulse"
        )
        
        Box(contentAlignment = Alignment.Center, modifier = Modifier.clickable(enabled = !isLoading) { onClick() }) {
            if (isRecording) {
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .scale(scale)
                        .clip(CircleShape)
                        .background(AccentCoral.copy(alpha = 0.10f))
                )
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .scale(scale)
                        .clip(CircleShape)
                        .background(AccentCoral.copy(alpha = 0.16f))
                )
            }
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(Brush.radialGradient(listOf(AccentCoral, AccentCoralDeep))),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                    contentDescription = "Mic",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }

    @Composable
    private fun NavBar(modifier: Modifier = Modifier) {
        val bottomNavHeight = 72.dp
        Row(
            modifier = modifier
                .fillMaxWidth()
                .height(bottomNavHeight)
                .background(Color(0xFF101010)),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            NavItem(
                icon = Icons.Outlined.Home,
                label = "Home",
                active = currentScreen == "Home",
                onClick = { currentScreen = "Home" },
                modifier = Modifier.weight(1f).padding(top = 12.dp)
            )
            Spacer(modifier = Modifier.weight(1f))
            NavItem(
                icon = Icons.Outlined.Settings,
                label = "Settings",
                active = currentScreen == "Settings",
                onClick = { currentScreen = "Settings" },
                modifier = Modifier.weight(1f).padding(top = 12.dp)
            )
        }
    }

    @Composable
    private fun NavItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
        val tint = if (active) AccentCoral else NavInactive
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier.clickable { onClick() }) {
            Icon(imageVector = icon, contentDescription = label, tint = tint, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(4.dp))
            Text(text = label, color = tint, fontSize = 12.sp)
        }
    }

    // Test & Benchmark Accessors (Visible for androidTest / benchmark harness)
    fun setLanguagesForBenchmark(source: String, target: String, targetTag: String) {
        sourceLanguage.value = source
        targetLanguage.value = target
        targetLanguageTag.value = targetTag
        ttsEngine.setLanguage(targetTag)
    }

    fun getTranslationEngineInstance(): TranslationEngine = translationEngine
    fun getStreamingTTSManagerInstance(): StreamingTTSManager = ttsEngine
    fun getAudioRecorderHelperInstance(): AudioRecorderHelper = audioRecorderHelper
    fun isModelReadyState(): Boolean = isModelReady.value
    fun getMessagesList(): List<ChatMessage> = messages.toList()

    override fun onDestroy() {
        super.onDestroy()
        audioRecorderHelper.stopRecording()
        cancelAndClearQueue("activity_destroyed")
        ttsEngine.shutdown()
        translationEngine.close()
    }
}
