package org.traccar.sync

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.google.firebase.messaging.FirebaseMessaging
import org.traccar.sync.api.Device
import org.traccar.sync.api.DeviceRepository

data class UiState(
    val token: String? = null,
    val needsKeySetup: Boolean = false,
    val devices: List<Device> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val serverUrl: String = "",
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = DeviceRepository(application)

    private val _state = MutableStateFlow(UiState(token = repo.savedOauthToken, serverUrl = repo.serverUrl))
    val state: StateFlow<UiState> = _state

    init {
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            repo.onFirebaseTokenChanged(token)
        }
        val oauthToken = repo.savedOauthToken
        if (oauthToken != null) {
            _state.update { it.copy(needsKeySetup = !repo.hasSharedKey()) }
            loadDevices(oauthToken)
        }
    }

    fun onTokenReceived(email: String, token: String) {
        repo.saveCredentials(email, token)
        _state.update { it.copy(token = token, needsKeySetup = !repo.hasSharedKey()) }
        loadDevices(token)
    }

    fun onSharedKeyReceived(sharedKey: ByteArray) {
        repo.saveSharedKey(sharedKey)
        _state.update { it.copy(needsKeySetup = false) }
        loadDevices(repo.savedOauthToken!!, sharedKey)
    }

    private fun loadDevices(oauthToken: String, sharedKey: ByteArray? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val devices = repo.loadDevices(oauthToken, sharedKey)
                _state.update { it.copy(devices = devices) }

                viewModelScope.launch(Dispatchers.IO) {
                    repo.startPushConnection { _, _ -> }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching devices", e)
                _state.update { it.copy(error = e.message) }
            } finally {
                _state.update { it.copy(loading = false) }
            }
        }
    }

    fun updateServerUrl(url: String) {
        repo.saveServerUrl(url)
        _state.update { it.copy(serverUrl = url) }
    }

    override fun onCleared() {
        super.onCleared()
        repo.stopPushConnection()
    }

    companion object {
        private const val TAG = "MainViewModel"
    }
}
