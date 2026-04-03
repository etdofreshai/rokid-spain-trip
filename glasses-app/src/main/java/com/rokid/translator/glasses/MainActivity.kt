package com.rokid.translator.glasses

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rokid.translator.glasses.ui.theme.RokidGlassesTheme
import com.rokid.translator.glasses.viewmodel.TranslationFeedEntry
import com.rokid.translator.glasses.viewmodel.TranslationMode
import com.rokid.translator.glasses.viewmodel.TranslatorViewModel

class MainActivity : ComponentActivity() {

    companion object {
        private const val WIFI_LONG_PRESS_MS = 700L
    }

    private var vm: TranslatorViewModel? = null
    private var centerKeyDownAt = 0L

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
                    onToggle = { viewModel.toggleTranslation() },
                    onOpenWifi = { openWifiSettings() }
                )
            }
        }
    }

    override fun onKeyDown(code: Int, event: KeyEvent?): Boolean {
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
            if (event?.repeatCount == 0) {
                centerKeyDownAt = event.downTime
                return true
            }
        }
        return super.onKeyDown(code, event)
    }

    override fun onKeyUp(code: Int, event: KeyEvent?): Boolean {
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
            val downAt = if (centerKeyDownAt > 0L) centerKeyDownAt else event?.downTime ?: 0L
            val heldFor = (event?.eventTime ?: 0L) - downAt
            centerKeyDownAt = 0L
            if (heldFor >= WIFI_LONG_PRESS_MS) {
                openWifiSettings()
            } else {
                vm?.toggleTranslation()
            }
            return true
        }
        return super.onKeyUp(code, event)
    }

    private fun openWifiSettings() {
        val intents = listOf(
            Intent(Settings.ACTION_WIFI_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )

        for (intent in intents) {
            runCatching {
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            }
        }
    }
}

@Composable
fun TranslatorScreen(
    state: com.rokid.translator.glasses.viewmodel.TranslatorState,
    onToggle: () -> Unit,
    onOpenWifi: () -> Unit,
) {
    val debugPreview = remember(state.debugLog) {
        state.debugLog
            .lines()
            .filter { it.isNotBlank() }
            .takeLast(15)
            .joinToString("\n")
    }
    val languageDisplay = remember(state.detectedLanguage, state.targetLanguage, state.configuredPairLabel) {
        when {
            state.mode == TranslationMode.ONLINE &&
                (state.detectedLanguage.isNotEmpty() || state.targetLanguage.isNotEmpty()) ->
                state.detectedLanguage + " -> " + state.targetLanguage
            else -> state.configuredPairLabel
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(enabled = true, onClick = onToggle)
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp)) {

            // Status bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusBadge(
                        active = state.isConnected,
                        activeLabel = "CONNECTED",
                        inactiveLabel = "DISCONNECTED",
                        activeColor = Color(0xFF4CAF50),
                        inactiveColor = Color(0xFFFF5722)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    StatusBadge(
                        active = state.isOnline,
                        activeLabel = "ONLINE",
                        inactiveLabel = "OFFLINE",
                        activeColor = Color(0xFF00BCD4),
                        inactiveColor = Color(0xFFFFC107)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "WI-FI",
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { onOpenWifi() }
                    )
                }

                if (state.isListening) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier
                            .size(7.dp)
                            .background(Color.Red, shape = CircleShape))
                        Spacer(modifier = Modifier.width(3.dp))
                        Text("LISTENING", color = Color.Red, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            if (state.mode != TranslationMode.DISABLED || languageDisplay.isNotEmpty()) {
                Text(
                    text = listOf(state.mode.label, languageDisplay)
                        .filter { it.isNotBlank() }
                        .joinToString("  "),
                    color = Color(0xFF4CAF50),
                    fontSize = 5.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }

            if (state.mode == TranslationMode.DISABLED) {
                ModeMenuPane(
                    counterpartLanguage = state.counterpartLanguageLabel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.42f)
                )
            } else {
                FeedPane(
                    entries = state.feedEntries,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.42f)
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.50f),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                if (debugPreview.isNotEmpty()) {
                    Column {
                        Text("-- DEBUG --", color = Color(0xFF555555), fontSize = 6.sp)
                        Spacer(modifier = Modifier.height(1.dp))
                        Text(
                            debugPreview,
                            color = Color(0xFF777777),
                            fontSize = 5.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 6.sp,
                            maxLines = 15,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Text(
                    state.status,
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 6.sp,
                    textAlign = TextAlign.Left,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 1.dp)
                )
            }
        }
    }
}

