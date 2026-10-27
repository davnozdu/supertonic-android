package com.brahmadeo.supertonic.tts.service

import android.content.Context
import android.content.Intent

/** The app the listener reads in, whichever it is (Moon+, KOReader, Readest…): the last app that had text read by MyTTS.
 * «Продолжить чтение» brings it back to the screen by its launcher entry, as the launcher does — no list of readers. An
 * app that uses MyTTS's TTS service is visible to MyTTS (Android 11+ package visibility), so its launcher entry resolves. */
object ReaderApp {
    @Volatile private var lastUid = -1

    /** A reader's synthesis request (not MyTTS itself, not another app's message). */
    fun note(context: Context, uid: Int) { if (uid != context.applicationInfo.uid) lastUid = uid }

    /** Brings the reader to the front; false when none is known or it has no launcher entry. Call from an activity in
     * front (a notification tap): a background start of another app is not allowed. */
    fun open(context: Context): Boolean {
        val uid = lastUid.takeIf { it > 0 } ?: return false
        val pm = context.packageManager
        val launch = pm.getPackagesForUid(uid).orEmpty().firstNotNullOfOrNull { pm.getLaunchIntentForPackage(it) } ?: return false
        return runCatching {
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        }.isSuccess
    }
}
