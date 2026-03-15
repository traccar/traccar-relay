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
import com.google.firebase.messaging.FirebaseMessaging
import androidx.work.WorkInfo
import androidx.work.WorkManager
import org.traccar.relay.api.Device
import org.traccar.relay.api.DeviceRepository

data class WorkScheduleInfo(
    val state: String,
    val nextScheduleTimeMillis: Long,
    val runAttemptCount: Int,
)

data class UiState(
    val token: String? = null,
    val needsKeySetup: Boolean = false,
    val devices: List<Device> = emptyList(),
    val workSchedules: Map<String, WorkScheduleInfo> = emptyMap(),
    val loading: Boolean = false,
    val error: String? = null,
    val serverUrl: String = "",
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = DeviceRepository(application)
    private val workManager = WorkManager.getInstance(application)

    private val _state = MutableStateFlow(UiState(token = repo.savedOauthToken, serverUrl = repo.serverUrl))
    val state: StateFlow<UiState> = _state

    init {
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            viewModelScope.launch(Dispatchers.IO) {
                repo.onFirebaseTokenChanged(token)
            }
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
        _state.update { it.copy(serverUrl = url) }
        viewModelScope.launch(Dispatchers.IO) {
            repo.saveServerUrl(url)
        }
    }

    fun signOut() {
        repo.signOut()
        _state.update { UiState() }
    }

    companion object {
        private const val TAG = "MainViewModel"
    }
}
