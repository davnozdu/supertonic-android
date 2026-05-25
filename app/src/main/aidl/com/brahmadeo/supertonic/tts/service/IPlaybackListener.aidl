package com.brahmadeo.supertonic.tts.service;

interface IPlaybackListener {
    oneway void onStateChanged(boolean isPlaying, boolean hasContent, boolean isSynthesizing);
    oneway void onProgress(int current, int total);
    oneway void onPlaybackStopped();
}