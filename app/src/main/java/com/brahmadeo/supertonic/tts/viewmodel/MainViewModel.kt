package com.brahmadeo.supertonic.tts.viewmodel

import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel

class MainViewModel : ViewModel() {
    // UI State
    var inputText = mutableStateOf("")
    var isInitializing = mutableStateOf(true)
    var isSynthesizing = mutableStateOf(false)

    // Settings State
    var currentLang = mutableStateOf("en")
    var selectedVoiceFile = mutableStateOf("F3.json")
    var selectedVoiceFile2 = mutableStateOf("M2.json")
    var isMixingEnabled = mutableStateOf(false)
    var mixAlpha = mutableFloatStateOf(0.5f)
    var currentSpeed = mutableFloatStateOf(1.0f)
    var currentSteps = mutableIntStateOf(5)
    var intraThreads = mutableIntStateOf(4)
    var engineType = mutableStateOf("Detecting...")

    // Mini Player State
    var showMiniPlayer = mutableStateOf(false)
    var miniPlayerTitle = mutableStateOf("Now Playing")
    var miniPlayerIsPlaying = mutableStateOf(false)

    // Asset Download State
    var isDownloading = mutableStateOf(false)
    var downloadingVersion = mutableStateOf("v1")
    var downloadProgress = mutableFloatStateOf(0f)
    var downloadStatus = mutableStateOf("Checking assets...")
    var downloadError = mutableStateOf<String?>(null)

    // Dialog State
    var showQueueDialog = mutableStateOf(false)
    var queueDialogText = ""
    var showV2ConfirmDialog = mutableStateOf(false)
    var showV2DeleteDialog = mutableStateOf(false)
    var showThreadsDialog = mutableStateOf(false)
    var pendingLangCode = ""

    // Data
    val voiceFiles = mutableStateMapOf<String, String>()
}