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
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        val position = data.dataPosition()
        var id: Long? = null
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
                            id = LlmPreparation.submit(context, caller, text, mode != TextToSpeech.QUEUE_ADD)
                            if(mode == TextToSpeech.QUEUE_ADD) {
                                queuedText=text
                                queuedParams=if(data.readInt()!=0) android.os.Bundle.CREATOR.createFromParcel(data) else null
                            } else ReaderAudioAhead.cancel()
                        }
                    } else { LlmPreparation.cancel(caller); ReaderAudioAhead.cancel() }
                }
            } catch (_: Exception) {
                queuedText=null
                Log.w("LlmPreparation", "Queue observation unavailable; using ordinary synthesis")
            } finally { data.setDataPosition(position) }
        }
        try {
            val result = delegate.transact(code, data, reply, flags)
            if ((id != null || queuedText != null) && reply != null) {
                val replyPosition = reply.dataPosition()
                try { reply.setDataPosition(0); reply.readException(); if (reply.readInt() != TextToSpeech.SUCCESS) { LlmPreparation.rejected(id); queuedText=null } }
                finally { reply.setDataPosition(replyPosition) }
            }
            if(result && queuedText!=null) ReaderAudioAhead.submit(context,queuedText!!,queuedParams)
            return result
        } catch (t: Throwable) { LlmPreparation.rejected(id); throw t }
    }
}
