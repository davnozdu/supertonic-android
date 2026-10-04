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
class TtsQueueObserver(private val context: Context, private val delegate: IBinder) : Binder() {
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
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == FIRST_CALL_TRANSACTION && com.brahmadeo.supertonic.tts.service.CallInterruption.active()) {
            reply?.writeNoException(); reply?.writeInt(TextToSpeech.ERROR)
            return true
        }
        val position = data.dataPosition()
        if(Build.VERSION.SDK_INT in 24..36 && code==FIRST_CALL_TRANSACTION+11) {
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
        var id: Long? = null
        var musicOwner: IBinder? = null
        var musicToken: Long? = null
        var queuedText: String? = null
        var queuedParams: android.os.Bundle? = null
        if (Build.VERSION.SDK_INT in 24..36 && (code == FIRST_CALL_TRANSACTION || code == FIRST_CALL_TRANSACTION + 5)) {
            try {
                data.enforceInterface("android.speech.tts.ITextToSpeechService")
                val caller = data.readStrongBinder()
                if (caller != null) {
                    if (code == FIRST_CALL_TRANSACTION) {
                        val text = if (data.readInt() != 0) TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(data)?.toString() else null
                        val mode = data.readInt()
                        if (text != null) {
                            val params=if(data.readInt()!=0) android.os.Bundle.CREATOR.createFromParcel(data) else null
                            val utteranceId=data.readString()
                            if (!VoicePreview.requested(context, params, getCallingUid())) {
                                readingOwners.add(caller)
                                id = LlmPreparation.submit(context, caller, text, mode != TextToSpeech.QUEUE_ADD)
                                musicOwner=caller
                                musicToken=com.brahmadeo.supertonic.tts.music.BackgroundMusic.enqueue(context,caller,utteranceId,mode!=TextToSpeech.QUEUE_ADD)
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
