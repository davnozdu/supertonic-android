package com.brahmadeo.supertonic.tts.llm

import android.content.Context
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.speech.tts.TextToSpeech
import android.text.TextUtils
import android.util.Log

/** Observe incoming queue; forward every original transaction and callback unchanged.
 * Internal AOSP protocol: unknown SDKs or decoding failures fall back to ordinary TTS.
 */
/** Measured Kokoro/Silero speech at the default rate: about 55-70 ms per character. */
private const val FILE_SPEECH_MS_PER_CHAR = 70L
/** Highest SDK whose TTS binder protocol was checked against its framework.jar (Android 17: 26.10.2026). */
private const val MAX_VERIFIED_SDK = 37

class TtsQueueObserver(private val context: Context, private val delegate: IBinder) : Binder() {
    @Volatile private var lastFileChars = 0
    @Volatile private var lastFileCharsBefore = 0
    @Volatile private var lastFileAt = 0L
    private val musicCallbacks = HashMap<IBinder, com.brahmadeo.supertonic.tts.music.MusicTtsCallback>()
    private val readingOwners = java.util.concurrent.ConcurrentHashMap.newKeySet<IBinder>()
    /** The local callback proxy cannot die with the remote client; preserve framework cleanup explicitly. */
    private fun deadClient(owner: IBinder, dead: com.brahmadeo.supertonic.tts.music.MusicTtsCallback) {
        val removed=synchronized(musicCallbacks) {
            if(musicCallbacks[owner]===dead) { musicCallbacks.remove(owner);true } else false
        }
        if(!removed) return
        if (readingOwners.remove(owner)) ReaderAudioAhead.cancel()
        LlmPreparation.cancel(owner)
        com.brahmadeo.supertonic.tts.books.BookMatcher.forget(owner)
        for(code in listOf(FIRST_CALL_TRANSACTION+5,FIRST_CALL_TRANSACTION+11)) {
            val data=Parcel.obtain();val reply=Parcel.obtain()
            try {
                data.writeInterfaceToken("android.speech.tts.ITextToSpeechService")
                data.writeStrongBinder(owner)
                if(code==FIRST_CALL_TRANSACTION+11) data.writeStrongBinder(null)
                data.setDataPosition(0)
                delegate.transact(code,data,reply,0)
            } catch (_: Exception) { Log.w("BackgroundMusic","Client-death cleanup unavailable") }
            finally { data.recycle();reply.recycle() }
        }
    }
    fun stopForCall() {
        ReaderAudioAhead.cancel()
        val owners = synchronized(musicCallbacks) { musicCallbacks.keys.toList() }
        for (owner in owners) {
            LlmPreparation.cancel(owner)
            com.brahmadeo.supertonic.tts.music.BackgroundMusic.stop(owner)
            val data = Parcel.obtain(); val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken("android.speech.tts.ITextToSpeechService")
                data.writeStrongBinder(owner); data.setDataPosition(0)
                delegate.transact(FIRST_CALL_TRANSACTION + 5, data, reply, 0)
            } catch (_: Exception) { Log.w("CallInterruption", "TTS client stop failed") }
            finally { data.recycle(); reply.recycle() }
        }
    }
    fun close() { synchronized(musicCallbacks) {
        musicCallbacks.values.forEach { it.detach() }; musicCallbacks.clear()
        readingOwners.clear()
    } }
    /** The ITextToSpeechService / ITextToSpeechCallback transaction codes and argument order read below, checked in the
     * phone's own framework.jar up to Android 17 (SDK 37). Before that the check stopped at 36 and on the Android 17 OTA the
     * observer silently switched off: no background music, no LLM preparation ahead, no sleep timer for readers. */
    private fun protocolKnown(): Boolean {
        val known = Build.VERSION.SDK_INT in 24..MAX_VERIFIED_SDK
        if (!known && !warned) { warned = true; Log.w("LlmPreparation", "TTS protocol of SDK ${Build.VERSION.SDK_INT} not verified: reader queue observation, background music and LLM preparation are off") }
        return known
    }
    @Volatile private var warned = false

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == FIRST_CALL_TRANSACTION && com.brahmadeo.supertonic.tts.service.CallInterruption.active()) {
            reply?.writeNoException(); reply?.writeInt(TextToSpeech.ERROR)
            return true
        }
        val position = data.dataPosition()
        if(protocolKnown() && code==FIRST_CALL_TRANSACTION+11) {
            var rewritten: Parcel? = null
            try {
                data.enforceInterface("android.speech.tts.ITextToSpeechService")
                val owner=data.readStrongBinder()
                val callback=data.readStrongBinder()
                if(owner!=null) {
                    com.brahmadeo.supertonic.tts.music.BackgroundMusic.initialize(context)
                    val proxy=synchronized(musicCallbacks) {
                        musicCallbacks.remove(owner)?.detach()
                        callback?.let { com.brahmadeo.supertonic.tts.music.MusicTtsCallback(owner,it) { dead -> deadClient(owner,dead) }.also { musicCallbacks[owner]=it } }
                    }
                    if(callback==null) com.brahmadeo.supertonic.tts.music.BackgroundMusic.stop(owner)
                    rewritten=Parcel.obtain().apply {
                        writeInterfaceToken("android.speech.tts.ITextToSpeechService")
                        writeStrongBinder(owner)
                        writeStrongBinder(proxy)
                        setDataPosition(0)
                    }
                }
            } catch (_: Exception) { Log.w("BackgroundMusic","Callback registration observation unavailable") }
            finally { data.setDataPosition(position) }
            if(rewritten!=null) {
                try { return delegate.transact(code,rewritten,reply,flags) }
                finally { rewritten.recycle() }
            }
        }
        // synthesizeToFileDescriptor (KOReader's audiobook plugin): the app writes one sentence to a file, plays it
        // and only then asks for the next, so nothing is queued ahead and every sentence waited ~1.5 s for the cloud
        // LLM and the roles. Treated like a one-at-a-time reader (Readest): the dictionaries read it at once.
        if (protocolKnown() && code == FIRST_CALL_TRANSACTION + 1 && getCallingUid() != context.applicationInfo.uid) {
            try {
                data.enforceInterface("android.speech.tts.ITextToSpeechService")
                val caller = data.readStrongBinder()
                val text = if (data.readInt() != 0) TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(data)?.toString() else null
                // No reading notification: the app plays the file itself, MyTTS's pause would not reach it.
                if (caller != null && text != null) {
                    com.brahmadeo.supertonic.tts.service.ForeignSpeech.note(getCallingUid())
                    if (LlmPreparation.directReader(context, caller, getCallingUid(), text, true)) LlmPreparation.cancel(caller)
                    // The reader never says when its file plays or pauses. With its lookahead this request comes as the
                    // previous sentence starts; without it, this sentence plays next. Cover the longer one plus the gap.
                    val chars = maxOf(text.length, lastFileChars); lastFileChars = text.length
                    // When it is the plugin's lookahead, this request arrives while the previous sentence is still
                    // sounding: the sentence asked for now plays only after that one ends, and its sounds must wait
                    // for it too — otherwise every creak and door comes a sentence early.
                    val now = android.os.SystemClock.uptimeMillis()
                    val estPrev = lastFileCharsBefore * FILE_SPEECH_MS_PER_CHAR
                    val gap = if (lastFileAt == 0L) 0L else now - lastFileAt
                    val pending = if (gap in 1 until (estPrev * 7 / 10)) (estPrev - gap).coerceAtMost(20_000L) else 0L
                    lastFileCharsBefore = text.length; lastFileAt = now
                    com.brahmadeo.supertonic.tts.music.BackgroundMusic.hold(context, (chars * FILE_SPEECH_MS_PER_CHAR + 4_000L).coerceAtMost(60_000L), caller, text, pending)
                }
            } catch (_: Exception) { Log.w("LlmPreparation", "File synthesis observation unavailable; using ordinary synthesis") }
            finally { data.setDataPosition(position) }
        }
        var id: Long? = null
        var musicOwner: IBinder? = null
        var musicToken: Long? = null
        var queuedText: String? = null
        var queuedParams: android.os.Bundle? = null
        if (protocolKnown() && (code == FIRST_CALL_TRANSACTION || code == FIRST_CALL_TRANSACTION + 5)) {
            try {
                data.enforceInterface("android.speech.tts.ITextToSpeechService")
                val caller = data.readStrongBinder()
                if (caller != null) {
                    if (code == FIRST_CALL_TRANSACTION) {
                        val text = if (data.readInt() != 0) TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(data)?.toString() else null
                        val mode = data.readInt()
                        if (mode != TextToSpeech.QUEUE_ADD) com.brahmadeo.supertonic.tts.service.SleepTimer.manualResume()
                        if (com.brahmadeo.supertonic.tts.service.SleepTimer.blocked) {
                            reply?.writeNoException(); reply?.writeInt(TextToSpeech.ERROR)
                            return true
                        }
                        if (text != null) {
                            val params=if(data.readInt()!=0) android.os.Bundle.CREATOR.createFromParcel(data) else null
                            val utteranceId=data.readString()
                            val foreign = com.brahmadeo.supertonic.tts.service.ForeignSpeech.foreign(context, getCallingUid())
                            com.brahmadeo.supertonic.tts.service.ForeignSpeech.note(getCallingUid())
                            // A message of another app over a reading (MacroDroid reading an SMS): the reader pauses for
                            // it; the reading's own state (ahead audio, music, notification, LLM) is left as it is.
                            // KOReader reads by files; a speak() of it is a short answer of its plugin, not the book.
                            val aside = getCallingUid() == KoreaderAhead.readerUid
                            if (aside) com.brahmadeo.supertonic.tts.service.ForeignSpeech.aside(text)
                            if (foreign) com.brahmadeo.supertonic.tts.service.ForeignSpeech.begin(context)
                            else if (!aside && !VoicePreview.requested(context, params, getCallingUid())) {
                                com.brahmadeo.supertonic.tts.service.ReadingControls.externalStarted(context)
                                readingOwners.add(caller)
                                // A reader that sends one sentence at a time gets no LLM wait (see ReaderQueueStyle).
                                id = if (LlmPreparation.directReader(context, caller, getCallingUid(), text, mode != TextToSpeech.QUEUE_ADD)) {
                                    LlmPreparation.cancel(caller); null
                                } else LlmPreparation.submit(context, caller, text, mode != TextToSpeech.QUEUE_ADD)
                                musicOwner=caller
                                musicToken=com.brahmadeo.supertonic.tts.music.BackgroundMusic.enqueue(context,caller,utteranceId,mode!=TextToSpeech.QUEUE_ADD,text)
                                if(mode == TextToSpeech.QUEUE_ADD) {
                                    queuedText=text
                                    queuedParams=params
                                } else ReaderAudioAhead.cancel()
                            }
                        }
                    } else {
                        com.brahmadeo.supertonic.tts.music.BackgroundMusic.stop(caller)
                        LlmPreparation.cancel(caller)
                        if (caller in readingOwners) ReaderAudioAhead.cancel()
                    }
                }
            } catch (_: Exception) {
                queuedText=null
                Log.w("LlmPreparation", "Queue observation unavailable; using ordinary synthesis")
            } finally { data.setDataPosition(position) }
        }
        try {
            val result = delegate.transact(code, data, reply, flags)
            if ((id != null || queuedText != null || musicToken != null) && reply != null) {
                val replyPosition = reply.dataPosition()
                try { reply.setDataPosition(0); reply.readException(); if (reply.readInt() != TextToSpeech.SUCCESS) {
                    LlmPreparation.rejected(id); queuedText=null
                    musicOwner?.let { owner -> musicToken?.let { com.brahmadeo.supertonic.tts.music.BackgroundMusic.rejected(owner,it) } }
                } }
                finally { reply.setDataPosition(replyPosition) }
            }
            if(result && queuedText!=null) ReaderAudioAhead.submit(context,queuedText!!,queuedParams)
            if(!result) musicOwner?.let { owner -> musicToken?.let { com.brahmadeo.supertonic.tts.music.BackgroundMusic.rejected(owner,it) } }
            return result
        } catch (t: Throwable) {
            musicOwner?.let { owner -> musicToken?.let { com.brahmadeo.supertonic.tts.music.BackgroundMusic.rejected(owner,it) } }
            LlmPreparation.rejected(id); throw t
        }
    }
}
