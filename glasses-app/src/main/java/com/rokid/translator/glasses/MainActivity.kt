package com.rokid.translator.glasses

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rokid.translator.glasses.ui.theme.RokidGlassesTheme
import com.rokid.translator.glasses.viewmodel.GlassesViewModel

class MainActivity : ComponentActivity() {
    
    private var glassesViewModel: GlassesViewModel? = null
    
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        
        checkPermissions()
        
        setContent {
            RokidGlassesTheme {
                val vm: GlassesViewModel = viewModel(factory = GlassesViewModel.Factory(this))
                glassesViewModel = vm
                GlassesTranslatorScreen(viewModel = vm)
            }
        }
    }
    
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val vm = glassesViewModel ?: return super.onKeyDown(keyCode, event)
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (event?.repeatCount == 0) true
                else super.onKeyDown(keyCode, event)
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }
    
    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        val vm = glassesViewModel ?: return super.onKeyUp(keyCode, event)
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                vm.toggleRecording()
                true
            }
            else -> super.onKeyUp(keyCode, event)
        }
    }
    
    private fun checkPermissions() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.addAll(listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN))
        }
        val needed = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }
}

@Composable
fun GlassesTranslatorScreen(viewModel: GlassesViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    var showDeviceSelector by remember { mutableStateOf(false) }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) {
                if (uiState.isConnected) {
                    viewModel.toggleRecording()
                } else {
                    viewModel.refreshPairedDevices()
                    showDeviceSelector = true
                }
            }
    ) {
        // Status indicator (top right)
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Connection dot
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        if (uiState.isConnected) Color(0xFF4CAF50) else Color(0xFFFF5722),
                        shape = androidx.compose.foundation.shape.CircleShape
                    )
            )
            Text(
                if (uiState.isConnected) stringResource(R.string.connected)
                else stringResource(R.string.tap_to_connect),
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 12.sp
            )
            
            // Recording indicator
            AnimatedVisibility(visible = uiState.isListening) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(Color.Red, shape = androidx.compose.foundation.shape.CircleShape)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("REC", color = Color.Red, fontSize = 12.sp)
                }
            }
        }
        
        // Main display area
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AnimatedVisibility(visible = uiState.isProcessing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    color = Color(0xFF4CAF50),
                    strokeWidth = 3.dp
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Translation text - large and readable
            AnimatedContent(
                targetState = uiState.displayText,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "display"
            ) { text ->
                Text(
                    text = text,
                    color = Color.White,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    lineHeight = 36.sp,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        
        // Hint text
        Text(
            text = uiState.hintText,
            color = Color.White.copy(alpha = 0.4f),
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 20.dp)
        )
        
        // Device selector
        if (showDeviceSelector) {
            AlertDialog(
                onDismissRequest = { showDeviceSelector = false },
                containerColor = Color(0xFF1A1A1A),
                title = { Text(stringResource(R.string.select_phone), color = Color.White, fontWeight = FontWeight.Bold) },
                text = {
                    if (uiState.availableDevices.isEmpty()) {
                        Text(stringResource(R.string.no_paired_devices), color = Color.White.copy(alpha = 0.7f))
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            uiState.availableDevices.forEach { device ->
                                @Suppress("MissingPermission")
                                val name = device.name ?: stringResource(R.string.unknown_device)
                                Surface(
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        viewModel.connectToDevice(device)
                                        showDeviceSelector = false
                                    },
                                    color = Color(0xFF2A2A2A),
                                    shape = MaterialTheme.shapes.small
                                ) {
                                    Text(name, color = Color.White, fontSize = 16.sp, modifier = Modifier.padding(16.dp))
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showDeviceSelector = false }) {
                        Text(stringResource(R.string.cancel), color = Color(0xFF4CAF50))
                    }
                }
            )
        }
    }
}
