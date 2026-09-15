package org.traccar.relay

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.firebase.messaging.FirebaseMessaging
import androidx.work.WorkInfo
import androidx.work.WorkManager
import org.traccar.relay.api.Device
import org.traccar.relay.api.DeviceRepository
import org.traccar.relay.auth.clearGoogleAuthenticationSession

data class WorkScheduleInfo(
    val state: String,
    val nextScheduleTimeMillis: Long,
    val runAttemptCount: Int,
)

data class UiState(
    val authenticated: Boolean = false,
    val authenticationInProgress: Boolean = false,
    val authenticationGeneration: Int = 0,
    val needsKeySetup: Boolean = false,
    val keySetupUrl: String? = null,
    val devices: List<Device> = emptyList(),
    val workSchedules: Map<String, WorkScheduleInfo> = emptyMap(),
    val loading: Boolean = false,
    val error: String? = null,
    val serverUrl: String = "",
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = DeviceRepository(application)
    private val workManager = WorkManager.getInstance(application)

    private val _state = MutableStateFlow(UiState(authenticated = repo.hasCredentials, serverUrl = repo.serverUrl))
    val state: StateFlow<UiState> = _state

    init {
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            viewModelScope.launch(Dispatchers.IO) {
                repo.onFirebaseTokenChanged(token)
            }
        }
        if (repo.hasCredentials) {
            _state.update { it.copy(needsKeySetup = !repo.hasSharedKey()) }
            loadDevices()
        }
    }

    fun onTokenReceived(email: String, token: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(authenticationInProgress = true, error = null) }
            try {
                repo.authenticate(email, token)
                _state.update {
                    it.copy(
                        authenticated = true,
                        authenticationInProgress = false,
                        needsKeySetup = !repo.hasSharedKey(),
                    )
                }
                loadDevices()
            } catch (e: Exception) {
                repo.signOut()
                withContext(Dispatchers.Main) {
                    clearGoogleAuthenticationSession(getApplication())
                }
                _state.update {
                    it.copy(
                        authenticated = false,
                        authenticationInProgress = false,
                        authenticationGeneration = it.authenticationGeneration + 1,
                        error = e.message ?: "Google authentication failed",
                    )
                }
            }
        }
    }

    fun onSharedKeyReceived(sharedKey: ByteArray) {
        repo.saveSharedKey(sharedKey)
        clearGoogleAuthenticationSession(getApplication())
        _state.update { it.copy(needsKeySetup = false) }
        loadDevices(sharedKey)
    }

    fun prepareKeySetup(continueUrl: String) {
        if (_state.value.keySetupUrl != null || _state.value.authenticationInProgress) return
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(authenticationInProgress = true, error = null) }
            try {
                val url = repo.createKeySetupUrl(continueUrl)
                _state.update { it.copy(keySetupUrl = url, authenticationInProgress = false) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        authenticationInProgress = false,
                        error = e.message ?: "Google key setup failed",
                    )
                }
            }
        }
    }

    private fun loadDevices(sharedKey: ByteArray? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val devices = repo.loadDevices(sharedKey)
                val workSchedules = mutableMapOf<String, WorkScheduleInfo>()
                for (device in devices) {
                    val workInfos = workManager
                        .getWorkInfosForUniqueWork("periodic_location_${device.id}")
                        .get()
                    val activeWork = workInfos.firstOrNull {
                        it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING
                    }
                    if (activeWork != null) {
                        workSchedules[device.id] = WorkScheduleInfo(
                            state = activeWork.state.name,
                            nextScheduleTimeMillis = activeWork.nextScheduleTimeMillis,
                            runAttemptCount = activeWork.runAttemptCount,
                        )
                    }
                }
                _state.update { it.copy(devices = devices, workSchedules = workSchedules) }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching devices", e)
                _state.update { it.copy(error = e.message) }
            } finally {
                _state.update { it.copy(loading = false) }
            }
        }
    }

    fun cancelWork(deviceId: String) {
        workManager.cancelUniqueWork("periodic_location_$deviceId")
        _state.update {
            it.copy(workSchedules = it.workSchedules - deviceId)
        }
    }

    fun updateServerUrl(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val normalized = repo.saveServerUrl(url)
                _state.update { it.copy(serverUrl = normalized, error = null) }
            } catch (e: IllegalArgumentException) {
                _state.update { it.copy(error = e.message) }
            }
        }
    }

    fun signOut() {
        repo.signOut()
        clearGoogleAuthenticationSession(getApplication())
        _state.update {
            UiState(
                serverUrl = repo.serverUrl,
                authenticationGeneration = it.authenticationGeneration + 1,
            )
        }
    }

    companion object {
        private const val TAG = "MainViewModel"
    }
}
