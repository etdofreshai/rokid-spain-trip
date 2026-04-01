package com.rokid.translator.glasses.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rokid.translator.glasses.bridge.AssistBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TranslatorState(
    val status: String = "Tap to connect",
    val sourceText: String = "",
    val translatedText: String = "",
    val translationAlternatives: List<String> = emptyList(),
    val detectedLanguage: String = "",
    val targetLanguage: String = "",
    val isConnected: Boolean = false,
    val isListening: Boolean = false,
    val isTemporaryResult: Boolean = false,
    val debugLog: String = "",
)

class TranslatorViewModel(private val context: Context) : ViewModel(), AssistBridge.Listener {

    companion object { private const val TAG = "TranslatorVM" }

    private val bridge = AssistBridge(context)
    private var activeResultId: Int? = null
    private var activeTranslationAlternatives: List<String> = emptyList()

    private val _state = MutableStateFlow(TranslatorState())
    val state: StateFlow<TranslatorState> = _state.asStateFlow()

    init {
        bridge.setListener(this)
        viewModelScope.launch { bridge.bind() }
    }

    fun toggleTranslation() {
        if (_state.value.isListening) {
            bridge.stopTranslation()
            resetResultTracking()
            _state.update {
                it.copy(
                    isListening = false,
                    isTemporaryResult = false,
                    translationAlternatives = emptyList(),
                    status = "Stopped"
                )
            }
        } else {
            if (!bridge.isConnected()) {
                addLog("Not connected - rebinding")
                bridge.bind()
                return
            }
            bridge.startTranslation()
            resetResultTracking()
            _state.update {
                it.copy(
                    sourceText = "",
                    translatedText = "",
                    translationAlternatives = emptyList(),
                    isListening = true,
                    isTemporaryResult = false,
                    status = "Listening..."
                )
            }
        }
    }

    // AssistBridge.Listener
    override fun onConnected() {
        Log.d(TAG, "Bridge connected")
        _state.update { it.copy(isConnected = true, status = "Connected - tap to listen") }
        addLog("Bridge connected")
    }

    override fun onDisconnected() {
        Log.d(TAG, "Bridge disconnected")
        _state.update { it.copy(isConnected = false, isListening = false, status = "Disconnected") }
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
        _state.update {
            it.copy(
                isListening = translateRunning,
                status = if (translateRunning) "Listening..." else if (it.isConnected) "Connected - tap to listen" else "Disconnected"
            )
        }
    }

    private fun handleTransMessage(key: String, data: String) {
        when (key) {
            "Trans_Start" -> {
                _state.update { it.copy(isListening = true, status = "Listening...") }
                addLog("Translation started by phone")
            }
            "Trans_Stop" -> {
                _state.update { it.copy(isListening = false, status = "Stopped") }
                addLog("Translation stopped by phone")
            }
            "Trans_Result" -> {
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
                    val alternatives = updateTranslationAlternatives(resultId, translated)
                    _state.update {
                        it.copy(
                            sourceText = source.ifBlank { it.sourceText },
                            translatedText = translated.ifBlank { it.translatedText },
                            translationAlternatives = alternatives,
                            detectedLanguage = lang.ifBlank { it.detectedLanguage },
                            targetLanguage = target.ifBlank { it.targetLanguage },
                            isTemporaryResult = isTemporary,
                            status = when {
                                isFinished -> "Translated"
                                isTemporary -> "Translating..."
                                else -> "Translated"
                            }
                        )
                    }
                } catch (e: Exception) {
                    val alternatives = updateTranslationAlternatives(null, data)
                    _state.update {
                        it.copy(
                            translatedText = data,
                            translationAlternatives = alternatives,
                            isTemporaryResult = false,
                            status = "Result"
                        )
                    }
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
        // Live/partial translation data
        val alternatives = updateTranslationAlternatives(activeResultId, data)
        _state.update {
            it.copy(
                translatedText = data,
                translationAlternatives = alternatives,
                isTemporaryResult = true,
                status = "Translating..."
            )
        }
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
            _state.update { it.copy(detectedLanguage = from, targetLanguage = to) }
            addLog("Language: $from -> $to")
        } catch (e: Exception) { /* ignore */ }
    }

    private fun firstNonBlank(vararg values: String): String =
        values.firstOrNull { it.isNotBlank() }?.trim().orEmpty()

    private fun updateTranslationAlternatives(resultId: Int?, translation: String): List<String> {
        val normalized = translation.trim()
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

    private fun resetResultTracking() {
        activeResultId = null
        activeTranslationAlternatives = emptyList()
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

    private fun addLog(msg: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        _state.update {
            val log = it.debugLog + "[$timestamp] $msg\n"
            // Keep last 20 lines
            val lines = log.lines().takeLast(20)
            it.copy(debugLog = lines.joinToString("\n"))
        }
    }

    override fun onCleared() {
        super.onCleared()
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
