package org.traccar.find.hub.sync

import android.app.Application
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val tokenStorage = TokenStorage(application)

    private val _token = MutableStateFlow(tokenStorage.getToken())
    val token: StateFlow<String?> = _token

    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = _devices

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    init {
        if (tokenStorage.getToken() != null) {
            fetchDevices()
        }
    }

    fun onTokenReceived(email: String, token: String) {
        tokenStorage.saveEmail(email)
        tokenStorage.saveToken(token)
        _token.value = token
        exchangeTokenAndFetchDevices(token)
    }

    fun fetchDevices() {
        val oauthToken = tokenStorage.getToken() ?: return
        exchangeTokenAndFetchDevices(oauthToken)
    }

    private fun exchangeTokenAndFetchDevices(oauthToken: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _loading.value = true
            _error.value = null
            try {
                val androidId = Settings.Secure.getString(
                    getApplication<Application>().contentResolver,
                    Settings.Secure.ANDROID_ID
                )

                val email = tokenStorage.getEmail() ?: ""

                var aasToken = tokenStorage.getAasToken()
                if (aasToken == null) {
                    val exchangeResult = GoogleAuthClient.exchangeToken(email, oauthToken, androidId)
                    aasToken = exchangeResult["Token"]
                        ?: throw Exception("Failed to get AAS token: ${exchangeResult["Error"]}")
                    tokenStorage.saveAasToken(aasToken)
                    exchangeResult["Email"]?.let { tokenStorage.saveEmail(it) }
                }

                val currentEmail = tokenStorage.getEmail() ?: email
                val oauthResult = GoogleAuthClient.performOAuth(currentEmail, aasToken, androidId, "android_device_manager")
                val admToken = oauthResult["Auth"]
                    ?: throw Exception("Failed to get ADM token: ${oauthResult["Error"]}")

                val devices = NovaApiClient.listDevices(admToken)
                _devices.value = devices
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching devices", e)
                _error.value = e.message
            } finally {
                _loading.value = false
            }
        }
    }

    companion object {
        private const val TAG = "MainViewModel"
    }
}
