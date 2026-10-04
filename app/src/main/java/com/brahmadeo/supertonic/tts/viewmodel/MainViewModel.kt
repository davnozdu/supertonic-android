package com.brahmadeo.supertonic.tts.viewmodel

import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException

class MainViewModel : ViewModel() {
    var showLinkDialog = mutableStateOf(false)
    var articleLink = mutableStateOf("")
    var articleLoading = mutableStateOf(false)
    var articleError = mutableStateOf<String?>(null)
    var pendingArticle = mutableStateOf<String?>(null)
    private var articleJob: Job? = null
    private var articleGeneration = 0L
    fun cancelArticle() {
        articleGeneration++
        articleJob?.cancel(); articleJob = null
        articleLoading.value = false; pendingArticle.value = null
    }
    fun readArticle(link: String) {
        cancelArticle()
        articleLink.value = link; articleError.value = null
        showLinkDialog.value = true; articleLoading.value = true
        val generation = articleGeneration
        articleJob = viewModelScope.launch {
            try {
                val article = com.brahmadeo.supertonic.tts.article.ArticleLoader.load(link)
                inputText.value = article.text
                pendingArticle.value = article.text
                showLinkDialog.value = false
                android.util.Log.i("ArticleReader", "Extracted article chars=${article.text.length}; pending selected playback pipeline")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                articleError.value = e.message ?: "Не удалось загрузить статью. Проверьте соединение."
            } finally { if (generation == articleGeneration) articleLoading.value = false }
        }
    }
    // UI State
    var inputText = mutableStateOf("")
    var isInitializing = mutableStateOf(true)
    var isSynthesizing = mutableStateOf(false)
    var canResume = mutableStateOf(false)

    // Settings State
    var currentLang = mutableStateOf(DEFAULT_LANG)
    var selectedVoiceFile = mutableStateOf(DEFAULT_VOICE)
    var selectedVoiceFile2 = mutableStateOf(DEFAULT_VOICE_2)
    var isMixingEnabled = mutableStateOf(false)
    var mixAlpha = mutableFloatStateOf(0.5f)
    var currentSpeed = mutableFloatStateOf(1.1f)
    var currentSteps = mutableIntStateOf(5)
    var isAdvancedNormalizationEnabled = mutableStateOf(false)

    // Mini Player State
    var showMiniPlayer = mutableStateOf(false)
    var miniPlayerTitle = mutableStateOf("Now Playing")
    var miniPlayerIsPlaying = mutableStateOf(false)

    // Asset Download State
    var isDownloading = mutableStateOf(false)
    var downloadProgress = mutableFloatStateOf(0f)
    var downloadStatus = mutableStateOf("Checking assets...")
    var downloadError = mutableStateOf<String?>(null)
    var showModelSelection = mutableStateOf(false)
    var selectedModel = mutableStateOf("standard")

    // Dialog State
    var showQueueDialog = mutableStateOf(false)
    var queueDialogText = ""
    var showModelDeleteDialog = mutableStateOf(false)

    // Update State — non-null when a newer GitHub release is available.
    var availableUpdate = mutableStateOf<com.brahmadeo.supertonic.tts.utils.UpdateChecker.Update?>(null)

    // Data
    val voiceFiles = mutableStateMapOf<String, String>()

    companion object {
        const val DEFAULT_VOICE = "F3.json"
        const val DEFAULT_VOICE_2 = "M2.json"
        const val DEFAULT_LANG = "en"
        const val DEFAULT_SPEED = 1.1f
        const val DEFAULT_STEPS = 3
    }
}
