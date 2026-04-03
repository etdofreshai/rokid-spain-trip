package com.rokid.translator.glasses.viewmodel

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rokid.translator.glasses.BuildConfig
import com.rokid.translator.glasses.bridge.AssistBridge
import com.rokid.translator.glasses.network.NetworkMonitor
import com.rokid.translator.glasses.translation.CloudConversationTranslator
import com.rokid.translator.glasses.translation.ConversationLanguageDetector
import com.rokid.translator.glasses.translation.OnDeviceConversationTranslator
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class TranslationMode(val label: String) {
    DISABLED("Disabled"),
    LOCAL_SILENT("Local Silent"),
    LOCAL_TTS("Local TTS"),
    ONLINE("Online");
}

data class TranslatorState(
    val status: String = "Tap to connect",
    val mode: TranslationMode = TranslationMode.DISABLED,
    val counterpartLanguageLabel: String = "Italian",
    val sourceText: String = "",
    val translatedText: String = "",
    val translationAlternatives: List<String> = emptyList(),
    val pronunciationText: String = "",
    val translationProvider: String = "",
    val isTranslating: Boolean = false,
    val feedEntries: List<TranslationFeedEntry> = emptyList(),
    val configuredPairLabel: String = "Italian <-> English",
    val phoneModel: String = "",
    val detectedLanguage: String = "",
    val targetLanguage: String = "",
    val isOnline: Boolean = false,
    val isConnected: Boolean = false,
    val isListening: Boolean = false,
    val isTemporaryResult: Boolean = false,
    val silenceDelayMs: Long = 2000L,
    val debugLog: String = "",
)

data class TranslationFeedEntry(
    val resultId: Int?,
    val originalText: String = "",
    val translatedText: String = "",
    val pronunciationText: String = "",
    val provider: String = "",
    val sourceLanguage: String = "",
    val targetLanguage: String = "",
)

class TranslatorViewModel(private val context: Context) : ViewModel(), AssistBridge.Listener {

    companion object {
        private const val TAG = "TranslatorVM"
        private const val HISTORY_PREFS = "translation_history"
        private const val KEY_FEED = "feed_entries"
        private const val KEY_COUNTERPART_CODE = "counterpart_code"
        private const val KEY_COUNTERPART_LABEL = "counterpart_label"
        private const val KEY_PHONE_MODEL = "phone_model"
    }

    private val historyPrefs: SharedPreferences =
        context.getSharedPreferences(HISTORY_PREFS, Context.MODE_PRIVATE)

    private val bridge = AssistBridge(context)
    private val networkMonitor = NetworkMonitor(context)
    private val localTranslator = OnDeviceConversationTranslator()
    private val cloudTranslator = CloudConversationTranslator(
        apiKey = BuildConfig.OPENROUTER_API_KEY,
        model = BuildConfig.OPENROUTER_MODEL,
        siteUrl = BuildConfig.OPENROUTER_SITE_URL,
        appName = BuildConfig.OPENROUTER_APP_NAME,
    )
    private var activeResultId: Int? = null
    private var activeTranslationAlternatives: List<String> = emptyList()
    private var activeTranslationJob: Job? = null
    private var activeTranslationNonce = 0L
    private var silenceJob: Job? = null
    private var preferredCounterpartCode = historyPrefs.getString(KEY_COUNTERPART_CODE, "it") ?: "it"
    private var preferredCounterpartLabel = historyPrefs.getString(KEY_COUNTERPART_LABEL, "Italian") ?: "Italian"
    private var listeningStartedAtMs = 0L
    private var translationRequestedAtMs = 0L
    private var sceneReadyAtMs = 0L
    private var firstResultAtMs = 0L

    private val _state = MutableStateFlow(TranslatorState())
    val state: StateFlow<TranslatorState> = _state.asStateFlow()

    init {
        bridge.setListener(this)
        viewModelScope.launch { bridge.bind() }
        observeNetworkState()
        preloadPreferredModels()
        _state.update {
            it.copy(
                feedEntries = loadFeedHistory(),
                counterpartLanguageLabel = preferredCounterpartLabel,
                phoneModel = historyPrefs.getString(KEY_PHONE_MODEL, "") ?: "",
                configuredPairLabel = routeLabelForMode(it.mode),
                status = statusForMode(it.mode, connected = false, listening = false)
            )
        }
    }

    fun cycleSilenceDelay() {
        val options = longArrayOf(1000L, 1500L, 2000L, 3000L, 5000L)
        val current = _state.value.silenceDelayMs
        val nextIndex = (options.indexOf(current) + 1) % options.size
        _state.update { it.copy(silenceDelayMs = options[nextIndex]) }
        addLog("Silence delay: ${options[nextIndex]}ms")
    }

    private fun resetSilenceTimer() {
        silenceJob?.cancel()
        silenceJob = viewModelScope.launch {
            delay(_state.value.silenceDelayMs)
            finalizePhraseToHistory()
        }
    }

