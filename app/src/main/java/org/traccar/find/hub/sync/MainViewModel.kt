package org.traccar.find.hub.sync

import android.app.Application
import android.provider.Settings
import android.util.Base64
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.traccar.find.hub.sync.proto.DeviceUpdate

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

    private val _locatingDevice = MutableStateFlow<String?>(null)
    val locatingDevice: StateFlow<String?> = _locatingDevice

    private val _locatedDeviceId = MutableStateFlow<String?>(null)
    val locatedDeviceId: StateFlow<String?> = _locatedDeviceId

    private val _locationResult = MutableStateFlow<String?>(null)
    val locationResult: StateFlow<String?> = _locationResult

    private var fcmCredentials: FcmCredentials? = null
    private var mcsClient: McsClient? = null
    private var pendingRequestUuid: String? = null
    private var pendingDeviceId: String? = null

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

                ensureFcmRegistered()
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching devices", e)
                _error.value = e.message
            } finally {
                _loading.value = false
            }
        }
    }

    private fun ensureFcmRegistered() {
        if (fcmCredentials != null) return

        val cached = tokenStorage.getFcmCredentials()
        if (cached != null) {
            try {
                val json = JSONObject(cached)
                fcmCredentials = FcmCredentials(
                    gcmAndroidId = json.getString("gcmAndroidId"),
                    gcmSecurityToken = json.getString("gcmSecurityToken"),
                    gcmToken = json.getString("gcmToken"),
                    gcmAppId = json.getString("gcmAppId"),
                    fcmToken = json.getString("fcmToken"),
                    publicKey = json.getString("publicKey"),
                    privateKey = json.getString("privateKey"),
                    authSecret = json.getString("authSecret"),
                )
                startMcsConnection()
                return
            } catch (_: Exception) {
            }
        }

        try {
            val creds = FcmRegistrationClient.register()
            fcmCredentials = creds

            val json = JSONObject().apply {
                put("gcmAndroidId", creds.gcmAndroidId)
                put("gcmSecurityToken", creds.gcmSecurityToken)
                put("gcmToken", creds.gcmToken)
                put("gcmAppId", creds.gcmAppId)
                put("fcmToken", creds.fcmToken)
                put("publicKey", creds.publicKey)
                put("privateKey", creds.privateKey)
                put("authSecret", creds.authSecret)
            }
            tokenStorage.saveFcmCredentials(json.toString())

            startMcsConnection()
        } catch (e: Exception) {
            Log.e(TAG, "FCM registration failed", e)
        }
    }

    private fun startMcsConnection() {
        val creds = fcmCredentials ?: return
        if (mcsClient != null) return

        val client = McsClient()
        mcsClient = client

        viewModelScope.launch(Dispatchers.IO) {
            try {
                client.connect(creds.gcmAndroidId, creds.gcmSecurityToken) { message ->
                    handlePushMessage(message)
                }
            } catch (e: Exception) {
                Log.e(TAG, "MCS connection error", e)
                mcsClient = null
            }
        }
    }

    private fun handlePushMessage(message: org.traccar.find.hub.sync.proto.mcs.DataMessageStanza) {
        val creds = fcmCredentials ?: return

        try {
            val appDataMap = message.app_data.associate { it.key to it.value_ }

            val cryptoKey = appDataMap["crypto-key"]?.removePrefix("dh=") ?: return
            val salt = appDataMap["encryption"]?.removePrefix("salt=") ?: return
            val rawData = message.raw_data?.toByteArray() ?: return

            val decrypted = HttpEceDecryptor.decrypt(
                rawData = rawData,
                saltBase64 = salt,
                cryptoKeyBase64 = cryptoKey,
                privateKeyBase64 = creds.privateKey,
                publicKeyBase64 = creds.publicKey,
                authSecretBase64 = creds.authSecret,
            )

            val jsonStr = String(decrypted, Charsets.UTF_8)
            val json = JSONObject(jsonStr)
            val data = json.optJSONObject("data")
            val fcmPayload = data?.optString("com.google.android.apps.adm.FCM_PAYLOAD")

            if (fcmPayload != null) {
                val payloadBytes = Base64.decode(fcmPayload, Base64.DEFAULT)
                val deviceUpdate = DeviceUpdate.ADAPTER.decode(payloadBytes)

                if (deviceUpdate.fcmMetadata?.requestUuid == pendingRequestUuid) {
                    processLocationUpdate(deviceUpdate)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling push message", e)
        }
    }

    private fun processLocationUpdate(deviceUpdate: DeviceUpdate) {
        val deviceName = deviceUpdate.deviceMetadata?.userDefinedDeviceName ?: "Unknown"
        val locationInfo = deviceUpdate.deviceMetadata?.information?.locationInformation
        val reports = locationInfo?.reports?.recentLocationAndNetworkLocations

        val sb = StringBuilder()
        sb.appendLine("Device: $deviceName")

        if (reports != null) {
            val recentTime = reports.recentLocationTimestamp
            if (recentTime != null) {
                sb.appendLine("Recent: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(recentTime.seconds.toLong() * 1000))}")
            }

            val recentLoc = reports.recentLocation
            if (recentLoc != null) {
                sb.appendLine("Status: ${recentLoc.status}")
                recentLoc.geoLocation?.let { geo ->
                    sb.appendLine("Accuracy: ${geo.accuracy}m")
                }
                recentLoc.semanticLocation?.let { sem ->
                    sb.appendLine("Location: ${sem.locationName}")
                }
            }

            val networkCount = reports.networkLocations.size
            if (networkCount > 0) {
                sb.appendLine("Network locations: $networkCount")
            }
        } else {
            sb.appendLine("No location data")
        }

        _locationResult.value = sb.toString()
        _locatedDeviceId.value = pendingDeviceId
        _locatingDevice.value = null
        pendingRequestUuid = null
        pendingDeviceId = null
    }

    fun requestLocation(device: Device) {
        val creds = fcmCredentials ?: return
        _locatingDevice.value = device.id
        _locatedDeviceId.value = null
        _locationResult.value = null
        pendingDeviceId = device.id

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val androidId = Settings.Secure.getString(
                    getApplication<Application>().contentResolver,
                    Settings.Secure.ANDROID_ID
                )

                val email = tokenStorage.getEmail() ?: ""
                val aasToken = tokenStorage.getAasToken()
                    ?: throw Exception("No AAS token")

                val oauthResult = GoogleAuthClient.performOAuth(email, aasToken, androidId, "android_device_manager")
                val admToken = oauthResult["Auth"]
                    ?: throw Exception("Failed to get ADM token: ${oauthResult["Error"]}")

                val (payload, requestUuid) = NovaApiClient.buildLocationRequest(device.id, creds.fcmToken)
                pendingRequestUuid = requestUuid

                NovaApiClient.executeAction(admToken, payload)
            } catch (e: Exception) {
                Log.e(TAG, "Error requesting location", e)
                _locationResult.value = "Error: ${e.message}"
                _locatingDevice.value = null
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        mcsClient?.disconnect()
        mcsClient = null
    }

    companion object {
        private const val TAG = "MainViewModel"
    }
}
