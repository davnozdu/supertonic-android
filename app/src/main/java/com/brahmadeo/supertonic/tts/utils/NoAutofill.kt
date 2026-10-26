package com.brahmadeo.supertonic.tts.utils

import android.app.Activity
import android.os.Build
import android.view.View

/**
 * Windows with the reading text are left out of autofill. On Android 10 a tap in a text field
 * holding a whole chapter sent all of it to the system's autofill service ("Unreasonably large
 * binder buffer") and some firmware (MIUI) closed the app. Nothing here is worth autofilling.
 * Found by the TeraTTS fork 1.6.12 (github.com/9208499-ship-it/teratts-android, GPL-3.0).
 */
object NoAutofill {
    fun apply(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                activity.window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            } catch (_: Exception) { }
        }
    }
}