    private fun finalizePhraseToHistory() {
        val current = _state.value
        if (current.sourceText.isBlank()) return

        val finalTranslation = current.translatedText

        upsertFeedEntry(
            resultId = activeResultId,
            originalText = current.sourceText,
            translatedText = finalTranslation,
            pronunciationText = current.pronunciationText,
            provider = current.translationProvider.ifBlank { "Rokid" },
            sourceLanguage = current.detectedLanguage,
            targetLanguage = current.targetLanguage
        )

        // Speak translation in TTS mode
        if (current.mode == TranslationMode.LOCAL_TTS && finalTranslation.isNotBlank()) {
            bridge.playTts(finalTranslation)
            addLog("TTS: $finalTranslation")
        }

        // Keep last values visible, just mark as finalized
        _state.update {
            it.copy(
                isTranslating = false,
                isTemporaryResult = false,
                status = statusForMode(it.mode, connected = it.isConnected, listening = it.isListening)
            )
        }
        addLog("Phrase finalized to history")
    }

    fun toggleTranslation() {
        applyMode(nextMode(_state.value.mode, _state.value.isOnline))
    }

    private fun applyMode(mode: TranslationMode) {
        val previousMode = _state.value.mode
        val connected = bridge.isConnected()
        val shouldStart = mode != TranslationMode.DISABLED && connected

        resetResultTracking()
        cancelActiveTranslation()

        if (mode == TranslationMode.DISABLED) {
            listeningStartedAtMs = 0L
            clearStartupTiming()
            addLog("Stopping translation for ${previousMode.label}")
            bridge.stopTranslation()
        } else {
            if (!connected) {
                addLog("Not connected - rebinding for ${mode.label}")
                bridge.bind()
            } else {
                listeningStartedAtMs = System.currentTimeMillis()
                markTranslationRequested("mode=${mode.label}")
                addLog("Requesting translation start for ${mode.label}")
                bridge.startTranslation()
            }
        }

        _state.update {
            it.copy(
                mode = mode,
                sourceText = "",
                translatedText = "",
                pronunciationText = "",
                translationProvider = "",
                translationAlternatives = emptyList(),
                detectedLanguage = "",
                targetLanguage = "",
                counterpartLanguageLabel = preferredCounterpartLabel,
                isListening = false,
                isTemporaryResult = false,
                configuredPairLabel = routeLabelForMode(mode),
                status = statusForMode(mode, connected = connected, listening = false, starting = shouldStart)
            )
        }
    }

    // AssistBridge.Listener
    override fun onConnected() {
        Log.d(TAG, "Bridge connected")
        val selectedMode = _state.value.mode
        if (selectedMode != TranslationMode.DISABLED) {
            resetResultTracking()
            cancelActiveTranslation()
            listeningStartedAtMs = System.currentTimeMillis()
            markTranslationRequested("bridge reconnect mode=${selectedMode.label}")
            addLog("Bridge connected - requesting translation start for ${selectedMode.label}")
            bridge.startTranslation()
        }
        _state.update {
            it.copy(
                isConnected = true,
                isListening = false,
                counterpartLanguageLabel = preferredCounterpartLabel,
                configuredPairLabel = routeLabelForMode(selectedMode),
                status = statusForMode(
                    mode = selectedMode,
                    connected = true,
                    listening = false,
                    starting = selectedMode != TranslationMode.DISABLED
                )
            )
        }
        addLog("Bridge connected")
    }

    override fun onDisconnected() {
        Log.d(TAG, "Bridge disconnected")
        _state.update {
            it.copy(
                isConnected = false,
                isListening = false,
                status = statusForMode(it.mode, connected = false, listening = false)
            )
        }
        addLog("Bridge disconnected")
    }

    override fun onTranslationMessage(cmd: String, subCmd: String, data: String) {
        Log.d(TAG, "Translation msg: cmd=$cmd sub=$subCmd data=$data")
        addLog("[$cmd] $subCmd | $data")

        when (cmd) {
            "Trans" -> handleTransMessage(subCmd, data)
            else -> {
                addLog("Unknown cmd: $cmd")
            }
        }
    }

    override fun onSceneStatus(translateRunning: Boolean) {
        addLog("Scene status translateRunning=$translateRunning")
        if (translateRunning) {
            markSceneReady("scene status")
        }
        _state.update {
            it.copy(
                isListening = translateRunning,
                status = statusForMode(it.mode, connected = it.isConnected, listening = translateRunning)
            )
        }
    }

