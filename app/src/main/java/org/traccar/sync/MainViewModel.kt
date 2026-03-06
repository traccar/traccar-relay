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
import org.traccar.sync.api.LocationEntry
import org.traccar.sync.api.LocationResult

data class UiState(
    val token: String? = null,
    val needsKeySetup: Boolean = false,
    val devices: List<Device> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val locatingDevice: String? = null,
    val locatedDeviceId: String? = null,
    val locationResult: LocationResult? = null,
    val ringingDevice: String? = null,
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
                    repo.startPushConnection { deviceId, result ->
                        _state.update {
                            it.copy(
                                locationResult = result,
                                locatedDeviceId = deviceId,
                                locatingDevice = null,
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching devices", e)
                _state.update { it.copy(error = e.message) }
            } finally {
                _state.update { it.copy(loading = false) }
            }
        }
    }

    fun requestLocation(device: Device) {
        _state.update {
            it.copy(
                locatingDevice = device.id,
                locatedDeviceId = null,
                locationResult = null,
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                repo.requestLocation(device.id)
            } catch (e: Exception) {
                Log.e(TAG, "Error requesting location", e)
                _state.update {
                    it.copy(
                        locationResult = LocationResult("Error", listOf(LocationEntry(label = e.message ?: "Unknown error"))),
                        locatingDevice = null,
                    )
                }
            }
        }
    }

    fun playSound(device: Device) {
        _state.update { it.copy(ringingDevice = device.id) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repo.playSound(device.id)
            } catch (e: Exception) {
                Log.e(TAG, "Error playing sound", e)
                _state.update { it.copy(ringingDevice = null) }
            }
        }
    }

    fun updateServerUrl(url: String) {
        repo.saveServerUrl(url)
        _state.update { it.copy(serverUrl = url) }
    }

    fun stopSound(device: Device) {
        _state.update { it.copy(ringingDevice = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repo.stopSound(device.id)
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping sound", e)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        repo.stopPushConnection()
    }

    companion object {
        private const val TAG = "MainViewModel"
    }
}
