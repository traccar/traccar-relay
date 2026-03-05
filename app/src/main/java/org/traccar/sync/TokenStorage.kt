package org.traccar.sync

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

    fun saveAasToken(token: String) {
        prefs.edit().putString(KEY_AAS_TOKEN, token).apply()
    }

    fun getAasToken(): String? {
        return prefs.getString(KEY_AAS_TOKEN, null)
    }

    fun saveEmail(email: String) {
        prefs.edit().putString(KEY_EMAIL, email).apply()
    }

    fun getEmail(): String? {
        return prefs.getString(KEY_EMAIL, null)
    }

    fun saveFcmCredentials(json: String) {
        prefs.edit().putString(KEY_FCM_CREDENTIALS, json).apply()
    }

    fun getFcmCredentials(): String? {
        return prefs.getString(KEY_FCM_CREDENTIALS, null)
    }

    fun saveSharedKey(hex: String) {
        prefs.edit().putString(KEY_SHARED_KEY, hex).apply()
    }

    fun getSharedKey(): String? {
        return prefs.getString(KEY_SHARED_KEY, null)
    }

    fun saveOwnerKey(hex: String) {
        prefs.edit().putString(KEY_OWNER_KEY, hex).apply()
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
