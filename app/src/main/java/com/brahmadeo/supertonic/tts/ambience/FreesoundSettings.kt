package com.brahmadeo.supertonic.tts.ambience

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Opt-in only. Credentials never leave the phone except in Freesound's Authorization header. */
object FreesoundSettings {
    const val ENABLED = AmbienceSettings.FREESOUND
    private const val KEY = "freesound_online_key"
    private const val ALIAS = "mytts-freesound"
    private fun prefs(context: Context) = context.getSharedPreferences("SupertonicPrefs", 0)
    fun enabled(context: Context) = prefs(context).getBoolean(ENABLED, false)
    private fun secret(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun key(context: Context): String = runCatching {
        val parts = prefs(context).getString(KEY, "").orEmpty().split(':')
        if (parts.size != 2) return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
    }.getOrDefault("")
    @Synchronized fun saveKey(context: Context, value: String) {
        val edit = prefs(context).edit()
        if (value.isBlank()) edit.remove(KEY)
        else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secret()) }
            edit.putString(KEY, Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(cipher.doFinal(value.trim().toByteArray(Charsets.UTF_8)), Base64.NO_WRAP))
        }
        check(edit.commit())
    }
}