@Composable
private fun StatusBadge(
    active: Boolean,
    activeLabel: String,
    inactiveLabel: String,
    activeColor: Color,
    inactiveColor: Color,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(if (active) activeColor else inactiveColor, shape = CircleShape)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = if (active) activeLabel else inactiveLabel,
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun ModeMenuPane(
    counterpartLanguage: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(top = 2.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        Text(
            text = "Tap to cycle modes",
            color = Color(0xFF7FB7C9),
            fontSize = 3.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        ModeMenuLine("1", "Disabled", "Stop listening and show this menu")
        ModeMenuLine("2", "Local", "$counterpartLanguage -> English")
        ModeMenuLine("3", "Online", "Requires Wi-Fi / hotspot")
    }
}

@Composable
private fun FeedPane(
    entries: List<TranslationFeedEntry>,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val newestFirst = remember(entries) { entries.asReversed() }
    val latestEntry = newestFirst.firstOrNull()
    val historyEntries = newestFirst.drop(1).take(15)

    LaunchedEffect(entries.size, latestEntry?.translatedText, latestEntry?.pronunciationText) {
        if (latestEntry != null) {
            scrollState.animateScrollTo(0)
        }
    }

    Column(
        modifier = modifier
            .verticalScroll(scrollState)
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        if (entries.isEmpty()) {
            HeroFeedEntry(
                entry = TranslationFeedEntry(
                    resultId = null,
                    originalText = "Waiting for speech...",
                    translatedText = "Waiting for translation...",
                    pronunciationText = "-",
                    provider = "",
                    sourceLanguage = "",
                    targetLanguage = ""
                )
            )
        } else {
            latestEntry?.let { entry ->
                HeroFeedEntry(entry = entry)
            }
            if (historyEntries.isNotEmpty()) {
                Spacer(modifier = Modifier.height(0.dp))
            }
            historyEntries.forEach { entry ->
                CompactFeedEntry(entry = entry)
            }
        }
    }
}

@Composable
private fun HeroFeedEntry(
    entry: TranslationFeedEntry,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        FeedHeroLine(label = "heard", value = entry.originalText.ifBlank { "-"}, valueFontSize = 7.sp)
        FeedHeroLine(
            label = "trans",
            value = entry.translatedText.ifBlank { "-" },
            valueFontSize = 5.sp
        )
        if (entry.pronunciationText.isNotBlank()) {
            FeedHeroLine(
                label = "say",
                value = entry.pronunciationText,
                valueFontSize = 5.sp,
                valueColor = Color.White.copy(alpha = 0.72f)
            )
        }
    }
}

@Composable
private fun CompactFeedEntry(
    entry: TranslationFeedEntry,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 1.dp, top = 0.dp, bottom = 0.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        Text(
            text = buildString {
                if (entry.originalText.isNotBlank()) append(entry.originalText)
                if (entry.translatedText.isNotBlank()) {
                    if (isNotEmpty()) append(" / ")
                    append(entry.translatedText)
                }
            }.ifBlank { "-" },
            color = Color.White.copy(alpha = 0.68f),
            fontSize = 5.sp,
            lineHeight = 5.sp,
            fontFamily = FontFamily.Monospace,
            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
            softWrap = true
        )
    }
}

@Composable
private fun FeedHeroLine(
    label: String,
    value: String,
    valueFontSize: TextUnit = 11.sp,
    valueColor: Color = Color.White.copy(alpha = 0.9f),
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            color = Color(0xFF4CAF50),
            fontSize = 3.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Left,
            modifier = Modifier.width(24.dp)
        )
        Text(
            text = value,
            color = valueColor,
            fontSize = valueFontSize,
            lineHeight = (valueFontSize.value + 0.03f).sp,
            fontFamily = FontFamily.Monospace,
            style = TextStyle(
                platformStyle = PlatformTextStyle(includeFontPadding = false)
            ),
            textAlign = TextAlign.Left,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun ModeMenuLine(
    index: String,
    title: String,
    description: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Text(
            text = "$index  $title",
            color = Color(0xFF4CAF50),
            fontSize = 4.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = description,
            color = Color.White.copy(alpha = 0.82f),
            fontSize = 4.sp,
            lineHeight = 5.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun FeedLine(
    label: String,
    value: String,
    labelColor: Color = Color(0xFF4CAF50),
    valueColor: Color = Color.White.copy(alpha = 0.88f),
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "${label.padEnd(5)} ",
            color = labelColor,
            fontSize = 4.sp,
            lineHeight = 4.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = value,
            color = valueColor,
            fontSize = 4.sp,
            lineHeight = 4.sp,
            fontFamily = FontFamily.Monospace,
            style = TextStyle(
                platformStyle = PlatformTextStyle(includeFontPadding = false)
            ),
            modifier = Modifier.weight(1f)
        )
    }
}
