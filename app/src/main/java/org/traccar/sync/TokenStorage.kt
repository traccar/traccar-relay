package org.traccar.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class TokenStorage(context: Context) {

    private val prefs: SharedPreferences

    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        prefs = EncryptedSharedPreferences.create(
            context,
            "secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun saveToken(token: String) {
        prefs.edit { putString(KEY_OAUTH_TOKEN, token) }
    }

    fun getToken(): String? {
        return prefs.getString(KEY_OAUTH_TOKEN, null)
    }

    fun saveAasToken(token: String) {
        prefs.edit { putString(KEY_AAS_TOKEN, token) }
    }

    fun getAasToken(): String? {
        return prefs.getString(KEY_AAS_TOKEN, null)
    }

    fun saveEmail(email: String) {
        prefs.edit { putString(KEY_EMAIL, email) }
    }

    fun getEmail(): String? {
        return prefs.getString(KEY_EMAIL, null)
    }

    fun saveFcmCredentials(json: String) {
        prefs.edit { putString(KEY_FCM_CREDENTIALS, json) }
    }

    fun getFcmCredentials(): String? {
        return prefs.getString(KEY_FCM_CREDENTIALS, null)
    }

    fun saveSharedKey(hex: String) {
        prefs.edit { putString(KEY_SHARED_KEY, hex) }
    }

    fun getSharedKey(): String? {
        return prefs.getString(KEY_SHARED_KEY, null)
    }

    fun saveOwnerKey(hex: String) {
        prefs.edit { putString(KEY_OWNER_KEY, hex) }
    }

    fun getOwnerKey(): String? {
        return prefs.getString(KEY_OWNER_KEY, null)
    }

    companion object {
        private const val KEY_OAUTH_TOKEN = "oauth_token"
        private const val KEY_AAS_TOKEN = "aas_token"
        private const val KEY_EMAIL = "email"
        private const val KEY_FCM_CREDENTIALS = "fcm_credentials"
        private const val KEY_SHARED_KEY = "shared_key"
        private const val KEY_OWNER_KEY = "owner_key"
    }
}
