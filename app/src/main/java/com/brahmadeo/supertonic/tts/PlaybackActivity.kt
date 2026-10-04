package com.brahmadeo.supertonic.tts

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import com.brahmadeo.supertonic.tts.service.IPlaybackListener
import com.brahmadeo.supertonic.tts.service.IPlaybackService
import com.brahmadeo.supertonic.tts.service.PlaybackService
import com.brahmadeo.supertonic.tts.ui.PlaybackScreen
import com.brahmadeo.supertonic.tts.ui.theme.SupertonicTheme
import com.brahmadeo.supertonic.tts.utils.TextNormalizer
import androidx.core.content.edit

class PlaybackActivity : ComponentActivity() {

    private var playbackService: IPlaybackService? = null
    private var isBound = false

    // Reactive State
    private var sentencesState = mutableStateOf<List<String>>(emptyList())
    private var currentIndexState = mutableIntStateOf(-1)
    private var isPlayingState = mutableStateOf(false)
    private var isServiceActiveState = mutableStateOf(false)

    // State persistence
    private var currentText = ""
    private var currentVoicePath = ""
    private var currentSpeed = 1.0f
    private var currentSteps = 5
    private var currentLang = "en"
    private val contentGate = com.brahmadeo.supertonic.tts.utils.PlaybackContentGate()

    companion object {
        const val EXTRA_TEXT = "extra_text"
        const val EXTRA_VOICE_PATH = "extra_voice_path"
        const val EXTRA_SPEED = "extra_speed"
        const val EXTRA_STEPS = "extra_steps"
        const val EXTRA_LANG = "extra_lang"
    }

