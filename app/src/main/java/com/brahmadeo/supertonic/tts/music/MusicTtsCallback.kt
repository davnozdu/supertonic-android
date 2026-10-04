package com.brahmadeo.supertonic.tts.music

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.util.Log

/** AOSP callback protocol (SDK 24–36). Forward every callback with its original payload. */
class MusicTtsCallback(private val owner: IBinder, private val delegate: IBinder, private val onDeath: (MusicTtsCallback) -> Unit) : Binder(), IBinder.DeathRecipient {
    init { runCatching { owner.linkToDeath(this,0) } }
    override fun binderDied() { BackgroundMusic.stop(owner); onDeath(this) }
    fun detach() { runCatching { owner.unlinkToDeath(this,0) } }
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        val position=data.dataPosition()
        if(code in FIRST_CALL_TRANSACTION..FIRST_CALL_TRANSACTION+3) {
            try {
                data.enforceInterface("android.speech.tts.ITextToSpeechCallback")
                val id=data.readString()
                when(code) {
                    FIRST_CALL_TRANSACTION -> BackgroundMusic.started(owner,id)
                    FIRST_CALL_TRANSACTION+1 -> BackgroundMusic.finished(owner,id,true)
                    FIRST_CALL_TRANSACTION+2 -> BackgroundMusic.finished(owner,id,data.readInt()!=0)
                    else -> BackgroundMusic.finished(owner,id)
                }
            } catch (_: Exception) { Log.w("BackgroundMusic","Playback callback observation unavailable") }
            finally { data.setDataPosition(position) }
        }
        return delegate.transact(code,data,reply,flags)
    }
}