    private fun handleTransMessage(key: String, data: String) {
        when (key) {
            "Trans_Start" -> {
                markSceneReady("Trans_Start")
                _state.update {
                    it.copy(
                        isListening = true,
                        status = statusForMode(it.mode, connected = it.isConnected, listening = true)
                    )
                }
                addLog("Translation started by phone")
            }
            "Trans_Stop" -> {
                clearStartupTiming()
                _state.update {
                    it.copy(
                        isListening = false,
                        status = statusForMode(it.mode, connected = it.isConnected, listening = false)
                    )
                }
                addLog("Translation stopped by phone")
            }
            "Trans_Result" -> {
                if (_state.value.mode == TranslationMode.DISABLED) return
                try {
                    val json = org.json.JSONObject(data)
                    val resultId = json.optInt("id", -1).takeIf { it >= 0 }
                    val source = firstNonBlank(
                        json.optString("sourceText"),
                        json.optString("originalText"),
                        json.optString("text"),
                        json.optString("asr")
                    )
                    val translated = firstNonBlank(
                        json.optString("translatedText"),
                        json.optString("translation"),
                        json.optString("result")
                    )
                    val lang = firstNonBlank(
                        json.optString("language"),
                        json.optString("from")
                    )
                    val target = firstNonBlank(
                        json.optString("targetLanguage"),
                        json.optString("to")
                    )
                    val isTemporary = json.optBoolean("temporary", false)
                    val isFinished = json.optBoolean("finished", false)
                    if (shouldIgnoreStartupTranscript(source, translated)) {
                        addLog("Ignoring startup prompt transcript")
                        return
                    }
                    markFirstResult(resultId, isTemporary)
                    if (!_state.value.isListening) {
                        _state.update {
                            it.copy(
                                isListening = true,
                                status = statusForMode(
                                    mode = it.mode,
                                    connected = it.isConnected,
                                    listening = true
                                )
                            )
                        }
                    }
                    beginResultTracking(resultId)

                    // Reset silence timer on every result
                    resetSilenceTimer()

                    // Check if Rokid's translation is different from the source (actual translation vs echo)
                    val rokidTranslated = translated.trim()
                    val sourceNorm = source.trim().lowercase()
                    val transNorm = rokidTranslated.lowercase()
                    val isEcho = sourceNorm.isNotBlank() && transNorm.isNotBlank() &&
                        (sourceNorm == transNorm ||
                         sourceNorm.replace(Regex("[^\\p{L}\\s]"), "") == transNorm.replace(Regex("[^\\p{L}\\s]"), ""))

                    // Always show heard text immediately + Rokid translation if it's real
                    _state.update {
                        it.copy(
                            sourceText = source.ifBlank { it.sourceText },
                            translatedText = if (!isEcho && rokidTranslated.isNotBlank()) rokidTranslated else if (isEcho) "" else it.translatedText,
                            translationProvider = if (!isEcho && rokidTranslated.isNotBlank()) "Rokid" else if (isEcho) "" else it.translationProvider,
                            isTranslating = true,
                            isTemporaryResult = isTemporary,
                            detectedLanguage = lang.ifBlank { it.detectedLanguage },
                            targetLanguage = target.ifBlank { it.targetLanguage },
                            status = "Listening..."
                        )
                    }

                    if (source.isBlank()) return

                    // If Rokid provided a real translation, trust its direction
                    // (Rokid scene is set to translate counterpart→English)
                    val (sourceCode, targetCode) = if (!isEcho && rokidTranslated.isNotBlank()) {
                        // Rokid translated successfully, source was likely the counterpart language
                        preferredCounterpartCode to "en"
                    } else {
                        resolveDirection(source, _state.value.mode)
                    }
                    val sourceLabel = languageDisplayName(sourceCode)
                    val targetLabel = languageDisplayName(targetCode)

                    _state.update {
                        it.copy(
                            sourceText = source,
                            detectedLanguage = sourceLabel,
                            targetLanguage = targetLabel
                        )
                    }

                    // Kick off local/cloud translation to refine
                    requestLocalTranslation(
                        sourceText = source,
                        sourceLanguage = sourceCode,
                        targetLanguage = targetCode,
                        resultId = resultId,
                        fallbackTranslation = translated,
                        isTemporary = isTemporary,
                        isFinished = isFinished
                    )
                } catch (e: Exception) {
                    val alternatives = updateTranslationAlternatives(null, data)
                    _state.update {
                        it.copy(
                            translatedText = data,
                            pronunciationText = "",
                            translationProvider = "Rokid",
                            translationAlternatives = alternatives,
                            isTemporaryResult = false,
                            status = "Result"
                        )
                    }
                    upsertFeedEntry(
                        resultId = null,
                        originalText = "",
                        translatedText = data,
                        pronunciationText = "",
                        provider = "Rokid",
                        sourceLanguage = "",
                        targetLanguage = ""
                    )
                }
            }
            "Trans_ChangeSceneIdStatus" -> handleTransReady(data)
            "Trans_Data" -> handleTransData(data)
            "Trans_Ready" -> handleTransReady(data)
            "Trans_Language" -> handleTransLanguage(data)
            "Trans_Language_info" -> handleTransLanguage(data)
            "Trans_SetTvConfig" -> addLog("TV config: $data")
            "Trans_RequestChangeSceneId" -> addLog("Scene change requested: $data")
        }
    }

    private fun handleTransData(data: String) {
        addLog("Ignoring assist partial data: $data")
    }