    private val playbackListenerStub = object : IPlaybackListener.Stub() {
        override fun onStateChanged(isPlaying: Boolean, hasContent: Boolean, isSynthesizing: Boolean) {
            runOnUiThread {
                isPlayingState.value = isPlaying
                isServiceActiveState.value = isPlaying || isSynthesizing
            }
        }

        override fun onProgress(current: Int, total: Int, contentId: String?) {
            runOnUiThread {
                val prefs = getSharedPreferences("SupertonicPrefs", MODE_PRIVATE)
                val text = prefs.getString("last_text", "").orEmpty()
                if (!contentGate.accept(contentId.orEmpty(), text, total)) return@runOnUiThread
                if (text.isNotBlank() && text != currentText) {
                    currentText = text
                    currentVoicePath = prefs.getString("last_voice_path", currentVoicePath).orEmpty()
                    currentLang = prefs.getString("last_lang", currentLang).orEmpty()
                    currentSpeed = prefs.getFloat("last_speed", currentSpeed)
                    currentSteps = prefs.getInt("last_steps", currentSteps)
                    setupList(currentText)
                }
                currentIndexState.intValue = current
                updateIndexState(current)
                if (total > 0 && current !in 0 until total) {
                    clearState()
                }
            }
        }

        override fun onPlaybackStopped() {
            runOnUiThread {
                isPlayingState.value = false
                isServiceActiveState.value = false
            }
        }

    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            playbackService = IPlaybackService.Stub.asInterface(service)
            try {
                playbackService?.setListener(playbackListenerStub)
                isBound = true

                if (intent.getBooleanExtra("is_resume", false)) {
                    val isActive = playbackService?.isServiceActive == true
                    if (isActive) {
                        val serviceIndex = playbackService?.getCurrentIndex() ?: -1
                        if (serviceIndex != -1) {
                            currentIndexState.intValue = serviceIndex
                        }
                    } else {
                        // Not playing in service, but user wants to resume: 
                        // Start playback from the saved index
                        playFromIndex(currentIndexState.intValue)
                    }
                    restoreState()
                } else {
                    startPlaybackFromIntent()
                }
            } catch (e: RemoteException) {
                e.printStackTrace()
            }
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            playbackService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        currentText = intent.getStringExtra(EXTRA_TEXT) ?: ""
        currentVoicePath = intent.getStringExtra(EXTRA_VOICE_PATH) ?: ""
        currentSpeed = intent.getFloatExtra(EXTRA_SPEED, 1.0f)
        currentSteps = intent.getIntExtra(EXTRA_STEPS, 5)
        currentLang = intent.getStringExtra(EXTRA_LANG) ?: "en"

        if (intent.getBooleanExtra("is_resume", false) && currentText.isEmpty()) {
             val prefs = getSharedPreferences("SupertonicPrefs", MODE_PRIVATE)
             currentText = prefs.getString("last_text", "") ?: ""
             currentVoicePath = prefs.getString("last_voice_path", "") ?: ""
             currentSpeed = prefs.getFloat("last_speed", 1.0f)
             currentSteps = prefs.getInt("last_steps", 5)
             currentLang = prefs.getString("last_lang", "en") ?: "en"
             currentIndexState.intValue = prefs.getInt("last_index", 0)
        }

        setupList(currentText)
        if (!intent.getBooleanExtra("is_resume", false)) contentGate.expect(currentText)

        setContent {
            SupertonicTheme(voiceFile = currentVoicePath) {
                PlaybackScreen(
                    sentences = sentencesState.value,
                    currentIndex = currentIndexState.intValue,
                    isPlaying = isPlayingState.value,
                    isServiceActive = isServiceActiveState.value,
                    onBackClick = { finish() },
                    onItemClick = { index -> playFromIndex(index) },
                    onPlayPauseClick = { handlePlayPause() },
                    onStopClick = { handleStop() },
                )
            }
        }

        val intent = Intent(this, PlaybackService::class.java)
        bindService(intent, connection, BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume()
        if (intent.getBooleanExtra("is_resume", false)) {
            val prefs = getSharedPreferences("SupertonicPrefs", MODE_PRIVATE)
            val newText = prefs.getString("last_text", "") ?: ""
            if (newText != currentText) {
                currentText = newText
                currentVoicePath = prefs.getString("last_voice_path", "") ?: ""
                currentSpeed = prefs.getFloat("last_speed", 1.0f)
                currentSteps = prefs.getInt("last_steps", 5)
                currentLang = prefs.getString("last_lang", "en") ?: "en"
                currentIndexState.intValue = prefs.getInt("last_index", 0)
                setupList(currentText)
            }
        }

        if (isBound && playbackService != null) {
            try {
                playbackService?.setListener(playbackListenerStub)
                val serviceIndex = playbackService?.getCurrentIndex() ?: -1
                if (serviceIndex != -1 && !contentGate.awaitingReplacement()) {
                    currentIndexState.intValue = serviceIndex
                }
            } catch (e: RemoteException) {
                e.printStackTrace()
            }
        }
    }

    private fun setupList(text: String) {
        val sentences = com.brahmadeo.supertonic.tts.utils.ReadingTextChunks.split(
            text, currentLang, preservePunctuation = com.brahmadeo.supertonic.tts.utils.AssetManager.isRussianModel(this)
        ).sentences
        sentencesState.value = sentences
    }

    private fun handlePlayPause() {
        try {
            if (isPlayingState.value) {
                playbackService?.pause()
            } else if (isServiceActiveState.value) {
                playbackService?.play()
            } else {
                if (currentIndexState.intValue >= 0) {
                    playFromIndex(currentIndexState.intValue)
                } else {
                    startPlaybackFromIntent()
                }
            }
        } catch (e: RemoteException) {
            e.printStackTrace()
        }
    }

    private fun handleStop() {
        try {
            playbackService?.stop()
        } catch (e: RemoteException) { }
        clearState()
        finish()
    }

    private fun startPlaybackFromIntent() {
        if (currentText.isEmpty()) return
        contentGate.expect(currentText)
        android.util.Log.i("ArticleReader", "Playback request chars=${currentText.length} contentId=${com.brahmadeo.supertonic.tts.utils.PlaybackContentGate.fingerprint(currentText).take(12)}")
        saveState()
        try {
            playbackService?.synthesizeAndPlay(currentText, currentLang, currentVoicePath, currentSpeed, currentSteps, 0)
        } catch (e: RemoteException) {
            e.printStackTrace()
        }
    }

    private fun playFromIndex(index: Int) {
        if (currentText.isEmpty()) return
        contentGate.expect(currentText)
        saveState()
        try {
            playbackService?.synthesizeAndPlay(currentText, currentLang, currentVoicePath, currentSpeed, currentSteps, index)
        } catch (e: RemoteException) {
            e.printStackTrace()
        }
    }

    private fun saveState() {
        getSharedPreferences("SupertonicPrefs", MODE_PRIVATE).edit {
            putString("last_text", currentText)
                .putString("last_voice_path", currentVoicePath)
                .putFloat("last_speed", currentSpeed)
                .putInt("last_steps", currentSteps)
                .putString("last_lang", currentLang)
                .putBoolean("is_playing", true)
        }
    }

    private fun updateIndexState(index: Int) {
        getSharedPreferences("SupertonicPrefs", MODE_PRIVATE).edit {
            putInt("last_index", index)
        }
    }

    private fun clearState() {
        getSharedPreferences("SupertonicPrefs", MODE_PRIVATE).edit {
            putBoolean("is_playing", false)
        }
    }

    private fun restoreState() {
        try {
            if (playbackService?.isServiceActive == false) {
                 playbackListenerStub.onStateChanged(false, true, false)
            }
        } catch (e: RemoteException) { }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            try {
                playbackService?.removeListener(playbackListenerStub)
            } catch (e: Exception) { }
            unbindService(connection)
            isBound = false
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!intent.getBooleanExtra("is_resume", false)) {
            val text = intent.getStringExtra(EXTRA_TEXT) ?: return
            currentText = text
            currentVoicePath = intent.getStringExtra(EXTRA_VOICE_PATH).orEmpty()
            currentSpeed = intent.getFloatExtra(EXTRA_SPEED, 1.0f)
            currentSteps = intent.getIntExtra(EXTRA_STEPS, 5)
            currentLang = intent.getStringExtra(EXTRA_LANG) ?: "en"
            currentIndexState.intValue = -1
            contentGate.expect(text)
            setupList(text)
            if (isBound) startPlaybackFromIntent()
        } else contentGate.resume()
    }
}
