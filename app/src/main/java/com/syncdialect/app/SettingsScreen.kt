package com.syncdialect.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    isModelReady: MutableState<Boolean>,
    isDownloading: MutableState<Boolean>,
    downloadProgress: MutableState<Float>,
    targetLanguage: MutableState<String>,
    targetLanguageTag: MutableState<String>,
    forceOfflineTts: MutableState<Boolean>,
    ttsEngine: StreamingTTSManager,
    downloadManager: ModelDownloadManager,
    mainActivityContext: android.content.Context
) {
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    var showVoiceSettings by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val notificationPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        isDownloading.value = true
        downloadProgress.value = 0f
        coroutineScope.launch {
            downloadManager.downloadModel(
                onProgress = { progress -> downloadProgress.value = progress / 100f },
                onComplete = { success -> 
                    isDownloading.value = false
                    if (success) isModelReady.value = true
                }
            )
        }
    }
    
    val TextPrimary = Color.White
    val TextSecondary = Color.Gray
    val CardBackground = Color(0xFF242424) // Darker card background to match mockup
    val CardBorder = Color(0xFF3A3A3A)
    val AccentCoral = Color(0xFFE8593C)
    
    val gemmaModelPath = downloadManager.getModelAbsolutePath()

    Column(modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(40.dp))

        // HEADER
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Preferences", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Text("App Customization & Engines", fontSize = 14.sp, color = TextSecondary)
            }
            // Removed the X cross mark as requested
        }

        Spacer(Modifier.height(24.dp))

        // 1. GEMMA OFFLINE ENGINE CARD
        ElevatedCard(
            colors = CardDefaults.elevatedCardColors(containerColor = CardBackground),
            modifier = Modifier.fillMaxWidth().border(1.dp, CardBorder, RoundedCornerShape(16.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Memory, contentDescription = null, tint = AccentCoral, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Gemma Offline Engine", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                }
                Spacer(Modifier.height(4.dp))
                Text("Manage the 2.2GB offline LLM powering translation. Required for core functionality.", color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(start = 36.dp))
                Spacer(Modifier.height(20.dp))

                // Progress Info
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    val statusText = if (isDownloading.value) "Downloading Language Data..." else if (isModelReady.value) "Installed & Ready" else "Available for Download"
                    Text(statusText, color = TextSecondary, fontSize = 12.sp)
                    Text("2.2 GB", color = TextSecondary, fontSize = 12.sp)
                }

                Spacer(Modifier.height(8.dp))

                if (isModelReady.value) {
                    Button(
                        onClick = { showDeleteConfirm = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A1A1A)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().border(1.dp, CardBorder, RoundedCornerShape(12.dp)).height(48.dp)
                    ) {
                        Text("Delete Gemma Model", color = AccentCoral, fontWeight = FontWeight.SemiBold)
                    }
                } else if (isDownloading.value) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF1A1A1A))
                        ) {
                            Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(downloadProgress.value).background(AccentCoral))
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("Downloading: ${(downloadProgress.value * 100).toInt()}%", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        IconButton(
                            onClick = { 
                                downloadManager.cancelDownload()
                                isDownloading.value = false
                            },
                            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF1A1A1A))
                        ) {
                            Icon(Icons.Default.Pause, contentDescription = "Pause", tint = Color.White)
                        }
                    }
                } else {
                    Button(
                        onClick = {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                isDownloading.value = true
                                downloadProgress.value = 0f
                                coroutineScope.launch {
                                    downloadManager.downloadModel(
                                        onProgress = { progress -> downloadProgress.value = progress / 100f },
                                        onComplete = { success -> 
                                            isDownloading.value = false
                                            if (success) isModelReady.value = true
                                        }
                                    )
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentCoral),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Text("Download Gemma", color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        
        Spacer(Modifier.height(16.dp))

        // 2. ML KIT LANGUAGE PACKS
        LanguagePacksCard(AccentCoral, CardBackground, CardBorder)

        Spacer(Modifier.height(16.dp))

        // 3. PREFERENCES CARD
        var preferencesEnabled by remember { mutableStateOf(false) }
        ElevatedCard(
            colors = CardDefaults.elevatedCardColors(containerColor = CardBackground),
            modifier = Modifier.fillMaxWidth().border(1.dp, CardBorder, RoundedCornerShape(16.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Enable Background Translation", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                    Switch(
                        checked = preferencesEnabled,
                        onCheckedChange = { preferencesEnabled = it },
                        enabled = false,
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = AccentCoral)
                    )
                }
                Text("Coming Soon - Translate seamlessly while using other apps", fontSize = 12.sp, color = TextSecondary)
            }
        }

        Spacer(Modifier.height(16.dp))

        // 4. VOICE & SPEECH CARD
        ElevatedCard(
            colors = CardDefaults.elevatedCardColors(containerColor = CardBackground),
            modifier = Modifier.fillMaxWidth().border(1.dp, CardBorder, RoundedCornerShape(16.dp)).clickable { showVoiceSettings = true }
        ) {
            Row(
                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Voice & Speech", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Menu", color = TextSecondary, fontSize = 14.sp)
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(20.dp))
                }
            }
        }
        
        Spacer(Modifier.height(16.dp))

        // 5. LEGAL & SUPPORT CARD
        ElevatedCard(
            colors = CardDefaults.elevatedCardColors(containerColor = CardBackground),
            modifier = Modifier.fillMaxWidth().border(1.dp, CardBorder, RoundedCornerShape(16.dp))
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // How to Use
                var showTutorial by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { showTutorial = true }.padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("How to Use", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(20.dp))
                }
                
                HorizontalDivider(color = CardBorder, thickness = 1.dp)
                
                // Terms & Conditions
                var showTerms by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { showTerms = true }.padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Terms and Conditions", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(20.dp))
                }
                
                HorizontalDivider(color = CardBorder, thickness = 1.dp)
                
                if (showTutorial) {
                    androidx.compose.ui.window.Dialog(
                        onDismissRequest = { showTutorial = false },
                        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
                    ) {
                        Box(modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFF050507), Color(0xFF14141C))))) {
                            TutorialScreen(onComplete = { showTutorial = false })
                        }
                    }
                }
                
                // Contact Us
                Row(
                    modifier = Modifier.fillMaxWidth().clickable {
                        val intent = android.content.Intent(android.content.Intent.ACTION_SENDTO).apply {
                            data = android.net.Uri.parse("mailto:")
                            putExtra(android.content.Intent.EXTRA_EMAIL, arrayOf("edumapstu@gmail.com"))
                            putExtra(android.content.Intent.EXTRA_SUBJECT, "SyncDialect App Support")
                        }
                        mainActivityContext.startActivity(intent)
                    }.padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Contact Us", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(20.dp))
                }

                if (showTerms) {
                    AlertDialog(
                        onDismissRequest = { showTerms = false },
                        containerColor = Color(0xFF1E1E1E),
                        title = { Text("Terms and Conditions", color = Color.White) },
                        text = {
                            LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                                item {
                                    Text(
                                        "Welcome to SyncDialect.\n\n" +
                                        "1. Privacy: All voice translations happen locally on your device. We do not upload, store, or sell your voice recordings.\n\n" +
                                        "2. User Conduct: You agree not to use the app for any illegal purposes or to generate harmful content.\n\n" +
                                        "3. Intellectual Property: The translation models are provided via Google ML Kit and Gemma. You must adhere to their respective terms of service.\n\n" +
                                        "4. Liability: SyncDialect is provided \"as is\". We are not responsible for translation inaccuracies or any consequences arising from misinterpretation.\n\n" +
                                        "5. AI Limitations: Machine learning models may occasionally produce hallucinatory or incorrect text. You are responsible for verifying critical translations.\n\n" +
                                        "Contact us at edumapstu@gmail.com for any questions.",
                                        color = Color.LightGray, fontSize = 14.sp
                                    )
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showTerms = false }) {
                                Text("Close", color = AccentCoral)
                            }
                        }
                    )
                }
            }
        }
        
        Spacer(Modifier.height(16.dp))

        
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Powered by Gemma", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text("Google Gemma 2B IT (Instruction Tuned)", color = TextSecondary, fontSize = 10.sp)
        }
        Spacer(Modifier.height(140.dp))
    }

    if (showVoiceSettings) {
        VoiceSettingsDialog(targetLanguage.value, targetLanguageTag.value, ttsEngine, forceOfflineTts, AccentCoral) { showVoiceSettings = false }
    }

    if (showDeleteConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Gemma Model?") },
            text = { Text("Are you sure you want to delete the 2.2GB Gemma model? Translation will not work offline.") },
            confirmButton = {
                TextButton(onClick = {
                    downloadManager.deleteModel()
                    isModelReady.value = false
                    showDeleteConfirm = false
                }) {
                    Text("Delete", color = Color.Red)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel", color = Color.White)
                }
            },
            containerColor = CardBackground,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary
        )
    }
}