    private fun handleTransReady(data: String) {
        try {
            val json = org.json.JSONObject(data)
            val ready = json.optBoolean("ready", json.optBoolean("isReady", false))
            val isOnline = json.optBoolean("isOnline", false)
            val changeSceneIdStatus = json.optString("changeSceneIdStatus", "")
            val message = json.optString("message")
                .ifEmpty { json.optString("msg") }
            if (ready) {
                markSceneReady(
                    when {
                        changeSceneIdStatus.isNotEmpty() -> "ready:$changeSceneIdStatus"
                        isOnline -> "ready:online"
                        else -> "ready:listening"
                    }
                )
                val status = when {
                    changeSceneIdStatus.isNotEmpty() -> "Ready (${changeSceneIdStatus})"
                    isOnline -> "Ready - online"
                    else -> "Ready - listening"
                }
                _state.update { it.copy(isListening = true, isTemporaryResult = false, status = status) }
            } else {
                _state.update { it.copy(isListening = false, isTemporaryResult = false, status = message.ifEmpty { "Not ready" }) }
            }
        } catch (e: Exception) {
            _state.update { it.copy(status = "Ready data: $data") }
        }
    }

    private fun handleTransLanguage(data: String) {
        try {
            val json = org.json.JSONObject(data)
            val from = firstNonBlank(
                json.optString("from"),
                json.optString("fromLanguage")
            )
            val to = firstNonBlank(
                json.optString("to"),
                json.optString("toLanguage")
            )
            val model = json.optString("modeInfo", "")
            updatePreferredCounterpartLanguage(from, to)
            // Persist language config for next startup
            historyPrefs.edit()
                .putString(KEY_COUNTERPART_CODE, preferredCounterpartCode)
                .putString(KEY_COUNTERPART_LABEL, preferredCounterpartLabel)
                .putString(KEY_PHONE_MODEL, model.ifBlank { _state.value.phoneModel })
                .apply()
            _state.update {
                it.copy(
                    counterpartLanguageLabel = preferredCounterpartLabel,
                    configuredPairLabel = routeLabelForMode(it.mode),
                    phoneModel = model.ifBlank { it.phoneModel }
                )
            }
            preloadPreferredModels()
            addLog("Language pair: $from -> $to, model: $model (preferred counterpart $preferredCounterpartLabel)")
        } catch (e: Exception) { /* ignore */ }
    }

    private fun firstNonBlank(vararg values: String): String =
        values.firstOrNull { it.isNotBlank() }?.trim().orEmpty()

    private fun resolveDirection(sourceText: String, mode: TranslationMode): Pair<String, String> =
        when (mode) {
            TranslationMode.LOCAL_SILENT, TranslationMode.LOCAL_TTS, TranslationMode.ONLINE -> {
                val sourceLanguage = ConversationLanguageDetector.detect(sourceText, preferredCounterpartCode)
                val targetLanguage = if (sourceLanguage == "en") preferredCounterpartCode else "en"
                sourceLanguage to targetLanguage
            }
            TranslationMode.DISABLED -> preferredCounterpartCode to "en"
        }

