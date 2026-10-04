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
    var articleWarning = mutableStateOf<String?>(null)
    private var firstArticle: com.brahmadeo.supertonic.tts.article.ReadingArticle? = null
    private var articleJob: Job? = null
    private var articleGeneration = 0L
    fun cancelArticle() {
        articleGeneration++
        com.brahmadeo.supertonic.tts.article.ArticleSession.cancel()
        firstArticle = null
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
                if (generation != articleGeneration) return@launch
                firstArticle = article
                inputText.value = article.text
                pendingArticle.value = article.text
                showLinkDialog.value = false
                android.util.Log.i("ArticleReader", "Extracted article chars=${article.text.length}; pending selected playback pipeline")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (generation == articleGeneration) articleError.value = e.message ?: "Не удалось загрузить статью. Проверьте соединение."
            } finally { if (generation == articleGeneration) articleLoading.value = false }
        }
    }
    fun enqueueContinuations(lang: String, style: String, speed: Float, steps: Int) {
        val first = firstArticle ?: return
        if (first.nextUrl == null) return
        var job: Job? = null
        val session = com.brahmadeo.supertonic.tts.article.ArticleSession.begin { job?.cancel() }
        job = viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            val seen = mutableSetOf(com.brahmadeo.supertonic.tts.article.ArticleContinuation.canonicalUrl(first.sourceUrl))
            var article = first
            var total = first.text.length
            var pages = 1
            try {
                while (article.nextUrl != null && com.brahmadeo.supertonic.tts.article.ArticleSession.current(session)) {
                    val next = article.nextUrl!!
                    check(seen.add(next)) { "Ссылка на продолжение зациклена. Автопереход остановлен." }
                    check(pages < 20) { "Достигнут лимит 20 частей. Следующие части можно открыть отдельно." }
                    val loaded = com.brahmadeo.supertonic.tts.article.ArticleLoader.load(next)
                    check(total + loaded.text.length <= com.brahmadeo.supertonic.tts.article.ArticleExtractor.MAX_TEXT) {
                        "Достигнут лимит 180 000 знаков для цепочки статей. Продолжение можно открыть отдельно."
                    }
                    if (!com.brahmadeo.supertonic.tts.article.ArticleSession.current(session)) break
                    val actualUrl = com.brahmadeo.supertonic.tts.article.ArticleContinuation.canonicalUrl(loaded.sourceUrl)
                    check(seen.add(actualUrl) || actualUrl == next) { "Продолжение перенаправлено на уже прочитанную страницу." }
                    com.brahmadeo.supertonic.tts.utils.QueueManager.add(com.brahmadeo.supertonic.tts.utils.QueueItem(
                        text = loaded.text, lang = lang, stylePath = style, speed = speed, steps = steps))
                    article = loaded; total += loaded.text.length; pages++
                    android.util.Log.i("ArticleReader", "Continuation queued page=$pages totalChars=$total")
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { articleWarning.value = "Продолжение не загружено: ${e.message}" }
            finally { com.brahmadeo.supertonic.tts.article.ArticleSession.complete(session) }
        }
        articleJob = job; job?.start()
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
