package com.github.sevagh.demucs_android

import android.content.Context
import android.content.SharedPreferences

class SavedSettings(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("DemucsAndroidAppSettings", Context.MODE_PRIVATE)

    var lastRecordingStatus: String?
        get() = prefs.getString("lastRecordingStatus", null)
        set(value) = prefs.edit().putString("lastRecordingStatus", value).apply()

    var lastCaptureStatus: String?
        get() = prefs.getString("lastCaptureStatus", null)
        set(value) = prefs.edit().putString("lastCaptureStatus", value).apply()

    var lastStemStatus: String?
        get() = prefs.getString("lastStemStatus", null)
        set(value) = prefs.edit().putString("lastStemStatus", value).apply()

    var lastStemsSet: Set<String>?
        get() = prefs.getStringSet("lastStemsSet", null)
        set(value) = prefs.edit().putStringSet("lastStemsSet", value).apply()

    var lastMixStatus: String?
        get() = prefs.getString("lastMixStatus", null)
        set(value) = prefs.edit().putString("lastMixStatus", value).apply()
}