    private fun requestLocalTranslation(
        sourceText: String,
        sourceLanguage: String,
        targetLanguage: String,
        resultId: Int?,
        fallbackTranslation: String,
        isTemporary: Boolean,
        isFinished: Boolean,
    ) {
        activeTranslationNonce += 1
        val nonce = activeTranslationNonce
        activeTranslationJob?.cancel()
        activeTranslationJob = viewModelScope.launch {
            if (isTemporary) delay(100)
            if (nonce != activeTranslationNonce) return@launch

            val currentMode = _state.value.mode
            val fallback = normalizedAlternativeOrBlank(
                sourceText = sourceText,
                candidate = fallbackTranslation,
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage
            )

            if (currentMode == TranslationMode.ONLINE &&
                cloudTranslator.isConfigured() &&
                _state.value.isOnline
            ) {
                try {
                    addLog("Cloud translation request: ${languageDisplayName(sourceLanguage)} -> ${languageDisplayName(targetLanguage)}")
                    addLog("OpenRouter model: ${BuildConfig.OPENROUTER_MODEL}")
                    val cloudResult = cloudTranslator.translateWithPronunciation(
                        text = sourceText,
                        sourceLanguage = sourceLanguage,
                        targetLanguage = targetLanguage
                    )
                    if (nonce != activeTranslationNonce) return@launch

                    val alternatives = updateTranslationAlternatives(
                        resultId = resultId,
                        translation = cloudResult.translation,
                        sourceText = sourceText,
                        sourceLanguage = sourceLanguage,
                        targetLanguage = targetLanguage
                    )
                    _state.update {
                        it.copy(
                            translatedText = cloudResult.translation,
                            pronunciationText = cloudResult.pronunciation,
                            translationProvider = "OpenRouter",
                            translationAlternatives = alternatives,
                            detectedLanguage = languageDisplayName(sourceLanguage),
                            targetLanguage = languageDisplayName(targetLanguage),
                            isTranslating = false,
                            isTemporaryResult = false,
                            status = "Cloud translated"
                        )
                    }
                    upsertFeedEntry(
                        resultId = resultId,
                        originalText = sourceText,
                        translatedText = cloudResult.translation,
                        pronunciationText = cloudResult.pronunciation,
                        provider = "OpenRouter",
                        sourceLanguage = languageDisplayName(sourceLanguage),
                        targetLanguage = languageDisplayName(targetLanguage)
                    )
                        addLog("Cloud translation ready after ${formatElapsed(elapsedSince(firstResultAtMs))}: ${cloudResult.translation}")
                        return@launch
                } catch (cloudError: Exception) {
                    addLog("Cloud translation failed: ${cloudError.message}")
                    Log.e(TAG, "Cloud translation failed", cloudError)
                }
            }

            if (currentMode == TranslationMode.ONLINE && fallback.isNotBlank()) {
                val alternatives = updateTranslationAlternatives(
                    resultId = resultId,
                    translation = fallback,
                    sourceText = sourceText,
                    sourceLanguage = sourceLanguage,
                    targetLanguage = targetLanguage
                )
                _state.update {
                    it.copy(
                        translatedText = fallback,
                        pronunciationText = "",
                        translationProvider = "Rokid",
                        translationAlternatives = alternatives,
                        detectedLanguage = languageDisplayName(sourceLanguage),
                        targetLanguage = languageDisplayName(targetLanguage),
                        isTemporaryResult = isTemporary,
                        status = "Rokid fallback"
                    )
                }
                upsertFeedEntry(
                    resultId = resultId,
                    originalText = sourceText,
                    translatedText = fallback,
                    pronunciationText = "",
                    provider = "Rokid",
                    sourceLanguage = languageDisplayName(sourceLanguage),
                    targetLanguage = languageDisplayName(targetLanguage)
                )
                addLog("Rokid fallback ready after ${formatElapsed(elapsedSince(firstResultAtMs))}: $fallback")
                Log.d(TAG, "Rokid fallback ready: $fallback")
                return@launch
            }

            try {
                addLog("Local translation request: ${languageDisplayName(sourceLanguage)} -> ${languageDisplayName(targetLanguage)} | $sourceText")
                Log.d(TAG, "Local translation request: $sourceLanguage -> $targetLanguage | $sourceText")
                val translated = withTimeoutOrNull(1200) {
                    localTranslator.translate(sourceText, sourceLanguage, targetLanguage)
                }
                if (nonce != activeTranslationNonce) return@launch
                if (translated == null) {
                    throw IllegalStateException("Local translation timed out")
                }

                val alternatives = updateTranslationAlternatives(
                    resultId = resultId,
                    translation = translated,
                    sourceText = sourceText,
                    sourceLanguage = sourceLanguage,
                    targetLanguage = targetLanguage
                )
                val visibleTranslation = preferVisibleTranslation(
                    current = _state.value.translatedText,
                    candidate = translated,
                    isTemporary = isTemporary,
                    isFinished = isFinished
                )
                _state.update {
                    it.copy(
                        translatedText = visibleTranslation,
                        pronunciationText = "",
                        translationProvider = if (visibleTranslation == translated) "Local" else it.translationProvider,
                        translationAlternatives = alternatives,
                        detectedLanguage = languageDisplayName(sourceLanguage),
                        targetLanguage = languageDisplayName(targetLanguage),
                        isTranslating = false,
                        isTemporaryResult = isTemporary,
                        status = when {
                            isFinished -> "Translated locally"
                            isTemporary -> "Translating locally..."
                            else -> "Translated locally"
                        }
                    )
                }
                upsertFeedEntry(
                    resultId = resultId,
                    originalText = sourceText,
                    translatedText = visibleTranslation,
                    pronunciationText = "",
                    provider = if (visibleTranslation == translated) "Local" else "",
                    sourceLanguage = languageDisplayName(sourceLanguage),
                    targetLanguage = languageDisplayName(targetLanguage)
                )
                addLog("Local translation ready after ${formatElapsed(elapsedSince(firstResultAtMs))}: $translated")
                Log.d(TAG, "Local translation ready: $translated")
            } catch (localError: Exception) {
                if (nonce != activeTranslationNonce) return@launch
                addLog("Local translation failed: ${localError.message}")
                Log.e(TAG, "Local translation failed", localError)

                if (fallback.isNotBlank()) {
                    val alternatives = updateTranslationAlternatives(
                        resultId = resultId,
                        translation = fallback,
                        sourceText = sourceText,
                        sourceLanguage = sourceLanguage,
                        targetLanguage = targetLanguage
                    )
                    val visibleFallback = preferVisibleTranslation(
                        current = _state.value.translatedText,
                        candidate = fallback,
                        isTemporary = isTemporary,
                        isFinished = true
                    )
                    _state.update {
                        it.copy(
                            translatedText = visibleFallback,
                            pronunciationText = "",
                            translationProvider = if (visibleFallback.isNotBlank()) "Rokid" else it.translationProvider,
                            translationAlternatives = alternatives,
                            detectedLanguage = languageDisplayName(sourceLanguage),
                            targetLanguage = languageDisplayName(targetLanguage),
                            isTemporaryResult = isTemporary,
                            status = "Rokid fallback"
                        )
                    }
                    upsertFeedEntry(
                        resultId = resultId,
                        originalText = sourceText,
                        translatedText = visibleFallback,
                        pronunciationText = "",
                        provider = if (visibleFallback.isNotBlank()) "Rokid" else "",
                        sourceLanguage = languageDisplayName(sourceLanguage),
                        targetLanguage = languageDisplayName(targetLanguage)
                    )
                    addLog("Rokid fallback ready after ${formatElapsed(elapsedSince(firstResultAtMs))}: $fallback")
                    Log.d(TAG, "Rokid fallback ready: $fallback")
                    return@launch
                }

                _state.update {
                    it.copy(
                        translatedText = sourceText,
                        pronunciationText = "",
                        translationProvider = "",
                        translationAlternatives = emptyList(),
                        detectedLanguage = languageDisplayName(sourceLanguage),
                        targetLanguage = languageDisplayName(targetLanguage),
                        isTemporaryResult = isTemporary,
                        status = "Translation unavailable"
                    )
                }
                upsertFeedEntry(
                    resultId = resultId,
                    originalText = sourceText,
                    translatedText = "",
                    pronunciationText = "",
                    provider = "",
                    sourceLanguage = languageDisplayName(sourceLanguage),
                    targetLanguage = languageDisplayName(targetLanguage)
                )
            }
        }
    }

