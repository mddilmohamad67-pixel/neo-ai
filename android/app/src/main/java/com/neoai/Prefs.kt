package com.neoai

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

/**
 * Stores the local backend URL, the shared auth token and small UI prefs.
 * The token never ships inside the APK: it is generated on first launch and
 * copied by the user into the Termux .env file.
 */
class Prefs(context: Context) {

    private val prefs: SharedPreferences = runCatching {
        val key = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "neo_secure",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        ) as SharedPreferences
    }.getOrElse {
        context.getSharedPreferences("neo_plain", Context.MODE_PRIVATE)
    }

    var serverUrl: String
        get() = prefs.getString("server_url", "http://127.0.0.1:8787") ?: "http://127.0.0.1:8787"
        set(value) = prefs.edit().putString("server_url", value.trim().trimEnd('/')).apply()

    var token: String
        get() {
            val existing = prefs.getString("token", null)
            if (existing != null) return existing
            val fresh = UUID.randomUUID().toString().replace("-", "")
            prefs.edit().putString("token", fresh).apply()
            return fresh
        }
        set(value) = prefs.edit().putString("token", value.trim()).apply()

    var language: String
        get() = prefs.getString("language", "hi-IN") ?: "hi-IN"
        set(value) = prefs.edit().putString("language", value).apply()

    var theme: String
        get() = prefs.getString("theme", "dark") ?: "dark"
        set(value) = prefs.edit().putString("theme", value).apply()

    var speakReplies: Boolean
        get() = prefs.getBoolean("speak", true)
        set(value) = prefs.edit().putBoolean("speak", value).apply()

    var handsFree: Boolean
        get() = prefs.getBoolean("hands_free", false)
        set(value) = prefs.edit().putBoolean("hands_free", value).apply()

    var automation: Boolean
        get() = prefs.getBoolean("automation", false)
        set(value) = prefs.edit().putBoolean("automation", value).apply()

    var displayName: String
        get() = prefs.getString("name", "Rehan") ?: "Rehan"
        set(value) = prefs.edit().putString("name", value).apply()
}
