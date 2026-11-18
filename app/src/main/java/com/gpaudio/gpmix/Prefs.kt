package com.gpaudio.gpmix

import android.content.Context
import android.content.SharedPreferences

/** Preferensi per perangkat (bukan bagian project). */
class Prefs(ctx: Context) {
    private val sp: SharedPreferences = ctx.getSharedPreferences("gpmix", Context.MODE_PRIVATE)

    var theme: String
        get() = sp.getString("theme", "default") ?: "default"
        set(v) { sp.edit().putString("theme", v).apply() }

    /** "system", "en", atau "id". */
    var language: String
        get() = sp.getString("lang", "system") ?: "system"
        set(v) { sp.edit().putString("lang", v).apply() }

    var inputDeviceId: Int
        get() = sp.getInt("inDev", 0)
        set(v) { sp.edit().putInt("inDev", v).apply() }

    var outputDeviceId: Int
        get() = sp.getInt("outDev", 0)
        set(v) { sp.edit().putInt("outDev", v).apply() }

    var donationSeen: Boolean
        get() = sp.getBoolean("donationSeen", false)
        set(v) { sp.edit().putBoolean("donationSeen", v).apply() }

    var lastProject: String
        get() = sp.getString("lastProject", "") ?: ""
        set(v) { sp.edit().putString("lastProject", v).apply() }

    /** Pemetaan MIDI sebagai teks "kunci=target;..." (kunci mis. "n:36" atau "c:7"). */
    var midiMap: String
        get() = sp.getString("midiMap", "") ?: ""
        set(v) { sp.edit().putString("midiMap", v).apply() }
}