    private fun observeNetworkState() {
        viewModelScope.launch {
            networkMonitor.connectivityFlow().collect { online ->
                val changed = _state.value.isOnline != online
                _state.update { it.copy(isOnline = online) }
                if (changed) {
                    addLog(if (online) "Network online" else "Network offline")
                    if (!online && _state.value.mode == TranslationMode.ONLINE) {
                        addLog("Online unavailable offline - switching to Local Silent")
                        applyMode(TranslationMode.LOCAL_SILENT)
                    }
                }
            }
        }
    }

    private fun preloadPreferredModels() {
        viewModelScope.launch {
            try {
                localTranslator.preloadCounterpart(preferredCounterpartCode)
                addLog("Local models ready for English <-> $preferredCounterpartLabel")
                Log.d(TAG, "Local models ready for English <-> $preferredCounterpartLabel")
            } catch (error: Exception) {
                addLog("Model preload failed: ${error.message}")
                Log.e(TAG, "Model preload failed", error)
            }
        }
    }

    private fun updateTranslationAlternatives(
        resultId: Int?,
        translation: String,
        sourceText: String = "",
        sourceLanguage: String = "",
        targetLanguage: String = "",
    ): List<String> {
        val normalized = normalizedAlternativeOrBlank(
            sourceText = sourceText,
            candidate = translation,
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage
        )
        if (resultId != null && activeResultId != resultId) {
            activeResultId = resultId
            activeTranslationAlternatives = emptyList()
        } else if (resultId == null && activeTranslationAlternatives.isEmpty()) {
            activeResultId = null
        }

        if (normalized.isBlank()) return activeTranslationAlternatives

        val updated = when {
            activeTranslationAlternatives.isEmpty() -> listOf(normalized)
            shouldReplaceAlternative(activeTranslationAlternatives.last(), normalized) -> {
                activeTranslationAlternatives.dropLast(1) + normalized
            }
            activeTranslationAlternatives.any { areNearlySameAlternative(it, normalized) } -> {
                activeTranslationAlternatives.map { existing ->
                    if (areNearlySameAlternative(existing, normalized)) normalized else existing
                }
            }
            else -> (activeTranslationAlternatives + normalized).takeLast(3)
        }
        activeTranslationAlternatives = updated
        return updated
    }

    private fun normalizedAlternativeOrBlank(
        sourceText: String,
        candidate: String,
        sourceLanguage: String,
        targetLanguage: String,
    ): String {
        val normalized = candidate.trim()
        if (normalized.isBlank()) return ""
        if (sourceLanguage.isNotBlank() &&
            targetLanguage.isNotBlank() &&
            sourceLanguage != targetLanguage &&
            sourceText.isNotBlank() &&
            areNearlySameAlternative(sourceText, normalized)
        ) {
            return ""
        }
        return normalized
    }

    private fun preferVisibleTranslation(
        current: String,
        candidate: String,
        isTemporary: Boolean,
        isFinished: Boolean,
    ): String {
        val existing = current.trim()
        val proposed = candidate.trim()
        if (proposed.isBlank()) return existing
        if (existing.isBlank()) return proposed
        if (isFinished) return proposed
        // Always show the latest candidate — prefer responsiveness over stability
        return proposed
    }

    private fun resetResultTracking() {
        activeResultId = null
        activeTranslationAlternatives = emptyList()
    }

    private fun beginResultTracking(resultId: Int?) {
        if (resultId != null && activeResultId != resultId) {
            activeResultId = resultId
            activeTranslationAlternatives = emptyList()
            _state.update { it.copy(translatedText = "", pronunciationText = "", translationProvider = "", translationAlternatives = emptyList()) }
        }
    }