fun getFlagEmoji(localeTag: String): String {
    val locale = Locale.forLanguageTag(localeTag)
    val countryCode = locale.country
    if (countryCode.isEmpty()) {
        // Fallbacks for languages without clear country codes in ML Kit
        return when (locale.language) {
            "en" -> "🇺🇸"
            "es" -> "🇪🇸"
            "fr" -> "🇫🇷"
            "de" -> "🇩🇪"
            "it" -> "🇮🇹"
            "ja" -> "🇯🇵"
            "ko" -> "🇰🇷"
            "zh" -> "🇨🇳"
            "hi" -> "🇮🇳"
            "ru" -> "🇷🇺"
            "ar" -> "🇸🇦"
            "pt" -> "🇧🇷"
            "tr" -> "🇹🇷"
            "ur" -> "🇵🇰"
            "nl" -> "🇳🇱"
            else -> "🌐"
        }
    }
    val firstLetter = Character.codePointAt(countryCode, 0) - 0x41 + 0x1F1E6
    val secondLetter = Character.codePointAt(countryCode, 1) - 0x41 + 0x1F1E6
    return String(Character.toChars(firstLetter)) + String(Character.toChars(secondLetter))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguagePacksCard(accentColor: Color, cardBackground: Color, cardBorder: Color) {
    var downloadedModels by remember { mutableStateOf<Set<String>>(emptySet()) }
    var downloadingModels by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isChecking by remember { mutableStateOf(true) }
    var showAddDialog by remember { mutableStateOf(false) }
    var languageToDelete by remember { mutableStateOf<Triple<String, String, String>?>(null) }
    
    val allTags = TranslateLanguage.getAllLanguages()
    val modelManager = RemoteModelManager.getInstance()

    val checkModels = {
        modelManager.getDownloadedModels(TranslateRemoteModel::class.java)
            .addOnSuccessListener { models ->
                downloadedModels = models.map { it.language }.toSet()
                isChecking = false
            }
            .addOnFailureListener {
                isChecking = false
            }
    }
    
    LaunchedEffect(Unit) {
        checkModels()
    }

    val allLanguages = allTags.map { tag ->
        val displayName = Locale.forLanguageTag(tag).displayLanguage.replaceFirstChar { it.uppercase() }
        val flag = getFlagEmoji(tag)
        Triple(displayName, tag, flag)
    }.sortedBy { it.first }

    val installed = allLanguages.filter { downloadedModels.contains(it.second) }
    val available = allLanguages.filter { !downloadedModels.contains(it.second) && !downloadingModels.contains(it.second) }.take(5) // Show top 5 for visual cleanliness, "Manage Packs" opens full list

    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = cardBackground),
        modifier = Modifier.fillMaxWidth().border(1.dp, cardBorder, RoundedCornerShape(16.dp))
    ) {
        Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
            // Header Row
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(accentColor),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Language, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.width(12.dp))
                Text("Language Packs", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            }
            
            Spacer(Modifier.height(16.dp))
            
            if (isChecking) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally).size(24.dp), color = accentColor)
            } else {
                if (installed.isNotEmpty()) {
                    Text("Installed", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
                    installed.forEach { (displayName, tag, flag) ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(flag, fontSize = 20.sp)
                                Spacer(Modifier.width(12.dp))
                                Text(displayName, color = Color.White, fontSize = 16.sp)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("~30 MB", color = Color.Gray, fontSize = 14.sp)
                                Spacer(Modifier.width(12.dp))
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Delete",
                                    tint = Color.Gray,
                                    modifier = Modifier.size(20.dp).clickable {
                                        languageToDelete = Triple(displayName, tag, flag)
                                    }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
                
                Button(
                    onClick = { showAddDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Text("Download More Languages", color = Color.White, fontWeight = FontWeight.SemiBold)
                }

                // Add Dialog (unchanged functionally, just styled)
                if (showAddDialog) {
                    val fullAvailable = allLanguages.filter { !downloadedModels.contains(it.second) }
                    AlertDialog(
                        onDismissRequest = { showAddDialog = false },
                        containerColor = Color(0xFF1E1E1E),
                        title = { Text("Available Languages", color = Color.White) },
                        text = {
                            LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                                items(fullAvailable.size) { idx ->
                                    val lang = fullAvailable[idx]
                                    Row(
                                        modifier = Modifier.fillMaxWidth().clickable {
                                            showAddDialog = false
                                            val tag = lang.second
                                            val model = TranslateRemoteModel.Builder(tag).build()
                                            downloadingModels = downloadingModels + tag
                                            modelManager.download(model, DownloadConditions.Builder().build())
                                                .addOnSuccessListener {
                                                    downloadingModels = downloadingModels - tag
                                                    checkModels()
                                                }
                                        }.padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(lang.third, fontSize = 20.sp)
                                        Spacer(Modifier.width(12.dp))
                                        Text(lang.first, color = Color.White, fontSize = 18.sp)
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showAddDialog = false }) {
                                Text("Close", color = accentColor)
                            }
                        }
                    )
                }

                // Delete Confirmation Dialog
                languageToDelete?.let { (displayName, tag, flag) ->
                    AlertDialog(
                        onDismissRequest = { languageToDelete = null },
                        containerColor = Color(0xFF1E1E1E),
                        title = { Text("Delete $displayName?", color = Color.White) },
                        text = { Text("Are you sure you want to remove the $displayName translation pack? You will need an internet connection to download it again.", color = Color.LightGray) },
                        confirmButton = {
                            Button(
                                onClick = {
                                    val model = TranslateRemoteModel.Builder(tag).build()
                                    modelManager.deleteDownloadedModel(model).addOnSuccessListener {
                                        checkModels()
                                    }
                                    languageToDelete = null
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                            ) {
                                Text("Delete", color = Color.White)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { languageToDelete = null }) {
                                Text("Cancel", color = accentColor)
                            }
                        }
                    )
                }
            }
        }
    }
}

data class FormattedVoice(val voice: android.speech.tts.Voice, val gender: String, val type: String, val displayName: String, val originalName: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceSettingsDialog(
    targetLanguage: String,
    targetLanguageTag: String,
    ttsEngine: StreamingTTSManager,
    forceOfflineTts: MutableState<Boolean>,
    accentColor: Color,
    onDismiss: () -> Unit
) {
    var availableVoices by remember { mutableStateOf<List<FormattedVoice>>(emptyList()) }
    var expanded by remember { mutableStateOf(false) }
    var selectedVoiceName by remember { mutableStateOf("Default") }
    var selectedOriginalVoiceName by remember { mutableStateOf("") }
    val context = androidx.compose.ui.platform.LocalContext.current
    
    LaunchedEffect(targetLanguageTag) {
        val voices = ttsEngine.getAvailableVoices(targetLanguageTag)
        
        val formatted = voices.map { voice ->
            val isNetwork = voice.isNetworkConnectionRequired
            val type = if (isNetwork) "Network" else "Offline"
            val parts = voice.name.split("-")
            
            val isFemale = parts.any { it.equals("female", true) }
            val isMale = parts.any { it.equals("male", true) }
            val gender = if (isFemale) "Female" else if (isMale) "Male" else "Any"
            
            val genderLabel = if (isFemale) "(Female)" else if (isMale) "(Male)" else ""
            
            var desc = voice.locale.displayName
            if (parts.size >= 4) {
                 val localeDesc = voice.locale.displayCountry.takeIf { it.isNotEmpty() } ?: voice.locale.displayName
                 desc = "$localeDesc $genderLabel"
            } else {
                 desc = "$desc $genderLabel"
            }
            
            FormattedVoice(voice, gender, type, desc.trim(), voice.name)
        }
        
        // Sort: Offline first, then Network
        availableVoices = formatted.sortedBy { it.voice.isNetworkConnectionRequired }
        
        if (availableVoices.isNotEmpty()) {
            val firstVoice = availableVoices.first()
            selectedVoiceName = "${firstVoice.displayName} - ${firstVoice.type}"
            selectedOriginalVoiceName = firstVoice.originalName
        }
    }

    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = Color(0xFF1A1A1A),
            modifier = Modifier.fillMaxWidth().border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(24.dp))
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text("Voice Settings", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("Current Target: $targetLanguage", color = Color.Gray, fontSize = 14.sp)
                
                Spacer(Modifier.height(24.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Force Offline TTS", color = Color.White, fontSize = 16.sp)
                    Switch(
                        checked = forceOfflineTts.value,
                        onCheckedChange = { forceOfflineTts.value = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = accentColor, checkedTrackColor = accentColor.copy(alpha=0.3f))
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text("If enabled, the app will only use voices downloaded to your phone. If disabled, it may use higher quality network voices.", color = Color.Gray, fontSize = 12.sp)

                Spacer(Modifier.height(24.dp))
                Text("Select Accent / Voice", color = Color.White, fontSize = 16.sp)
                Spacer(Modifier.height(8.dp))

                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded }
                ) {
                    OutlinedTextField(
                        value = selectedVoiceName,
                        onValueChange = {},
                        readOnly = true,
                        colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(
                            focusedBorderColor = accentColor,
                            unfocusedBorderColor = Color.DarkGray,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.LightGray
                        ),
                        modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) }
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                        containerColor = Color(0xFF1A1A1A),
                        modifier = Modifier.heightIn(max = 250.dp)
                    ) {
                        if (availableVoices.isEmpty()) {
                            DropdownMenuItem(text = { Text("No voices found matching criteria", color = Color.Gray) }, onClick = { expanded = false })
                        } else {
                            availableVoices.forEach { formattedVoice ->
                                val formattedName = "${formattedVoice.displayName} - ${formattedVoice.type}"
                                DropdownMenuItem(
                                    text = { 
                                        Column {
                                            Text(formattedVoice.displayName, color = Color.White)
                                            Text(formattedVoice.type, color = if (formattedVoice.type == "Offline") Color(0xFF4CAF50) else Color(0xFF2196F3), fontSize = 12.sp)
                                        }
                                    },
                                    onClick = {
                                        selectedVoiceName = formattedName
                                        selectedOriginalVoiceName = formattedVoice.originalName
                                        ttsEngine.setSpecificVoice(formattedVoice.originalName)
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                }
                
                var currentSpeechRate by remember { mutableStateOf(AppPreferences(context).ttsSpeechRate) }

                Spacer(Modifier.height(24.dp))
                Text("Speech Rate", color = Color.White, fontSize = 16.sp)
                Slider(
                    value = currentSpeechRate,
                    onValueChange = { 
                        currentSpeechRate = it
                        AppPreferences(context).ttsSpeechRate = it
                        ttsEngine.setSpeechRate(it)
                    },
                    valueRange = 0.5f..2.0f,
                    steps = 14,
                    colors = SliderDefaults.colors(
                        thumbColor = accentColor,
                        activeTrackColor = accentColor
                    )
                )
                Text("Speed: ${String.format(java.util.Locale.US, "%.1fx", currentSpeechRate)}", color = Color.Gray, fontSize = 12.sp)
                
                Spacer(Modifier.height(24.dp))
                
                Button(
                    onClick = { ttsEngine.playText("This is a demo of the translation voice.") },
                    colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Text("Play Demo Voice", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }

                Spacer(Modifier.height(16.dp))
                
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = Color.Gray, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { 
                        if (selectedOriginalVoiceName.isNotEmpty()) {
                            ttsEngine.saveVoice(selectedOriginalVoiceName)
                            android.widget.Toast.makeText(context, "$selectedVoiceName selected", android.widget.Toast.LENGTH_SHORT).show()
                        }
                        onDismiss()
                    }) {
                        Text("Save", color = accentColor, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
