import sys

def main():
    lines = open('temp_settings.txt', encoding='utf-8').readlines()
    cleaned_lines = []
    for line in lines:
        if ":" in line:
            parts = line.split(":", 1)
            if parts[0].strip().isdigit():
                cleaned_lines.append(parts[1][1:]) # strip the space after colon
            else:
                cleaned_lines.append(line)
        else:
            cleaned_lines.append(line)

    content = "".join(cleaned_lines)
    
    start_str = "        // 1. GEMMA OFFLINE ENGINE CARD"
    end_str = "        // 2. ML KIT LANGUAGE PACKS"
    
    start_idx = content.find(start_str)
    end_idx = content.find(end_str)
    
    if start_idx == -1 or end_idx == -1:
        print("Could not find card bounds")
        return
        
    proper_gemma_card = """        // 1. GEMMA OFFLINE ENGINE CARD
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
                    Box(
                        modifier = Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF1A1A1A))
                    ) {
                        Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(downloadProgress.value).background(AccentCoral))
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Downloading: ${(downloadProgress.value * 100).toInt()}%", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                } else {
                    Button(
                        onClick = {
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

"""
    content = content[:start_idx] + proper_gemma_card + content[end_idx:]


    adv_start_str = "        // 6. ADVANCED TUNING CARD"
    adv_start_idx = content.find(adv_start_str)
    
    if adv_start_idx == -1:
        print("Could not find Advanced Tuning card start")
        return
            
    content = content[:adv_start_idx]

    new_bottom = """        // 6. ADVANCED TUNING CARD
        val prefs = remember { AppPreferences(mainActivityContext) }
        var advancedTuningExpanded by remember { mutableStateOf(false) }
        var vadFrames by remember { mutableStateOf(prefs.vadWaitFrames.toFloat()) }
        var temp by remember { mutableStateOf(prefs.modelTemperature) }
        var topK by remember { mutableStateOf(prefs.modelTopK.toFloat()) }

        ElevatedCard(
            colors = CardDefaults.elevatedCardColors(containerColor = CardBackground),
            modifier = Modifier.fillMaxWidth().border(1.dp, CardBorder, RoundedCornerShape(16.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { advancedTuningExpanded = !advancedTuningExpanded }.padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Advanced Tuning", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                    Icon(
                        if (advancedTuningExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, 
                        contentDescription = null, 
                        tint = TextSecondary
                    )
                }
                
                if (advancedTuningExpanded) {
                    Spacer(Modifier.height(16.dp))

                    Text("Translation Speed (VAD Delay)", color = TextPrimary, fontSize = 14.sp)
                    Box(modifier = Modifier.fillMaxWidth()) {
                        // Default mark at 25 (range 10..50) -> fraction = 15/40 = 0.375
                        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(48.dp), contentAlignment = Alignment.CenterStart) {
                            Box(modifier = Modifier.fillMaxHeight(0.4f).fillMaxWidth(0.375f).wrapContentWidth(Alignment.End)) {
                                Box(modifier = Modifier.width(2.dp).fillMaxHeight().background(Color.Gray.copy(alpha=0.5f)))
                            }
                        }
                        Slider(
                            value = vadFrames,
                            onValueChange = { 
                                vadFrames = it 
                                prefs.vadWaitFrames = it.toInt()
                            },
                            valueRange = 10f..50f,
                            steps = 39,
                            colors = SliderDefaults.colors(thumbColor = AccentCoral, activeTrackColor = AccentCoral)
                        )
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${String.format(java.util.Locale.US, "%.1f", vadFrames / 25f)}s silence before translating", color = TextSecondary, fontSize = 12.sp)
                        Text("Default: 1.0s", color = TextSecondary, fontSize = 12.sp)
                    }

                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider(color = CardBorder, thickness = 1.dp)
                    Spacer(Modifier.height(16.dp))

                    Text("Model Temperature (Creativity)", color = TextPrimary, fontSize = 14.sp)
                    Box(modifier = Modifier.fillMaxWidth()) {
                        // Default mark at 0.1 (range 0..1) -> fraction = 0.1
                        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(48.dp), contentAlignment = Alignment.CenterStart) {
                            Box(modifier = Modifier.fillMaxHeight(0.4f).fillMaxWidth(0.1f).wrapContentWidth(Alignment.End)) {
                                Box(modifier = Modifier.width(2.dp).fillMaxHeight().background(Color.Gray.copy(alpha=0.5f)))
                            }
                        }
                        Slider(
                            value = temp,
                            onValueChange = { 
                                temp = it 
                                prefs.modelTemperature = it
                            },
                            valueRange = 0.0f..1.0f,
                            colors = SliderDefaults.colors(thumbColor = AccentCoral, activeTrackColor = AccentCoral)
                        )
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Current: ${String.format(java.util.Locale.US, "%.2f", temp)}", color = TextSecondary, fontSize = 12.sp)
                        Text("Default: 0.10", color = TextSecondary, fontSize = 12.sp)
                    }

                    Spacer(Modifier.height(16.dp))

                    Text("Top-K (Vocabulary Breadth)", color = TextPrimary, fontSize = 14.sp)
                    Box(modifier = Modifier.fillMaxWidth()) {
                        // Default mark at 1 (range 1..40) -> fraction = 0.0
                        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(48.dp), contentAlignment = Alignment.CenterStart) {
                            Box(modifier = Modifier.fillMaxHeight(0.4f).fillMaxWidth(0.0f).wrapContentWidth(Alignment.End)) {
                                Box(modifier = Modifier.width(2.dp).fillMaxHeight().background(Color.Gray.copy(alpha=0.5f)))
                            }
                        }
                        Slider(
                            value = topK,
                            onValueChange = { 
                                topK = it 
                                prefs.modelTopK = it.toInt()
                            },
                            valueRange = 1f..40f,
                            steps = 38,
                            colors = SliderDefaults.colors(thumbColor = AccentCoral, activeTrackColor = AccentCoral)
                        )
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Current: ${topK.toInt()}", color = TextSecondary, fontSize = 12.sp)
                        Text("Default: 1", color = TextSecondary, fontSize = 12.sp)
                    }
                }
            }
        }
        
        Spacer(Modifier.height(32.dp))
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Powered by Gemma", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text("Google Gemma 2B IT (Instruction Tuned)", color = TextSecondary, fontSize = 10.sp)
        }
        Spacer(Modifier.height(140.dp))
    }

    if (showVoiceSettings) {
        VoiceSettingsDialog(targetLanguage.value, targetLanguageTag.value, ttsEngine, forceOfflineTts, AccentCoral) { showVoiceSettings = false }
    }
}
"""
    content += new_bottom
    
    with open('app/src/main/java/com/syncdialect/app/SettingsScreen.kt', 'w', encoding='utf-8') as f:
        f.write(content)
        
    print("Repaired SettingsScreen.kt")

if __name__ == "__main__":
    main()