    private fun shouldReplaceAlternative(previous: String, next: String): Boolean {
        if (areNearlySameAlternative(previous, next)) return true

        val previousComparable = normalizeForComparison(previous)
        val nextComparable = normalizeForComparison(next)
        if (previousComparable.isBlank() || nextComparable.isBlank()) return false

        val prefixLength = commonPrefixLength(previousComparable, nextComparable)
        val minLength = minOf(previousComparable.length, nextComparable.length)
        val prefixRatio = prefixLength.toDouble() / minLength.toDouble()

        return prefixRatio >= 0.7
    }

    private fun areNearlySameAlternative(left: String, right: String): Boolean {
        val normalizedLeft = normalizeForComparison(left)
        val normalizedRight = normalizeForComparison(right)
        if (normalizedLeft.isBlank() || normalizedRight.isBlank()) return false

        return normalizedLeft == normalizedRight ||
            normalizedLeft.startsWith(normalizedRight) ||
            normalizedRight.startsWith(normalizedLeft)
    }

    private fun normalizeForComparison(value: String): String =
        value
            .lowercase(java.util.Locale.US)
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

    private fun commonPrefixLength(left: String, right: String): Int {
        val maxIndex = minOf(left.length, right.length)
        var index = 0
        while (index < maxIndex && left[index] == right[index]) {
            index++
        }
        return index
    }

    private fun updatePreferredCounterpartLanguage(from: String, to: String) {
        val fromCode = languageCode(from)
        val toCode = languageCode(to)
        val counterpart = listOf(fromCode, toCode)
            .firstOrNull { it != null && it != "en" }
            ?: preferredCounterpartCode

        preferredCounterpartCode = counterpart
        preferredCounterpartLabel = languageDisplayName(counterpart)
    }

    private fun languageCode(value: String): String? {
        val normalized = value.trim().lowercase(java.util.Locale.US)
        return when {
            normalized.startsWith("it") || normalized.contains("italian") -> "it"
            normalized.startsWith("es") || normalized.contains("spanish") -> "es"
            normalized.startsWith("en") || normalized.contains("english") -> "en"
            else -> null
        }
    }

    private fun languageDisplayName(languageCode: String): String =
        when (languageCode) {
            "es" -> "Spanish"
            "it" -> "Italian"
            else -> "English"
        }

    private fun providerPriority(provider: String): Int =
        when (provider) {
            "OpenRouter" -> 3
            "Rokid" -> 2
            "Local" -> 1
            else -> 0
        }

    private fun cancelActiveTranslation() {
        activeTranslationNonce += 1
        activeTranslationJob?.cancel()
        activeTranslationJob = null
    }

    private fun markTranslationRequested(reason: String) {
        translationRequestedAtMs = System.currentTimeMillis()
        sceneReadyAtMs = 0L
        firstResultAtMs = 0L
        addLog("Timing start requested ($reason)")
    }

    private fun markSceneReady(reason: String) {
        if (sceneReadyAtMs != 0L) return
        sceneReadyAtMs = System.currentTimeMillis()
        val fromRequest = elapsedSince(translationRequestedAtMs)
        addLog("Timing scene ready via $reason after ${formatElapsed(fromRequest)}")
    }

    private fun markFirstResult(resultId: Int?, isTemporary: Boolean) {
        if (firstResultAtMs != 0L) return
        firstResultAtMs = System.currentTimeMillis()
        val fromRequest = elapsedSince(translationRequestedAtMs)
        val fromReady = elapsedSince(sceneReadyAtMs)
        val kind = if (isTemporary) "temporary" else "stable"
        addLog(
            "Timing first $kind result id=${resultId ?: -1} after ${formatElapsed(fromRequest)}" +
                " (${formatElapsed(fromReady)} from ready)"
        )
    }

    private fun clearStartupTiming() {
        translationRequestedAtMs = 0L
        sceneReadyAtMs = 0L
        firstResultAtMs = 0L
    }

    private fun elapsedSince(timestampMs: Long): Long =
        if (timestampMs <= 0L) -1L else System.currentTimeMillis() - timestampMs

    private fun formatElapsed(durationMs: Long): String =
        if (durationMs < 0L) "n/a" else "${durationMs}ms"

    private fun upsertFeedEntry(
        resultId: Int?,
        originalText: String,
        translatedText: String,
        pronunciationText: String,
        provider: String,
        sourceLanguage: String,
        targetLanguage: String,
    ) {
        _state.update { current ->
            val entries = current.feedEntries.toMutableList()
            val existingIndex = entries.indexOfLast { it.resultId == resultId && resultId != null }
            val newPriority = providerPriority(provider)

            if (existingIndex >= 0) {
                val existing = entries[existingIndex]
                val existingPriority = providerPriority(existing.provider)
                val canReplaceProvider = newPriority >= existingPriority
                entries[existingIndex] = existing.copy(
                    originalText = originalText.ifBlank { existing.originalText },
                    translatedText = if (canReplaceProvider) translatedText.ifBlank { existing.translatedText } else existing.translatedText,
                    pronunciationText = if (canReplaceProvider) pronunciationText.ifBlank { existing.pronunciationText } else existing.pronunciationText,
                    provider = if (canReplaceProvider) provider.ifBlank { existing.provider } else existing.provider,
                    sourceLanguage = sourceLanguage.ifBlank { existing.sourceLanguage },
                    targetLanguage = targetLanguage.ifBlank { existing.targetLanguage }
                )
            } else {
                if (originalText.isBlank() && translatedText.isBlank()) return@update current
                entries += TranslationFeedEntry(
                    resultId = resultId,
                    originalText = originalText,
                    translatedText = translatedText,
                    pronunciationText = pronunciationText,
                    provider = provider,
                    sourceLanguage = sourceLanguage,
                    targetLanguage = targetLanguage
                )
            }

            val updated = entries.takeLast(20)
            saveFeedHistory(updated)
            current.copy(feedEntries = updated)
        }
    }

