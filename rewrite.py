import re

def process_file():
    with open('app/src/main/java/com/example/auravoice/MainActivity.kt', 'r', encoding='utf-8') as f:
        content = f.read()

    # 1. Update AuraVoiceApp layout
    content = content.replace(
        'var currentScreen by remember { mutableStateOf("Home") } // "Home", "History", "Saved", "Profile", "Settings", "Camera"',
        'var currentScreen by remember { mutableStateOf("Home") } // "Home", "Settings"'
    )
    
    # Remove Settings icon from top bar
    settings_icon = '''                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = TextPrimary,
                        modifier = Modifier.size(28.dp).clickable { 
                            if (isRecording.value) {
                                isRecording.value = false
                                audioRecorderHelper.stopRecording()
                            }
                            currentScreen = "Settings" 
                        }
                    )'''
    content = content.replace(settings_icon, '')
    
    # Remove Camera from screens
    content = content.replace('} else if (currentScreen == "Camera") {\n                    Box(modifier = Modifier.weight(1f)) { CameraScreen(onLangClick = { type -> editingLangType = type; showLangSheet = true }) }\n                ', '')

    # Update BottomControls usage
    old_bc_call = '''            BottomControls(
                isRecording = isRecording.value,
                activeTab = currentScreen,
                onTabSelected = { 
                    if (isRecording.value) {
                        isRecording.value = false
                        audioRecorderHelper.stopRecording()
                    }
                    currentScreen = it 
                },
                onMicToggle = {
                    if (isRecording.value) {
                        isRecording.value = false
                        audioRecorderHelper.stopRecording()
                    } else {
                        if (!isModelReady.value) {
                            currentScreen = "Settings"
                            return@BottomControls
                        }
                        isRecording.value = true
                        messages.clear()
                        messages.add(ChatMessage("", sourceLanguage.value, false))
                        audioRecorderHelper.startContinuousRecording { audioData ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                translationMutex.withLock {
                                    runOnUiThread {
                                        val lastIdx = messages.lastIndex
                                        if (lastIdx >= 0 && messages[lastIdx].text.isNotEmpty() && !messages[lastIdx].text.endsWith(" ")) {
                                            messages[lastIdx] = messages[lastIdx].copy(text = messages[lastIdx].text + " ")
                                        }
                                    }
                                    translationEngine.translateAudioStreaming(
                                        sourceLanguage.value, targetLanguage.value, audioData,
                                        onLanguageDetected = { },
                                        onTokenGenerated = processToken,
                                        onError = { err -> runOnUiThread { errorText.value = err; isRecording.value = false } }
                                    )
                                    runOnUiThread { ttsEngine.flush() }
                                }
                            }
                        }
                    }
                },
                onCameraClick = {
                    // Signal to CameraScreen to freeze/capture
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
            )'''
    
    new_bc_call = '''            BottomControls(
                isRecording = isRecording.value,
                activeTab = currentScreen,
                onTabSelected = { 
                    if (isRecording.value) {
                        isRecording.value = false
                        audioRecorderHelper.stopRecording()
                    }
                    currentScreen = it 
                },
                onMicToggle = {
                    if (isRecording.value) {
                        isRecording.value = false
                        audioRecorderHelper.stopRecording()
                    } else {
                        if (!isModelReady.value) {
                            currentScreen = "Settings"
                            return@BottomControls
                        }
                        isRecording.value = true
                        messages.clear()
                        messages.add(ChatMessage("", sourceLanguage.value, false))
                        audioRecorderHelper.startContinuousRecording { audioData ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                translationMutex.withLock {
                                    runOnUiThread {
                                        val lastIdx = messages.lastIndex
                                        if (lastIdx >= 0 && messages[lastIdx].text.isNotEmpty() && !messages[lastIdx].text.endsWith(" ")) {
                                            messages[lastIdx] = messages[lastIdx].copy(text = messages[lastIdx].text + " ")
                                        }
                                    }
                                    translationEngine.translateAudioStreaming(
                                        sourceLanguage.value, targetLanguage.value, audioData,
                                        onLanguageDetected = { },
                                        onTokenGenerated = processToken,
                                        onError = { err -> runOnUiThread { errorText.value = err; isRecording.value = false } }
                                    )
                                    runOnUiThread { ttsEngine.flush() }
                                }
                            }
                        }
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
            )'''
    content = content.replace(old_bc_call, new_bc_call)
    
    # Remove CameraScreen
    start_cs = content.find('    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)\n    @Composable\n    fun CameraScreen')
    if start_cs == -1: start_cs = content.find('    @Composable\n    fun CameraScreen')
    end_cs = content.find('    @Composable\n    fun BackgroundWaveform', start_cs)
    
    if start_cs != -1 and end_cs != -1:
        content = content[:start_cs] + content[end_cs:]
        
    # Remove BackgroundWaveform
    start_bw = content.find('    @Composable\n    fun BackgroundWaveform')
    end_bw = content.find('@Composable\nfun LanguagePill', start_bw)
    if start_bw != -1 and end_bw != -1:
        content = content[:start_bw] + content[end_bw:]
        
    # Update TranscriptList
    old_tl = '''        items(messages) { msg ->
            if (msg.text.isNotEmpty()) {
                TranscriptRow(
                    speakerLabel = if (msg.language == "English") "You (English):" else "Other ():", // Approximation
                    message = msg.text,
                    waveformOnStart = msg.language == "English"
                )
            }
        }'''
    new_tl = '''        items(messages) { msg ->
            TranscriptRow(
                speakerLabel = if (msg.language == "English") "You (English):" else "Other ():", // Approximation
                message = msg.text,
                waveformOnStart = msg.language == "English"
            )
        }'''
    content = content.replace(old_tl, new_tl)

    # Update TranscriptRow
    old_tr = '''        Spacer(Modifier.height(8.dp))
        Text(text = message, color = TextPrimary, fontSize = 22.sp, lineHeight = 32.sp, fontWeight = FontWeight.Normal)
    }
}'''
    new_tr = '''        Spacer(Modifier.height(8.dp))
        if (message.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "...", color = TextPrimary.copy(alpha = 0.5f), fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
        } else {
            Text(text = message, color = TextPrimary, fontSize = 22.sp, lineHeight = 32.sp, fontWeight = FontWeight.Normal)
        }
    }
}'''
    content = content.replace(old_tr, new_tr)

    # Update BottomControls and NavBar
    old_bc_def = '''@Composable
fun BottomControls(isRecording: Boolean, activeTab: String, onTabSelected: (String) -> Unit, onMicToggle: () -> Unit, onCameraClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier.padding(horizontal = 24.dp, vertical = 24.dp)) {
        NavBar(activeTab = activeTab, onTabSelected = onTabSelected, modifier = Modifier.align(Alignment.BottomCenter))
        
        val isCameraTab = activeTab == "Camera"
        val scale by animateFloatAsState(targetValue = if (isRecording) 1.25f else 1f, animationSpec = spring(dampingRatio = 0.5f, stiffness = 400f))
        
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(y = (-16).dp)
                .size(72.dp)
                .scale(scale)
                .background(if (isCameraTab) Color(0xFF333333) else AccentCoral, CircleShape)
                .clickable { if (isCameraTab) onCameraClick() else onMicToggle() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isCameraTab) Icons.Default.CameraAlt else (if (isRecording) Icons.Default.Stop else Icons.Default.Mic),
                contentDescription = if (isCameraTab) "Camera" else "Mic",
                tint = Color.White,
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

@Composable
fun NavBar(activeTab: String, onTabSelected: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF14141C).copy(alpha = 0.8f), RoundedCornerShape(32.dp))
            .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(32.dp))
            .padding(vertical = 12.dp, horizontal = 20.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        NavItem(icon = Icons.Default.Home, label = "Home", isSelected = activeTab == "Home", onClick = { onTabSelected("Home") })
        NavItem(icon = Icons.Default.History, label = "History", isSelected = activeTab == "History", onClick = { onTabSelected("History") })
        Spacer(Modifier.width(64.dp)) // space for mic
        NavItem(icon = Icons.Default.BookmarkBorder, label = "Saved", isSelected = activeTab == "Saved", onClick = { onTabSelected("Saved") })
        NavItem(icon = Icons.Default.Person, label = "Profile", isSelected = activeTab == "Profile", onClick = { onTabSelected("Profile") })
    }
}'''
    new_bc_def = '''@Composable
fun BottomControls(isRecording: Boolean, activeTab: String, onTabSelected: (String) -> Unit, onMicToggle: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier.padding(horizontal = 24.dp, vertical = 24.dp)) {
        NavBar(activeTab = activeTab, onTabSelected = onTabSelected, modifier = Modifier.align(Alignment.BottomCenter))
        
        val scale by animateFloatAsState(targetValue = if (isRecording) 1.25f else 1f, animationSpec = spring(dampingRatio = 0.5f, stiffness = 400f))
        
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(y = (-16).dp)
                .size(72.dp)
                .scale(scale)
                .background(AccentCoral, CircleShape)
                .clickable { onMicToggle() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                contentDescription = "Mic",
                tint = Color.White,
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

@Composable
fun NavBar(activeTab: String, onTabSelected: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF14141C).copy(alpha = 0.8f), RoundedCornerShape(32.dp))
            .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(32.dp))
            .padding(vertical = 12.dp, horizontal = 20.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        NavItem(icon = Icons.Default.Home, label = "Home", isSelected = activeTab == "Home", onClick = { onTabSelected("Home") })
        Spacer(Modifier.width(64.dp)) // space for mic
        NavItem(icon = Icons.Default.Settings, label = "Settings", isSelected = activeTab == "Settings", onClick = { onTabSelected("Settings") })
    }
}'''
    content = content.replace(old_bc_def, new_bc_def)

    # Finally, remove SettingsScreen and VoiceSettingsDialog since they were inside MainActivity and replace with new ones
    # But wait, in the clean repo, they are not there! The user had NOT added SettingsScreen yet in the commit.
    # So I will append SettingsScreen directly.
    # Where? At the very end of the file. As top-level functions!
    
    with open('app/src/main/java/com/example/auravoice/MainActivity.kt', 'w', encoding='utf-8') as f:
        f.write(content)

process_file()
