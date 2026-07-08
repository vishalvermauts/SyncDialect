import re

with open('app/src/main/java/com/example/auravoice/MainActivity.kt', 'r', encoding='utf-8') as f:
    content = f.read()

# 1. Update AuraVoiceApp layout
old_nav = 'var currentScreen by remember { mutableStateOf("Home") } // "Home", "History", "Saved", "Profile", "Settings", "Camera"'
new_nav = 'var currentScreen by remember { mutableStateOf("Home") } // "Home", "Settings"'
content = content.replace(old_nav, new_nav)

# 2. Update TranscriptRow for zero delay bubble
old_tr = '''        Spacer(Modifier.height(8.dp))
        Text(text = message, color = TextPrimary, fontSize = 22.sp, lineHeight = 32.sp, fontWeight = FontWeight.Normal)'''
new_tr = '''        Spacer(Modifier.height(8.dp))
        if (message.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "...", color = TextPrimary.copy(alpha = 0.5f), fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
        } else {
            Text(text = message, color = TextPrimary, fontSize = 22.sp, lineHeight = 32.sp, fontWeight = FontWeight.Normal)
        }'''
content = content.replace(old_tr, new_tr)

# 3. NavBar
old_navbar = '''@Composable
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
new_navbar = '''@Composable
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
content = content.replace(old_navbar, new_navbar)

# 4. Remove Camera tab from BottomControls
old_bc = '''        val isCameraTab = activeTab == "Camera"
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
        }'''
new_bc = '''        val scale by animateFloatAsState(targetValue = if (isRecording) 1.25f else 1f, animationSpec = spring(dampingRatio = 0.5f, stiffness = 400f))
        
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
        }'''
content = content.replace(old_bc, new_bc)

# 5. Remove onCameraClick param
content = content.replace('onCameraClick: () -> Unit, ', '')
content = content.replace('''                onCameraClick = {
                    // Signal to CameraScreen to freeze/capture
                },
''', '')

# 6. Remove Camera settings icon
old_settings = '''                    Icon(
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
content = content.replace(old_settings, '')

# 7. Remove Camera from else if chain
old_else_if = '''            } else if (currentScreen == "Camera") {
                Box(modifier = Modifier.weight(1f)) { CameraScreen(onLangClick = { type -> editingLangType = type; showLangSheet = true }) }
            }'''
content = content.replace(old_else_if, '')

# 8. Remove CameraScreen block
import re
content = re.sub(r'@androidx\.annotation\.OptIn.*?fun CameraScreen.*?\} \}', '', content, flags=re.DOTALL)
content = re.sub(r'@Composable\s*fun CameraPreview.*?\}\n\}', '', content, flags=re.DOTALL)

with open('app/src/main/java/com/example/auravoice/MainActivity.kt', 'w', encoding='utf-8') as f:
    f.write(content)