    private fun saveFeedHistory(entries: List<TranslationFeedEntry>) {
        val array = JSONArray()
        for (e in entries) {
            array.put(JSONObject().apply {
                put("resultId", e.resultId ?: JSONObject.NULL)
                put("originalText", e.originalText)
                put("translatedText", e.translatedText)
                put("pronunciationText", e.pronunciationText)
                put("provider", e.provider)
                put("sourceLanguage", e.sourceLanguage)
                put("targetLanguage", e.targetLanguage)
            })
        }
        historyPrefs.edit().putString(KEY_FEED, array.toString()).apply()
    }

    private fun loadFeedHistory(): List<TranslationFeedEntry> {
        val json = historyPrefs.getString(KEY_FEED, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                TranslationFeedEntry(
                    resultId = if (obj.isNull("resultId")) null else obj.getInt("resultId"),
                    originalText = obj.optString("originalText", ""),
                    translatedText = obj.optString("translatedText", ""),
                    pronunciationText = obj.optString("pronunciationText", ""),
                    provider = obj.optString("provider", ""),
                    sourceLanguage = obj.optString("sourceLanguage", ""),
                    targetLanguage = obj.optString("targetLanguage", ""),
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load feed history", e)
            emptyList()
        }
    }

    private fun addLog(msg: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        _state.update {
            val log = it.debugLog + "[$timestamp] $msg\n"
            // Keep enough history for the wider on-screen debug pane.
            val lines = log.lines().takeLast(60)
            it.copy(debugLog = lines.joinToString("\n"))
        }
    }

    private fun routeLabelForMode(mode: TranslationMode): String =
        when (mode) {
            TranslationMode.DISABLED -> "Tap to choose mode"
            TranslationMode.LOCAL_SILENT -> "$preferredCounterpartLabel <-> English"
            TranslationMode.LOCAL_TTS -> "$preferredCounterpartLabel <-> English"
            TranslationMode.ONLINE -> "$preferredCounterpartLabel <-> English (cloud)"
        }

    private fun shouldIgnoreStartupTranscript(source: String, translated: String): Boolean {
        val startedAt = listeningStartedAtMs
        if (startedAt <= 0L) return false
        val elapsed = System.currentTimeMillis() - startedAt
        if (elapsed > 6000L) return false

        val combined = normalizeForComparison("$source $translated")
        if (combined.isBlank()) return false

        return combined.contains("translation has started") ||
            combined.contains("the translation has started")
    }

    private fun nextMode(current: TranslationMode, onlineAvailable: Boolean): TranslationMode =
        when (current) {
            TranslationMode.DISABLED -> TranslationMode.LOCAL_SILENT
            TranslationMode.LOCAL_SILENT -> TranslationMode.LOCAL_TTS
            TranslationMode.LOCAL_TTS -> if (onlineAvailable) TranslationMode.ONLINE else TranslationMode.DISABLED
            TranslationMode.ONLINE -> TranslationMode.DISABLED
        }

    private fun statusForMode(
        mode: TranslationMode,
        connected: Boolean,
        listening: Boolean,
        starting: Boolean = false,
    ): String =
        when (mode) {
            TranslationMode.DISABLED -> if (connected) "Mode disabled" else "Connecting..."
            TranslationMode.LOCAL_SILENT ->
                when {
                    !connected -> "Local silent - connecting..."
                    starting -> "Local silent - starting..."
                    listening -> "Local silent listening"
                    else -> "Local silent ready"
                }
            TranslationMode.LOCAL_TTS ->
                when {
                    !connected -> "Local TTS - connecting..."
                    starting -> "Local TTS - starting..."
                    listening -> "Local TTS listening"
                    else -> "Local TTS ready"
                }
            TranslationMode.ONLINE ->
                when {
                    !connected -> "Online - connecting..."
                    !this._state.value.isOnline -> "Online - offline fallback"
                    starting -> "Online - starting..."
                    listening -> "Online listening"
                    else -> "Online ready"
                }
        }

    override fun onCleared() {
        super.onCleared()
        cancelActiveTranslation()
        localTranslator.close()
        bridge.unbind()
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(TranslatorViewModel::class.java))
                return TranslatorViewModel(context.applicationContext) as T
            throw IllegalArgumentException("Unknown ViewModel")
        }
    }
}
