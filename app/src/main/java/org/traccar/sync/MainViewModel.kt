package org.traccar.sync

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.traccar.sync.api.Device
import org.traccar.sync.api.DeviceRepository
import org.traccar.sync.api.LocationEntry
import org.traccar.sync.api.LocationResult

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = DeviceRepository(application)

    private val _token = MutableStateFlow(repo.savedOauthToken)
    val token: StateFlow<String?> = _token

    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = _devices

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private val _locatingDevice = MutableStateFlow<String?>(null)
    val locatingDevice: StateFlow<String?> = _locatingDevice

    private val _locatedDeviceId = MutableStateFlow<String?>(null)
    val locatedDeviceId: StateFlow<String?> = _locatedDeviceId

    private val _locationResult = MutableStateFlow<LocationResult?>(null)
    val locationResult: StateFlow<LocationResult?> = _locationResult

    private val _needsKeySetup = MutableStateFlow(false)
    val needsKeySetup: StateFlow<Boolean> = _needsKeySetup

    private val _ringingDevice = MutableStateFlow<String?>(null)
    val ringingDevice: StateFlow<String?> = _ringingDevice

    init {
        val oauthToken = repo.savedOauthToken
        if (oauthToken != null) {
            _needsKeySetup.value = !repo.hasSharedKey()
            loadDevices(oauthToken)
        }
    }

    fun onTokenReceived(email: String, token: String) {
        repo.saveCredentials(email, token)
        _token.value = token
        _needsKeySetup.value = !repo.hasSharedKey()
        loadDevices(token)
    }

    fun onSharedKeyReceived(sharedKey: ByteArray) {
        repo.saveSharedKey(sharedKey)
        _needsKeySetup.value = false
        loadDevices(repo.savedOauthToken!!, sharedKey)
    }

    private fun loadDevices(oauthToken: String, sharedKey: ByteArray? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            _loading.value = true
            _error.value = null
            try {
                _devices.value = repo.loadDevices(oauthToken, sharedKey)

                viewModelScope.launch(Dispatchers.IO) {
                    repo.startPushConnection { deviceId, result ->
                        _locationResult.value = result
                        _locatedDeviceId.value = deviceId
                        _locatingDevice.value = null
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching devices", e)
                _error.value = e.message
            } finally {
                _loading.value = false
            }
        }
    }

    fun requestLocation(device: Device) {
        _locatingDevice.value = device.id
        _locatedDeviceId.value = null
        _locationResult.value = null

        viewModelScope.launch(Dispatchers.IO) {
            try {
                repo.requestLocation(device.id)
            } catch (e: Exception) {
                Log.e(TAG, "Error requesting location", e)
                _locationResult.value = LocationResult("Error", listOf(LocationEntry(label = e.message ?: "Unknown error")))
                _locatingDevice.value = null
            }
        }
    }

    fun playSound(device: Device) {
        _ringingDevice.value = device.id
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repo.playSound(device.id)
            } catch (e: Exception) {
                Log.e(TAG, "Error playing sound", e)
                _ringingDevice.value = null
            }
        }
    }

    fun stopSound(device: Device) {
        _ringingDevice.value = null
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
