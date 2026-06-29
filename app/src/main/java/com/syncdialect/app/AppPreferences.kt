package com.syncdialect.app

import android.content.Context
import android.content.SharedPreferences

class AppPreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("SyncDialectPrefs", Context.MODE_PRIVATE)

    // --- Tuning Parameters ---

    // VAD Wait Frames (Speed): 10 to 50 (default 25 = 1.0s)
    var vadWaitFrames: Int
        get() = prefs.getInt("vadWaitFrames", 25)
        set(value) = prefs.edit().putInt("vadWaitFrames", value).apply()

    // Model Temperature: 0.0 to 1.0 (default 0.1)
    var modelTemperature: Float
        get() = prefs.getFloat("modelTemperature", 0.1f)
        set(value) = prefs.edit().putFloat("modelTemperature", value).apply()

    // Model Top-K: 1 to 40 (default 1)
    var modelTopK: Int
        get() = prefs.getInt("modelTopK", 1)
        set(value) = prefs.edit().putInt("modelTopK", value).apply()

    // --- Voice Selection ---
    
    // Saved TTS Voice ID
    var selectedVoiceName: String?
        get() = prefs.getString("selectedVoiceName", null)
        set(value) = prefs.edit().putString("selectedVoiceName", value).apply()
}
