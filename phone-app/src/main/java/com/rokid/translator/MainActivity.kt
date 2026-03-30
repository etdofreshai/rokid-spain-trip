package com.rokid.translator

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rokid.translator.data.LanguagePair
import com.rokid.translator.service.BluetoothConnectionState
import com.rokid.translator.service.ServiceBridge
import com.rokid.translator.ui.theme.RokidTranslatorTheme
import com.rokid.translator.viewmodel.TranslatorUiState
import com.rokid.translator.viewmodel.TranslatorViewModel

class MainActivity : ComponentActivity() {
    
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkPermissions()
        
        setContent {
            RokidTranslatorTheme {
                val viewModel: TranslatorViewModel = viewModel()
                val uiState by viewModel.uiState.collectAsState()
                
                // Auto-start service
                LaunchedEffect(Unit) {
                    if (!uiState.isServiceRunning) viewModel.startService()
                }
                
                TranslatorScreen(
                    uiState = uiState,
                    onLanguagePairChange = viewModel::setLanguagePair,
                    onCloudToggle = viewModel::setUseCloudTranslation,
                    onApiKeyChange = viewModel::setGeminiApiKey,
                    onToggleSettings = viewModel::toggleSettings,
                    onClearHistory = viewModel::clearTranslations,
                    onToggleStt = viewModel::toggleStt
                )
            }
        }
    }
    
    private fun checkPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.addAll(listOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            ))
        }
        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isNotEmpty()) permissionLauncher.launch(notGranted.toTypedArray())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslatorScreen(
    uiState: TranslatorUiState,
    onLanguagePairChange: (LanguagePair) -> Unit,
    onCloudToggle: (Boolean) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onToggleSettings: () -> Unit,
    onClearHistory: () -> Unit,
    onToggleStt: () -> Unit = {}
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Rokid Translator", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onClearHistory) {
                        Icon(Icons.Default.DeleteSweep, "Clear")
                    }
                    IconButton(onClick = onToggleSettings) {
                        Icon(Icons.Default.Settings, "Settings")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // Connection status
            ConnectionStatusCard(uiState)
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Language pair selector
            LanguagePairSelector(
                selected = uiState.languagePair,
                onChange = onLanguagePairChange
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Status
            StatusCard(uiState)
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Settings panel
            AnimatedVisibility(visible = uiState.showSettings) {
                SettingsPanel(
                    geminiApiKey = uiState.geminiApiKey,
                    useCloud = uiState.useCloudTranslation,
                    onApiKeyChange = onApiKeyChange,
                    onCloudToggle = onCloudToggle
                )
            }
            
            // Mic toggle button
            Button(
                onClick = onToggleStt,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (uiState.isSttActive) Color(0xFFF44336) else MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    if (uiState.isSttActive) Icons.Default.MicOff else Icons.Default.Mic,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (uiState.isSttActive) "Stop Listening" else "Start Listening")
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Translation feed
            Text(
                "Translations",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            
            val listState = rememberLazyListState()
            LaunchedEffect(uiState.translations.size) {
                if (uiState.translations.isNotEmpty())
                    listState.animateScrollToItem(uiState.translations.size - 1)
            }
            
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(uiState.translations) { result ->
                    TranslationCard(result)
                }
                
                if (uiState.translations.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "Translations will appear here\nSpeak nearby — phone mic is listening",
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ConnectionStatusCard(uiState: TranslatorUiState) {
    val (color, text) = when (uiState.bluetoothState) {
        BluetoothConnectionState.CONNECTED -> Color(0xFF4CAF50) to "Connected: ${uiState.connectedDeviceName ?: "Glasses"}"
        BluetoothConnectionState.LISTENING -> Color(0xFFFFC107) to "Waiting for glasses..."
        BluetoothConnectionState.CONNECTING -> Color(0xFFFFC107) to "Connecting..."
        BluetoothConnectionState.DISCONNECTED -> Color(0xFFF44336) to "Disconnected"
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.1f))
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(text, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
fun LanguagePairSelector(selected: LanguagePair, onChange: (LanguagePair) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        LanguagePair.entries.forEach { pair ->
            FilterChip(
                selected = pair == selected,
                onClick = { onChange(pair) },
                label = {
                    Text(
                        pair.displayName,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun StatusCard(uiState: TranslatorUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (uiState.isListening) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = Color(0xFFF44336)
                )
                Spacer(modifier = Modifier.width(12.dp))
            }
            Text(uiState.statusText)
        }
    }
}

@Composable
fun SettingsPanel(
    geminiApiKey: String,
    useCloud: Boolean,
    onApiKeyChange: (String) -> Unit,
    onCloudToggle: (Boolean) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Settings", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(12.dp))
            
            var keyInput by remember(geminiApiKey) { mutableStateOf(geminiApiKey) }
            OutlinedTextField(
                value = keyInput,
                onValueChange = { keyInput = it; onApiKeyChange(it) },
                label = { Text("Gemini API Key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Cloud translation (Gemini)")
                Switch(checked = useCloud, onCheckedChange = onCloudToggle)
            }
        }
    }
}

@Composable
fun TranslationCard(result: ServiceBridge.TranslationResult) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Original text with detected language
            Text(
                "[${result.detectedLanguage.uppercase()}] ${result.originalText}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            
            Spacer(modifier = Modifier.height(6.dp))
            
            // Translation result
            val translation = result.cloudTranslation ?: result.localTranslation ?: ""
            Text(
                translation,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            
            // Show if cloud vs local
            if (result.cloudTranslation != null) {
                Text("☁️ Gemini", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
            } else if (result.localTranslation != null) {
                Text("📱 ML Kit", fontSize = 10.sp, color = MaterialTheme.colorScheme.secondary)
            }
        }
    }
}
