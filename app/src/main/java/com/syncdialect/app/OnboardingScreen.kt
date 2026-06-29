package com.syncdialect.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(
    downloadedLanguages: List<Pair<String, String>>,
    onComplete: (sourceLang: String, targetLang: String, targetTag: String) -> Unit
) {
    val BgTop = Color(0xFF050507)
    val BgBottom = Color(0xFF14141C)
    val AccentCoral = Color(0xFFE8593C)
    val TextPrimary = Color.White
    val TextSecondary = Color(0xFFA0A0A8)

    val pagerState = rememberPagerState(pageCount = { 5 })
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    var selectedSource by remember { mutableStateOf<Pair<String, String>?>(null) }
    var selectedTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    
        // Auto advance from splash
    LaunchedEffect(Unit) {
        delay(2500)
        pagerState.scrollToPage(1)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(BgTop, BgBottom)))
            .navigationBarsPadding()
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = pagerState.currentPage != 0 // Disable manual scroll on splash
        ) { page ->
            when (page) {
                0 -> {
                    // Splash Screen
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(100.dp)
                                .clip(CircleShape)
                                .background(AccentCoral.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = null,
                                tint = AccentCoral,
                                modifier = Modifier.size(50.dp)
                            )
                        }
                        Spacer(Modifier.height(24.dp))
                        Text(
                            text = "SyncDialect",
                            color = TextPrimary,
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Speak with the world",
                            color = TextSecondary,
                            fontSize = 16.sp
                        )
                    }
                }
                1 -> {
                    // Tutorial Screen
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(120.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.05f))
                                .border(1.dp, Color.White.copy(alpha = 0.1f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = null,
                                tint = AccentCoral,
                                modifier = Modifier.size(60.dp)
                            )
                        }
                        Spacer(Modifier.height(40.dp))
                        Text(
                            text = "100% Offline & Private",
                            color = TextPrimary,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = "All translations are powered by on-device AI. Your voice never leaves your phone.",
                            color = TextSecondary,
                            fontSize = 16.sp,
                            textAlign = TextAlign.Center,
                            lineHeight = 24.sp
                        )
                        Spacer(Modifier.height(48.dp))
                        Button(
                            onClick = { coroutineScope.launch { pagerState.animateScrollToPage(2) } },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentCoral),
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                        ) {
                            Text("Next", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }
                2 -> {
                    // Permissions Screen
                    var hasMicPermission by remember {
                        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                    }
                    val permissionLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission()
                    ) { isGranted ->
                        hasMicPermission = isGranted
                        if (isGranted) {
                            coroutineScope.launch { pagerState.animateScrollToPage(4) }
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(120.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.05f))
                                .border(1.dp, Color.White.copy(alpha = 0.1f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = null,
                                tint = AccentCoral,
                                modifier = Modifier.size(60.dp)
                            )
                        }
                        Spacer(Modifier.height(40.dp))
                        Text(
                            text = "Microphone Access",
                            color = TextPrimary,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = "SyncDialect needs access to your microphone to translate your speech in real-time.",
                            color = TextSecondary,
                            fontSize = 16.sp,
                            textAlign = TextAlign.Center,
                            lineHeight = 24.sp
                        )
                        Spacer(Modifier.height(48.dp))
                        
                        if (hasMicPermission) {
                            Button(
                                onClick = { coroutineScope.launch { pagerState.animateScrollToPage(3) } },
                                colors = ButtonDefaults.buttonColors(containerColor = AccentCoral),
                                shape = RoundedCornerShape(24.dp),
                                modifier = Modifier.fillMaxWidth().height(56.dp)
                            ) {
                                Text("Continue", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        } else {
                            Button(
                                onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                                colors = ButtonDefaults.buttonColors(containerColor = AccentCoral),
                                shape = RoundedCornerShape(24.dp),
                                modifier = Modifier.fillMaxWidth().height(56.dp)
                            ) {
                                Text("Allow Microphone", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }
                    }
                }
                3 -> {
                    TutorialScreen(onComplete = {
                        coroutineScope.launch { pagerState.animateScrollToPage(4) }
                    })
                }
                4 -> {
                    // Language Selection Screen
                    var showSourceDialog by remember { mutableStateOf(false) }
                    var showTargetDialog by remember { mutableStateOf(false) }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 64.dp, start = 24.dp, end = 24.dp, bottom = 32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Language,
                            contentDescription = null,
                            tint = AccentCoral,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(Modifier.height(24.dp))
                        Text(
                            text = "What languages do you want to translate?",
                            color = TextPrimary,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            lineHeight = 36.sp
                        )
                        Spacer(Modifier.height(40.dp))
                        
                        Text("My Language", color = TextSecondary, fontSize = 14.sp)
                        Spacer(Modifier.height(8.dp))
                        LanguageSelectorCard(
                            selectedLanguage = selectedSource?.first,
                            onClick = { showSourceDialog = true }
                        )

                        Spacer(Modifier.height(24.dp))

                        Text("Language to Translate", color = TextSecondary, fontSize = 14.sp)
                        Spacer(Modifier.height(8.dp))
                        LanguageSelectorCard(
                            selectedLanguage = selectedTarget?.first,
                            onClick = { showTargetDialog = true }
                        )
                        
                        Spacer(Modifier.weight(1f))
                        
                        Button(
                            onClick = { 
                                if (selectedSource != null && selectedTarget != null) {
                                    onComplete(selectedSource!!.first, selectedTarget!!.first, selectedTarget!!.second)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AccentCoral,
                                disabledContainerColor = AccentCoral.copy(alpha = 0.3f)
                            ),
                            shape = RoundedCornerShape(24.dp),
                            enabled = selectedSource != null && selectedTarget != null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                        ) {
                            Text("Get Started", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }

                    // Dialogs for selection
                    if (showSourceDialog) {
                        LanguagePickerDialog(
                            languages = downloadedLanguages,
                            onDismiss = { showSourceDialog = false },
                            onSelect = { 
                                selectedSource = it
                                showSourceDialog = false
                            }
                        )
                    }
                    if (showTargetDialog) {
                        LanguagePickerDialog(
                            languages = downloadedLanguages,
                            onDismiss = { showTargetDialog = false },
                            onSelect = { 
                                selectedTarget = it
                                showTargetDialog = false
                            }
                        )
                    }
                }
            }
        }
        
        // Dot Indicators for pages 1, 2, 3
        if (pagerState.currentPage > 0) {
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                repeat(3) { index ->
                    val color = if (pagerState.currentPage - 1 == index) AccentCoral else Color.White.copy(alpha = 0.2f)
                    Box(
                        modifier = Modifier
                            .padding(4.dp)
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(color)
                    )
                }
            }
        }
    }
}

@Composable
fun LanguageSelectorCard(selectedLanguage: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .padding(20.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = selectedLanguage ?: "Select a language...",
            color = if (selectedLanguage != null) Color.White else Color.Gray,
            fontSize = 18.sp
        )
        if (selectedLanguage != null) {
            Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFFE8593C))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguagePickerDialog(
    languages: List<Pair<String, String>>,
    onDismiss: () -> Unit,
    onSelect: (Pair<String, String>) -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFF1E1E1E)) {
        LazyColumn(modifier = Modifier.padding(24.dp)) {
            item {
                Text("Select Language", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))
            }
            items(languages) { langPair ->
                Text(
                    text = langPair.first,
                    color = Color.White,
                    fontSize = 18.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(langPair) }
                        .padding(vertical = 16.dp)
                )
            }
        }
    }
}
