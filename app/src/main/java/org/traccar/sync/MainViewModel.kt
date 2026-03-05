package org.traccar.sync

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
import org.traccar.sync.api.Device
import org.traccar.sync.api.NovaApiClient
import org.traccar.sync.api.SpotApiClient
import org.traccar.sync.auth.GoogleAuthClient
import org.traccar.sync.auth.TokenStorage
import org.traccar.sync.proto.DeviceUpdate
import org.traccar.sync.push.FcmCredentials
import org.traccar.sync.push.FcmRegistrationClient
import org.traccar.sync.push.HttpEceDecryptor
import org.traccar.sync.push.McsClient
import org.traccar.sync.util.LocationDecryptor

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

    private val _locationResult = MutableStateFlow<LocationResult?>(null)
    val locationResult: StateFlow<LocationResult?> = _locationResult

    private val _needsKeySetup = MutableStateFlow(false)
    val needsKeySetup: StateFlow<Boolean> = _needsKeySetup

    private val _ringingDevice = MutableStateFlow<String?>(null)
    val ringingDevice: StateFlow<String?> = _ringingDevice

    private var fcmCredentials: FcmCredentials? = null
    private var mcsClient: McsClient? = null
    private var pendingRequestUuid: String? = null
    private var pendingDeviceId: String? = null

    init {
        if (tokenStorage.getToken() != null) {
            _needsKeySetup.value = tokenStorage.getSharedKey() == null
            fetchDevices()
        }
    }

    fun onTokenReceived(email: String, token: String) {
        tokenStorage.saveEmail(email)
        tokenStorage.saveToken(token)
        _token.value = token
        _needsKeySetup.value = tokenStorage.getSharedKey() == null
        exchangeTokenAndFetchDevices(token)
    }

    fun onSharedKeyReceived(sharedKey: ByteArray) {
        tokenStorage.saveSharedKey(sharedKey.joinToString("") { "%02x".format(it) })
        _needsKeySetup.value = false
        exchangeTokenAndFetchDevices(tokenStorage.getToken()!!, sharedKey)
    }

    private fun fetchAndDecryptOwnerKey(sharedKey: ByteArray) {
        val androidId = Settings.Secure.getString(
            getApplication<Application>().contentResolver,
            Settings.Secure.ANDROID_ID
        )
        val email = tokenStorage.getEmail() ?: ""
        val aasToken = tokenStorage.getAasToken() ?: throw Exception("No AAS token")

        val oauthResult = GoogleAuthClient.performOAuth(email, aasToken, androidId, "spot", "com.google.android.gms")
        val spotToken = oauthResult["Auth"] ?: throw Exception("Failed to get Spot token: ${oauthResult["Error"]}")

        val encryptedOwnerKey = SpotApiClient.getEncryptedOwnerKey(spotToken)
        val ownerKey = LocationDecryptor.decryptOwnerKey(sharedKey, encryptedOwnerKey)
        val hex = ownerKey.joinToString("") { "%02x".format(it) }
        tokenStorage.saveOwnerKey(hex)
    }

    private fun fetchDevices() {
        val oauthToken = tokenStorage.getToken() ?: return
        exchangeTokenAndFetchDevices(oauthToken)
    }

    private fun exchangeTokenAndFetchDevices(oauthToken: String, sharedKey: ByteArray? = null) {
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

                if (sharedKey != null && tokenStorage.getOwnerKey() == null) {
                    try {
                        fetchAndDecryptOwnerKey(sharedKey)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error fetching owner key", e)
                    }
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

    private fun handlePushMessage(message: org.traccar.sync.proto.mcs.DataMessageStanza) {
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

        val ownerKeyHex = tokenStorage.getOwnerKey()
        val ownerKey = ownerKeyHex?.chunked(2)?.map { it.toInt(16).toByte() }?.toByteArray()

        val encryptedEik = deviceUpdate.deviceMetadata?.information?.deviceRegistration
            ?.encryptedUserSecrets?.encryptedIdentityKey?.toByteArray()

        var identityKey: ByteArray? = null
        if (ownerKey != null && encryptedEik != null) {
            try {
                identityKey = LocationDecryptor.decryptEik(ownerKey, encryptedEik)
            } catch (e: Exception) {
                Log.e(TAG, "Error decrypting EIK", e)
            }
        }

        val locations = mutableListOf<LocationEntry>()

        if (reports != null) {
            val recentLoc = reports.recentLocation
            val recentTime = reports.recentLocationTimestamp
            if (recentLoc != null) {
                val entry = buildLocationEntry(recentLoc, recentTime, identityKey, "Recent")
                locations.add(entry)
            }

            reports.networkLocations.forEachIndexed { index, loc ->
                val time = reports.networkLocationTimestamps.getOrNull(index)
                val entry = buildLocationEntry(loc, time, identityKey, "Network ${index + 1}")
                locations.add(entry)
            }
        }

        _locationResult.value = LocationResult(deviceName, locations)
        _locatedDeviceId.value = pendingDeviceId
        _locatingDevice.value = null
        pendingRequestUuid = null
        pendingDeviceId = null
    }

    private fun buildLocationEntry(
        report: org.traccar.sync.proto.LocationReport,
        time: org.traccar.sync.proto.Time?,
        identityKey: ByteArray?,
        label: String,
    ): LocationEntry {
        val timestamp = time?.let {
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date(it.seconds.toLong() * 1000))
        }
        val status = report.status?.name
        val accuracy = report.geoLocation?.accuracy
        val semanticLocation = report.semanticLocation?.locationName

        var latitude: Double? = null
        var longitude: Double? = null
        var altitude: Int? = null

        val geo = report.geoLocation?.encryptedReport
        if (geo != null && identityKey != null) {
            try {
                val encLoc = geo.encryptedLocation?.toByteArray()
                val pubKeyRandom = geo.publicKeyRandom?.toByteArray()
                val deviceTimeOffset = report.geoLocation?.deviceTimeOffset?.toLong() ?: 0L

                if (encLoc != null) {
                    val decrypted = LocationDecryptor.decryptLocation(
                        identityKey, encLoc, pubKeyRandom, deviceTimeOffset
                    )
                    latitude = decrypted.latitude
                    longitude = decrypted.longitude
                    altitude = decrypted.altitude
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error decrypting location for $label", e)
            }
        }

        return LocationEntry(
            label = label,
            timestamp = timestamp,
            status = status,
            accuracy = accuracy,
            latitude = latitude,
            longitude = longitude,
            altitude = altitude,
            semanticLocation = semanticLocation,
        )
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
                _locationResult.value = LocationResult("Error", listOf(LocationEntry(label = e.message ?: "Unknown error")))
                _locatingDevice.value = null
            }
        }
    }

    fun playSound(device: Device) {
        sendSoundRequest(device, start = true)
    }

    fun stopSound(device: Device) {
        sendSoundRequest(device, start = false)
    }

    private fun sendSoundRequest(device: Device, start: Boolean) {
        val creds = fcmCredentials ?: return

        if (start) {
            _ringingDevice.value = device.id
        } else {
            _ringingDevice.value = null
        }

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

                val payload = NovaApiClient.buildSoundRequest(device.id, creds.fcmToken, start)
                NovaApiClient.executeAction(admToken, payload)
            } catch (e: Exception) {
                Log.e(TAG, "Error sending sound request", e)
                _ringingDevice.value = null
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

data class LocationEntry(
    val label: String,
    val timestamp: String? = null,
    val status: String? = null,
    val accuracy: Float? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitude: Int? = null,
    val semanticLocation: String? = null,
)

data class LocationResult(
    val deviceName: String,
    val locations: List<LocationEntry>,
)
