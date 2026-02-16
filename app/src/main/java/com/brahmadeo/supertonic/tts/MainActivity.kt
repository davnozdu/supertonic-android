package com.brahmadeo.supertonic.tts

import android.Manifest
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import com.brahmadeo.supertonic.tts.service.IPlaybackListener
import com.brahmadeo.supertonic.tts.service.IPlaybackService
import com.brahmadeo.supertonic.tts.service.PlaybackService
import com.brahmadeo.supertonic.tts.ui.MainScreen
import com.brahmadeo.supertonic.tts.ui.theme.SupertonicTheme
import com.brahmadeo.supertonic.tts.utils.HistoryManager
import com.brahmadeo.supertonic.tts.utils.LexiconManager
import com.brahmadeo.supertonic.tts.utils.QueueManager
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

import androidx.activity.viewModels
import com.brahmadeo.supertonic.tts.viewmodel.MainViewModel
import com.brahmadeo.supertonic.tts.ui.DownloadScreen
import com.brahmadeo.supertonic.tts.utils.AssetManager

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    // Data
    private val languages = mapOf(
        "English" to "en",
        "French" to "fr",
        "Portuguese" to "pt",
        "Spanish" to "es",
        "Korean" to "ko"
    )

    private var currentModelVersion = "v1" // "v1" or "v2"

    // Service
    private var playbackService: IPlaybackService? = null
    private var isBound = false

    private val playbackListener = object : IPlaybackListener.Stub() {
        override fun onStateChanged(isPlaying: Boolean, hasContent: Boolean, isSynthesizing: Boolean) {
            runOnUiThread {
                viewModel.miniPlayerIsPlaying.value = isPlaying
                viewModel.isSynthesizing.value = isSynthesizing
                if (hasContent || isSynthesizing) {
                    viewModel.showMiniPlayer.value = true
                    val lastText = getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE).getString("last_text", "")
                    if (!lastText.isNullOrEmpty()) {
                        viewModel.miniPlayerTitle.value = lastText
                    }
                } else {
                    viewModel.showMiniPlayer.value = false
                }
            }
        }
        override fun onProgress(current: Int, total: Int) { }
        override fun onPlaybackStopped() {
            runOnUiThread {
                viewModel.showMiniPlayer.value = false
                viewModel.miniPlayerIsPlaying.value = false
            }
        }
        override fun onExportComplete(success: Boolean, path: String) { }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            playbackService = IPlaybackService.Stub.asInterface(service)
            isBound = true
            try {
                playbackService?.setListener(playbackListener)
            } catch (e: Exception) { e.printStackTrace() }
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            playbackService = null
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private val historyLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val selectedText = result.data?.getStringExtra("selected_text")
            if (!selectedText.isNullOrEmpty()) {
                viewModel.inputText.value = selectedText
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        loadPreferences()
        checkNotificationPermission()

        val bindIntent = Intent(this, PlaybackService::class.java)
        bindService(bindIntent, connection, Context.BIND_AUTO_CREATE)

        LexiconManager.load(this)
        QueueManager.initialize(this)

        // Initial setup based on saved language
        val savedLang = getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE).getString("selected_lang", "en") ?: "en"
        currentModelVersion = if (savedLang == "en") "v1" else "v2"

        // On FIRST LAUNCH, we check/download the required version.
        // If English (default), we ensure V1 is ready.
        // If they managed to switch language before assets were ready (unlikely), we check that version.
        if (currentModelVersion == "v1") {
            if (!AssetManager.isV1Ready(this)) {
                startDownload("v1")
            } else {
                initializeEngine("v1")
            }
        } else {
            if (!AssetManager.isV2Ready(this)) {
                startDownload("v2")
            } else {
                initializeEngine("v2")
            }
        }

        handleIntent(intent)
        checkResumeState()

        setContent {
            SupertonicTheme {
                if (viewModel.isDownloading.value) {
                    DownloadScreen(
                        status = viewModel.downloadStatus.value,
                        progress = viewModel.downloadProgress.value,
                        version = viewModel.downloadingVersion.value,
                        error = viewModel.downloadError.value,
                        onRetry = { startDownload(viewModel.downloadingVersion.value) }
                    )
                } else {
                    if (viewModel.showQueueDialog.value) {
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = { viewModel.showQueueDialog.value = false },
                            title = { Text(getString(R.string.playback_active_title)) },
                            text = { Text(getString(R.string.playback_active_message)) },
                            confirmButton = {
                                TextButton(onClick = {
                                    addToQueue(viewModel.queueDialogText)
                                    viewModel.showQueueDialog.value = false
                                }) { Text(getString(R.string.add_to_queue)) }
                            },
                            dismissButton = {
                                TextButton(onClick = {
                                    playNow(viewModel.queueDialogText)
                                    viewModel.showQueueDialog.value = false
                                }) { Text(getString(R.string.play_now)) }
                            }
                        )
                    }

                    if (viewModel.showV2ConfirmDialog.value) {
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = { 
                                viewModel.showV2ConfirmDialog.value = false
                                viewModel.currentLang.value = "en"
                                saveStringPref("selected_lang", "en")
                                switchModel("v1")
                            },
                            title = { Text("Download Multilingual Models?") },
                            text = { Text("Supporting French, Spanish, Portuguese, and Korean requires a additional download (~350MB). Do you want to download it now?") },
                            confirmButton = {
                                TextButton(onClick = {
                                    val lang = viewModel.pendingLangCode
                                    viewModel.currentLang.value = lang
                                    saveStringPref("selected_lang", lang)
                                    viewModel.showV2ConfirmDialog.value = false
                                    switchModel("v2")
                                }) { Text("Download") }
                            },
                            dismissButton = {
                                TextButton(onClick = {
                                    viewModel.showV2ConfirmDialog.value = false
                                    viewModel.currentLang.value = "en"
                                    saveStringPref("selected_lang", "en")
                                    switchModel("v1")
                                }) { Text("Cancel") }
                            }
                        )
                    }

                    if (viewModel.showV2DeleteDialog.value) {
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = { viewModel.showV2DeleteDialog.value = false },
                            title = { Text("Delete Multilingual Models?") },
                            text = { Text("This will remove all multilingual models and voice styles to free up space (~350MB). You can download them again later if needed.") },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        AssetManager.deleteVersion(this@MainActivity, "v2")
                                        viewModel.showV2DeleteDialog.value = false
                                        // Ensure we are on English/V1
                                        viewModel.currentLang.value = "en"
                                        saveStringPref("selected_lang", "en")
                                        switchModel("v1")
                                        Toast.makeText(this@MainActivity, "Multilingual models deleted", Toast.LENGTH_SHORT).show()
                                    },
                                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                ) { Text("Delete") }
                            },
                            dismissButton = {
                                TextButton(onClick = { viewModel.showV2DeleteDialog.value = false }) { Text("Cancel") }
                            }
                        )
                    }

                    // Get localized placeholder
                    val placeholder = getLocalizedResource(this, viewModel.currentLang.value, R.string.default_input_text)

                    MainScreen(
                        inputText = viewModel.inputText.value,
                        onInputTextChange = { viewModel.inputText.value = it },
                        placeholderText = placeholder,
                        isInitializing = viewModel.isInitializing.value,
                        isSynthesizing = viewModel.isSynthesizing.value,
                        onSynthesizeClick = {
                            val textToPlay = viewModel.inputText.value.ifEmpty { placeholder }
                            generateAndPlay(textToPlay)
                        },

                        languages = languages,
                        currentLangCode = viewModel.currentLang.value,
                        onLangChange = {
                            if (it == "en") {
                                viewModel.currentLang.value = it
                                saveStringPref("selected_lang", it)
                                switchModel("v1")
                            } else {
                                if (AssetManager.isV2Ready(this@MainActivity)) {
                                    viewModel.currentLang.value = it
                                    saveStringPref("selected_lang", it)
                                    switchModel("v2")
                                } else {
                                    viewModel.pendingLangCode = it
                                    viewModel.showV2ConfirmDialog.value = true
                                }
                            }
                        },

                        voices = viewModel.voiceFiles,
                        selectedVoiceFile = viewModel.selectedVoiceFile.value,
                        onVoiceChange = {
                            if (viewModel.selectedVoiceFile.value != it) {
                                viewModel.selectedVoiceFile.value = it
                                saveStringPref("selected_voice", it)
                                val resetIntent = Intent(this, PlaybackService::class.java).apply { action = "RESET_ENGINE" }
                                startService(resetIntent)
                            }
                        },

                        isMixingEnabled = viewModel.isMixingEnabled.value,
                        onMixingEnabledChange = { 
                            viewModel.isMixingEnabled.value = it
                            getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE).edit().putBoolean("is_mixing_enabled", it).apply()
                        },
                        selectedVoiceFile2 = viewModel.selectedVoiceFile2.value,
                        onVoice2Change = {
                            viewModel.selectedVoiceFile2.value = it
                            saveStringPref("selected_voice_2", it)
                        },
                        mixAlpha = viewModel.mixAlpha.value,
                        onMixAlphaChange = { 
                            viewModel.mixAlpha.value = it
                            getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE).edit().putFloat("mix_alpha", it).apply()
                        },

                        speed = viewModel.currentSpeed.value,
                        onSpeedChange = { viewModel.currentSpeed.value = it },
                        steps = viewModel.currentSteps.value,
                        onStepsChange = {
                            viewModel.currentSteps.value = it
                            getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE).edit().putInt("diffusion_steps", it).apply()
                        },
                        ortThreads = viewModel.ortThreads.intValue,
                        xnnThreads = viewModel.xnnThreads.intValue,
                        showThreadsDialog = viewModel.showThreadsDialog.value,
                        onShowThreadsDialogChange = { viewModel.showThreadsDialog.value = it },
                        onThreadsChange = { ort, xnn ->
                            viewModel.ortThreads.intValue = ort
                            viewModel.xnnThreads.intValue = xnn
                            getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE).edit()
                                .putInt("ort_threads", ort)
                                .putInt("xnn_threads", xnn)
                                .apply()
                            // Re-initialize engine with new thread counts
                            initializeEngine(currentModelVersion)
                        },
                        engineType = viewModel.engineType.value,

                        onResetClick = {
                            viewModel.inputText.value = ""
                            val stopIntent = Intent(this, PlaybackService::class.java).apply { action = "STOP_PLAYBACK" }
                            startService(stopIntent)
                        },
                        onSavedAudioClick = { startActivity(Intent(this, SavedAudioActivity::class.java)) },
                        onHistoryClick = { historyLauncher.launch(Intent(this, HistoryActivity::class.java)) },
                        onQueueClick = { startActivity(Intent(this, QueueActivity::class.java)) },
                        onLexiconClick = { startActivity(Intent(this, LexiconActivity::class.java)) },
                        onDeleteV2Click = { viewModel.showV2DeleteDialog.value = true },
                        isV2Ready = AssetManager.isV2Ready(this),

                        showMiniPlayer = viewModel.showMiniPlayer.value,
                        miniPlayerTitle = viewModel.miniPlayerTitle.value,
                        miniPlayerIsPlaying = viewModel.miniPlayerIsPlaying.value,
                        onMiniPlayerClick = {
                            val intent = Intent(this, PlaybackActivity::class.java)
                            intent.flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                            startActivity(intent)
                        },
                        onMiniPlayerPlayPauseClick = {
                             if (playbackService?.isServiceActive == true) {
                                try {
                                    if (viewModel.miniPlayerIsPlaying.value) playbackService?.pause() else playbackService?.play()
                                } catch (e: Exception) { e.printStackTrace() }
                            }
                        }
                    )
                }
            }
        }
    }

    private fun loadPreferences() {
        val prefs = getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE)
        viewModel.currentLang.value = prefs.getString("selected_lang", "en") ?: "en"
        viewModel.selectedVoiceFile.value = prefs.getString("selected_voice", "F3.json") ?: "F3.json"
        viewModel.selectedVoiceFile2.value = prefs.getString("selected_voice_2", "M2.json") ?: "M2.json"
        viewModel.isMixingEnabled.value = prefs.getBoolean("is_mixing_enabled", false)
        viewModel.mixAlpha.value = prefs.getFloat("mix_alpha", 0.5f)
        viewModel.currentSpeed.value = prefs.getFloat("speed", 1.0f)
        viewModel.currentSteps.value = prefs.getInt("diffusion_steps", 5)
        viewModel.ortThreads.intValue = prefs.getInt("ort_threads", 4)
        viewModel.xnnThreads.intValue = prefs.getInt("xnn_threads", 1)
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun getLocalizedResource(context: Context, lang: String, resId: Int): String {
        val locale = java.util.Locale(lang)
        val config = android.content.res.Configuration(context.resources.configuration)
        config.setLocale(locale)
        val localizedContext = context.createConfigurationContext(config)
        return localizedContext.resources.getString(resId)
    }

    private fun saveStringPref(key: String, value: String) {
        getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE).edit()
            .putString(key, value)
            .apply()
    }

    private fun startDownload(version: String) {
        viewModel.isDownloading.value = true
        viewModel.downloadingVersion.value = version
        viewModel.downloadError.value = null
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (version == "v1") {
                    AssetManager.downloadV1(this@MainActivity) { status, progress ->
                        runOnUiThread {
                            viewModel.downloadStatus.value = status
                            viewModel.downloadProgress.value = progress
                        }
                    }
                } else {
                    AssetManager.downloadV2(this@MainActivity) { status, progress ->
                        runOnUiThread {
                            viewModel.downloadStatus.value = status
                            viewModel.downloadProgress.value = progress
                        }
                    }
                }
                
                withContext(Dispatchers.Main) {
                    viewModel.isDownloading.value = false
                    initializeEngine(version)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    viewModel.downloadError.value = e.message ?: "Unknown error"
                    Log.e("MainActivity", "Download failed", e)
                }
            }
        }
    }

    private fun initializeEngine(version: String) {
        currentModelVersion = version
        viewModel.isInitializing.value = true
        
        CoroutineScope(Dispatchers.IO).launch {
            withContext(Dispatchers.Main) {
                setupVoicesMap(version)
            }
            
            // Force release of any existing engine to ensure we load the new model path
            SupertonicTTS.release()
            
            val modelPath = File(filesDir, "$version/onnx").absolutePath
            val libPath = applicationInfo.nativeLibraryDir + "/libonnxruntime.so"
            val ortThreads = viewModel.ortThreads.intValue
            val xnnThreads = viewModel.xnnThreads.intValue

            if (SupertonicTTS.initialize(modelPath, libPath, ortThreads, xnnThreads)) {
                withContext(Dispatchers.Main) {
                    viewModel.isInitializing.value = false
                    val type = if (SupertonicTTS.isXnnpackSupported()) "XNNPACK" else "CPU"
                    viewModel.engineType.value = "$type : $ortThreads|$xnnThreads Cores"
                }
            }
        }
    }

    private fun switchModel(version: String) {
        if (currentModelVersion == version) return
        
        // Lazy Check
        val isReady = if (version == "v1") AssetManager.isV1Ready(this) else AssetManager.isV2Ready(this)
        
        if (!isReady) {
            // Trigger Download
            startDownload(version)
        } else {
            // Instant Switch
            initializeEngine(version)
            Toast.makeText(this, "Switched to model $version", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupVoicesMap(version: String) {
        val voiceResources = mapOf(
            "M1.json" to R.string.voice_m1,
            "M2.json" to R.string.voice_m2,
            "M3.json" to R.string.voice_m3,
            "M4.json" to R.string.voice_m4,
            "M5.json" to R.string.voice_m5,
            "F1.json" to R.string.voice_f1,
            "F2.json" to R.string.voice_f2,
            "F3.json" to R.string.voice_f3,
            "F4.json" to R.string.voice_f4,
            "F5.json" to R.string.voice_f5
        )

        voiceResources.forEach { (filename, resId) ->
            viewModel.voiceFiles[getString(resId)] = filename
        }

        // Check dynamic dir for default listing
        val voiceDir = File(filesDir, "$version/voice_styles")
        if (voiceDir.exists()) {
            val files = voiceDir.listFiles { _, name -> name.endsWith(".json") }
            files?.forEach { file ->
                if (!voiceResources.containsKey(file.name)) {
                    val friendlyName = file.name.removeSuffix(".json")
                    viewModel.voiceFiles[friendlyName] = file.name
                }
            }
        }
    }

    // ... (generateAndPlay, addToQueue, playNow, launchPlaybackActivity, handleIntent, checkResumeState, lifecycle methods same as before) ...
    // Note: I will include the full file content to ensure no missing braces.

    private fun generateAndPlay(text: String) {
        val isReady = if (currentModelVersion == "v1") AssetManager.isV1Ready(this) else AssetManager.isV2Ready(this)
        if (!isReady) {
            startDownload(currentModelVersion)
            return
        }

        if (viewModel.isInitializing.value) return

        var stylePath = File(filesDir, "$currentModelVersion/voice_styles/${viewModel.selectedVoiceFile.value}").absolutePath
        if (!File(stylePath).exists()) {
             // This case should be covered by isReady, but as a fallback:
             startDownload(currentModelVersion)
             return
        }

        if (viewModel.isMixingEnabled.value) {
            val stylePath2 = File(filesDir, "$currentModelVersion/voice_styles/${viewModel.selectedVoiceFile2.value}").absolutePath
            if (File(stylePath2).exists()) {
                stylePath = "$stylePath;$stylePath2;${viewModel.mixAlpha.value}"
            }
        }
        
        val v1Name = viewModel.voiceFiles.entries.find { it.value == viewModel.selectedVoiceFile.value }?.key ?: "Voice 1"
        val v2Name = viewModel.voiceFiles.entries.find { it.value == viewModel.selectedVoiceFile2.value }?.key ?: "Voice 2"
        val voiceName = if (viewModel.isMixingEnabled.value) "Mixed: $v1Name + $v2Name" else v1Name

        HistoryManager.saveItem(this, text, voiceName)

        try {
            if (playbackService?.isServiceActive == true) {
                viewModel.queueDialogText = text
                viewModel.showQueueDialog.value = true
            } else {
                launchPlaybackActivity(text, stylePath)
            }
        } catch (e: Exception) {
            launchPlaybackActivity(text, stylePath)
        }
    }

    private fun addToQueue(text: String) {
        val isReady = if (currentModelVersion == "v1") AssetManager.isV1Ready(this) else AssetManager.isV2Ready(this)
        if (!isReady) {
            startDownload(currentModelVersion)
            return
        }

        if (viewModel.isInitializing.value) return

        var stylePath = File(filesDir, "$currentModelVersion/voice_styles/${viewModel.selectedVoiceFile.value}").absolutePath
        if (viewModel.isMixingEnabled.value) {
            val stylePath2 = File(filesDir, "$currentModelVersion/voice_styles/${viewModel.selectedVoiceFile2.value}").absolutePath
            stylePath = "$stylePath;$stylePath2;${viewModel.mixAlpha.value}"
        }

        try {
            playbackService?.addToQueue(
                text,
                viewModel.currentLang.value,
                stylePath,
                viewModel.currentSpeed.value,
                viewModel.currentSteps.value,
                0
            )
            Toast.makeText(this, getString(R.string.added_to_queue), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun playNow(text: String) {
        val isReady = if (currentModelVersion == "v1") AssetManager.isV1Ready(this) else AssetManager.isV2Ready(this)
        if (!isReady) {
            startDownload(currentModelVersion)
            return
        }

        if (viewModel.isInitializing.value) return

        var stylePath = File(filesDir, "$currentModelVersion/voice_styles/${viewModel.selectedVoiceFile.value}").absolutePath
        if (viewModel.isMixingEnabled.value) {
            val stylePath2 = File(filesDir, "$currentModelVersion/voice_styles/${viewModel.selectedVoiceFile2.value}").absolutePath
            stylePath = "$stylePath;$stylePath2;${viewModel.mixAlpha.value}"
        }
        launchPlaybackActivity(text, stylePath)
    }

    private fun launchPlaybackActivity(text: String, stylePath: String) {
        val intent = Intent(this, PlaybackActivity::class.java).apply {
            putExtra(PlaybackActivity.EXTRA_TEXT, text)
            putExtra(PlaybackActivity.EXTRA_VOICE_PATH, stylePath)
            putExtra(PlaybackActivity.EXTRA_SPEED, viewModel.currentSpeed.value)
            putExtra(PlaybackActivity.EXTRA_STEPS, viewModel.currentSteps.value)
            putExtra(PlaybackActivity.EXTRA_LANG, viewModel.currentLang.value)
        }
        startActivity(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!sharedText.isNullOrEmpty()) {
                viewModel.inputText.value = sharedText
            }
        } else {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: intent.data?.getQueryParameter("text")
            if (!text.isNullOrEmpty()) {
                viewModel.inputText.value = text
            }
        }
    }

    private fun checkResumeState() {
        if (viewModel.isDownloading.value) return

        val prefs = getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE)
        val lastText = prefs.getString("last_text", null)
        val isPlaying = prefs.getBoolean("is_playing", false)

        if (!lastText.isNullOrEmpty() && isPlaying) {
             AlertDialog.Builder(this)
                .setTitle(getString(R.string.resume_title))
                .setMessage(getString(R.string.resume_message))
                .setPositiveButton(getString(R.string.yes)) { _, _ ->
                    val intent = Intent(this, PlaybackActivity::class.java)
                    intent.putExtra("is_resume", true)
                    startActivity(intent)
                }
                .setNegativeButton(getString(R.string.no)) { _, _ ->
                    getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE).edit()
                        .putBoolean("is_playing", false)
                        .apply()
                    val stopIntent = Intent(this, PlaybackService::class.java)
                    stopIntent.action = "STOP_PLAYBACK"
                    startService(stopIntent)
                }
                .show()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }
}