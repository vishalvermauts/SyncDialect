package com.example.auravoice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream

class MainActivity : ComponentActivity() {

    private lateinit var audioRecorderHelper: AudioRecorderHelper
    private lateinit var ttsEngine: StreamingTTSManager
    private lateinit var translationEngine: TranslationEngine
    private lateinit var downloadManager: ModelDownloadManager

    private var isRecording = mutableStateOf(false)
    private var translatedText = mutableStateOf("")
    private var engineLoading = mutableStateOf(false)
    private var errorText = mutableStateOf("")
    private var latencyText = mutableStateOf("")

    private var currentAudioStream: ByteArrayOutputStream? = null
    private val translationMutex = Mutex()

    private var sourceLanguage = mutableStateOf("English")
    private var targetLanguage = mutableStateOf("Spanish")

    private var rawTranslationBuffer = StringBuilder()

    // States for Model Download
    private var isModelReady = mutableStateOf(false)
    private var isDownloading = mutableStateOf(false)
    private var downloadProgress = mutableStateOf(0)

    private val languages = listOf("English", "Spanish", "French", "German", "Japanese", "Hindi")

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (!isGranted) {
                Toast.makeText(this, "Microphone permission required for AuraVoice", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize engines
        ttsEngine = StreamingTTSManager(this) { intent ->
            if (intent != null) {
                startActivity(intent)
            }
        }

        ttsEngine.onSpeakingStateChanged = { isSpeaking ->
            if (isSpeaking) {
                audioRecorderHelper.isMuted = true
            } else {
                // When TTS stops speaking, wait a short cooldown (e.g. 400ms) before unmuting the recorder.
                // This prevents capturing room reverberations or the very end of the speaker's audio.
                lifecycleScope.launch {
                    kotlinx.coroutines.delay(400)
                    // Check if TTS didn't start speaking again during this delay
                    if (!ttsEngine.isSpeaking()) {
                        audioRecorderHelper.isMuted = false
                    }
                }
            }
        }

        translationEngine = TranslationEngine(this) { partialToken, ttft ->
            runOnUiThread {
                if (ttft != null) {
                    latencyText.value = "Delay: ${ttft}ms"
                    if (rawTranslationBuffer.isNotEmpty()) {
                        rawTranslationBuffer.append(" ")
                    }
                }

                rawTranslationBuffer.append(partialToken)
                
                var cleanText = rawTranslationBuffer.toString()
                cleanText = cleanText.replace(Regex("<[^>]*>"), "")
                    .replace("end_of_turn", "").replace("start_of_turn", "")
                    .replace("eos", "").replace("model", "").replace("user", "")
                    .replace("thought", "").replace("*", "")
                cleanText = cleanText.replace(Regex("<[^>]*$"), "")
                    .replace(Regex("end_[^ ]*$"), "").replace(Regex("start_[^ ]*$"), "")
                val finalText = cleanText.trim()

                if (finalText.isNotEmpty()) {
                    translatedText.value = finalText
                }
                
                var cleanPartial = partialToken.replace(Regex("<[^>]*>"), "")
                    .replace("end_of_turn", "").replace("start_of_turn", "")
                    .replace("eos", "").replace("model", "").replace("user", "")
                    .replace("thought", "").replace("*", "")
                
                if (cleanPartial.isNotEmpty()) {
                    ttsEngine.processToken(cleanPartial)
                }
            }
        }

        audioRecorderHelper = AudioRecorderHelper()

        // Initialize Download Manager
        downloadManager = ModelDownloadManager(this)
        isModelReady.value = downloadManager.isModelDownloaded()

        // Request permissions
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF121212)
                ) {
                    if (isModelReady.value) {
                        AuraVoiceApp()
                    } else {
                        SetupScreen()
                    }
                }
            }
        }

        // Do NOT initialize engine at startup — load lazily on first mic press
        // This prevents OOM from loading a 2.5GB model while other apps use RAM
    }

    @Composable
    fun SetupScreen() {
        val coroutineScope = rememberCoroutineScope()
        
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("AuraVoice Setup", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "To run 100% offline, AuraVoice requires the Gemma 4 E2B AI model. This is a one-time download.",
                color = Color.LightGray,
                fontSize = 16.sp
            )
            Spacer(modifier = Modifier.height(32.dp))
            
            if (isDownloading.value) {
                Text("Downloading Model... ${downloadProgress.value}%", color = Color.White)
                Spacer(modifier = Modifier.height(16.dp))
                LinearProgressIndicator(
                    progress = downloadProgress.value / 100f,
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    color = Color(0xFFBB86FC),
                    trackColor = Color.DarkGray
                )
            } else {
                Button(
                    onClick = {
                        isDownloading.value = true
                        coroutineScope.launch {
                            downloadManager.downloadModel(
                                onProgress = { progress -> downloadProgress.value = progress },
                                onComplete = { success ->
                                    isDownloading.value = false
                                    if (success) {
                                        isModelReady.value = true
                                        // Initialize engine after download
                                        coroutineScope.launch {
                                            translationEngine.initialize(downloadManager.getModelAbsolutePath())
                                        }
                                    } else {
                                        Toast.makeText(this@MainActivity, "Download Failed", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFBB86FC))
                ) {
                    Text("Download Gemma Model (~2.5GB)", color = Color.Black)
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun AuraVoiceApp() {
        val coroutineScope = rememberCoroutineScope()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header Hardware Status
            Surface(
                color = Color(0xFF1E1E1E),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "🟢 Gemma 4 E2B Active (GPU)",
                        color = Color(0xFF81C784),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "🟢 End-to-End Audio Pipeline Ready",
                        color = Color(0xFF81C784),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Language Selectors
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                LanguageDropdown(label = "Source", selected = sourceLanguage.value) { sourceLanguage.value = it }
                Text("➡", color = Color.White, modifier = Modifier.align(Alignment.CenterVertically))
                LanguageDropdown(label = "Target", selected = targetLanguage.value) { 
                    targetLanguage.value = it 
                    // Update TTS language based on selection
                    when(it) {
                        "Spanish" -> ttsEngine.setLanguage("es")
                        "French" -> ttsEngine.setLanguage("fr")
                        "German" -> ttsEngine.setLanguage("de")
                        "Japanese" -> ttsEngine.setLanguage("ja")
                        "English" -> ttsEngine.setLanguage("en")
                        "Hindi" -> ttsEngine.setLanguage("hi")
                    }
                }
            }

            // Translation output panel
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF1A1A1A))
                    .padding(16.dp),
                verticalArrangement = Arrangement.Center
            ) {
                if (translatedText.value.isEmpty()) {
                    Text(
                        text = if (isRecording.value) "Listening…" else "Tap the mic and speak",
                        color = Color.Gray,
                        fontSize = 20.sp,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                } else {
                    Text(
                        text = translatedText.value,
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Microphone Button
            Spacer(modifier = Modifier.height(32.dp))

            if (engineLoading.value) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color(0xFFBB86FC))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Loading model…", color = Color.Gray, fontSize = 14.sp)
                }
            } else {
                MicrophoneButton(
                    isRecording = isRecording.value,
                    onClick = {
                        if (isRecording.value) {
                            // Stop recording
                            audioRecorderHelper.stopRecording()
                            isRecording.value = false
                        } else {
                            // Start Continuous Recording
                            translatedText.value = ""
                            rawTranslationBuffer.clear()
                            errorText.value = ""
                            latencyText.value = ""
                            engineLoading.value = true

                            coroutineScope.launch {
                                val ready = translationEngine.initialize(
                                    downloadManager.getModelAbsolutePath()
                                )
                                engineLoading.value = false
                                if (ready) {
                                    isRecording.value = true
                                    audioRecorderHelper.startContinuousRecording { chunkData ->
                                        coroutineScope.launch {
                                            translationMutex.withLock {
                                                translationEngine.translateAudioStreaming(
                                                    sourceLanguage.value,
                                                    targetLanguage.value,
                                                    chunkData,
                                                    onError = { msg ->
                                                        runOnUiThread { errorText.value = msg }
                                                    }
                                                )
                                                runOnUiThread { ttsEngine.flush() }
                                            }
                                        }
                                    }
                                } else {
                                    runOnUiThread { errorText.value = "Model failed to load — try again" }
                                }
                            }
                        }
                    }
                )
            }

            // Latency text
            if (latencyText.value.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = latencyText.value,
                    color = Color(0xFF03DAC5),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            // Error text
            if (errorText.value.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = errorText.value,
                    color = Color(0xFFCF6679),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun LanguageDropdown(label: String, selected: String, onSelectionChanged: (String) -> Unit) {
        var expanded by remember { mutableStateOf(false) }

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded }
        ) {
            OutlinedTextField(
                value = selected,
                onValueChange = {},
                readOnly = true,
                label = { Text(label, color = Color.Gray) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(
                    focusedBorderColor = Color(0xFFBB86FC),
                    unfocusedBorderColor = Color.DarkGray,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                modifier = Modifier.menuAnchor().width(130.dp)
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                languages.forEach { selectionOption ->
                    DropdownMenuItem(
                        text = { Text(selectionOption) },
                        onClick = {
                            onSelectionChanged(selectionOption)
                            expanded = false
                        }
                    )
                }
            }
        }
    }

    @Composable
    fun MicrophoneButton(isRecording: Boolean, onClick: () -> Unit) {
        val infiniteTransition = rememberInfiniteTransition()
        val scale by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = if (isRecording) 1.2f else 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(800, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            )
        )

        Box(
            modifier = Modifier
                .size(80.dp)
                .scale(scale)
                .clip(CircleShape)
                .background(if (isRecording) Color(0xFFCF6679) else Color(0xFFBB86FC))
                .clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (isRecording) "⬛" else "🎙",
                fontSize = 32.sp,
                color = Color.White
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        audioRecorderHelper.stopRecording()
        ttsEngine.shutdown()
    }
}
