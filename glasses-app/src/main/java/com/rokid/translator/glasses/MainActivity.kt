package com.rokid.translator.glasses

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rokid.translator.glasses.ui.theme.RokidGlassesTheme
import com.rokid.translator.glasses.viewmodel.TranslatorViewModel

class MainActivity : ComponentActivity() {

    private var vm: TranslatorViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            RokidGlassesTheme {
                val viewModel: TranslatorViewModel = viewModel(factory = TranslatorViewModel.Factory(this))
                vm = viewModel
                TranslatorScreen(
                    state = viewModel.state.collectAsState().value,
                    onToggle = { viewModel.toggleTranslation() }
                )
            }
        }
    }

    override fun onKeyDown(code: Int, event: KeyEvent?): Boolean {
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
            if (event?.repeatCount == 0) return true
        }
        return super.onKeyDown(code, event)
    }

    override fun onKeyUp(code: Int, event: KeyEvent?): Boolean {
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
            vm?.toggleTranslation()
            return true
        }
        return super.onKeyUp(code, event)
    }
}

@Composable
fun TranslatorScreen(state: com.rokid.translator.glasses.viewmodel.TranslatorState, onToggle: () -> Unit) {
    val debugPreview = remember(state.debugLog) {
        state.debugLog
            .lines()
            .filter { it.isNotBlank() }
            .takeLast(5)
            .joinToString("\n")
    }
    val translationDisplay = remember(state.translationAlternatives, state.translatedText) {
        when {
            state.translationAlternatives.isNotEmpty() -> state.translationAlternatives.joinToString(" / ")
            else -> state.translatedText
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(enabled = true, onClick = onToggle)
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {

            // Status bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier
                        .size(8.dp)
                        .background(
                            if (state.isConnected) Color(0xFF4CAF50) else Color(0xFFFF5722),
                            shape = CircleShape
                        ))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        if (state.isConnected) "CONNECTED" else "DISCONNECTED",
                        color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp
                    )
                }

                if (state.isListening) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier
                            .size(8.dp)
                            .background(Color.Red, shape = CircleShape))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("LISTENING", color = Color.Red, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Language pair
            if (state.detectedLanguage.isNotEmpty() || state.targetLanguage.isNotEmpty()) {
                Text(
                    state.detectedLanguage + " -> " + state.targetLanguage,
                    color = Color(0xFF4CAF50), fontSize = 12.sp, fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (state.isConnected || state.sourceText.isNotEmpty() || translationDisplay.isNotEmpty()) {
                TranslationPane(
                    title = "ORIGINAL",
                    text = state.sourceText.ifBlank { "Waiting for speech..." },
                    titleColor = Color(0xFF9E9E9E),
                    textColor = Color.White.copy(alpha = 0.82f),
                    textSize = 17.sp,
                    lineHeight = 22.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(92.dp)
                )
                Spacer(modifier = Modifier.height(10.dp))
                TranslationPane(
                    title = when {
                        state.translationAlternatives.size > 1 -> "TRANSLATION OPTIONS"
                        state.isTemporaryResult -> "TRANSLATING"
                        else -> "TRANSLATION"
                    },
                    text = translationDisplay.ifBlank { "Waiting for translation..." },
                    titleColor = Color(0xFF4CAF50),
                    textColor = Color.White,
                    textSize = 22.sp,
                    lineHeight = 28.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(144.dp)
                )
                Spacer(modifier = Modifier.height(10.dp))
            }

            // Debug log
            if (debugPreview.isNotEmpty()) {
                Text("-- DEBUG --", color = Color(0xFF666666), fontSize = 10.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    debugPreview,
                    color = Color(0xFF888888), fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 12.sp,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.weight(1f))
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }

            // Bottom hint
            Text(
                state.status,
                color = Color.White.copy(alpha = 0.4f), fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            )
        }
    }
}

@Composable
private fun TranslationPane(
    title: String,
    text: String,
    titleColor: Color,
    textColor: Color,
    textSize: androidx.compose.ui.unit.TextUnit,
    lineHeight: androidx.compose.ui.unit.TextUnit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = Color.White.copy(alpha = 0.06f),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                title,
                color = titleColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text,
                    color = textColor,
                    fontSize = textSize,
                    lineHeight = lineHeight,
                    style = TextStyle(
                        platformStyle = PlatformTextStyle(
                            includeFontPadding = true
                        )
                    ),
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            if (text.isBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}
