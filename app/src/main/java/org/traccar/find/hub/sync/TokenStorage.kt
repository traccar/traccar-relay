package org.traccar.find.hub.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

class TokenStorage(context: Context) {

    private val prefs: SharedPreferences

    init {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        prefs = EncryptedSharedPreferences.create(
            "secure_prefs",
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun saveToken(token: String) {
        prefs.edit().putString(KEY_OAUTH_TOKEN, token).apply()
    }

    fun getToken(): String? {
        return prefs.getString(KEY_OAUTH_TOKEN, null)
    }

    fun clearToken() {
        prefs.edit().remove(KEY_OAUTH_TOKEN).apply()
    }

    companion object {
        private const val KEY_OAUTH_TOKEN = "oauth_token"
    }
}
