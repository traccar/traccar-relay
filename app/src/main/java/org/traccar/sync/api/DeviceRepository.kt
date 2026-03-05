package org.traccar.sync.api

import android.content.Context
import android.provider.Settings
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import org.traccar.sync.auth.GoogleAuthClient
import org.traccar.sync.auth.TokenStorage
import org.traccar.sync.proto.DeviceUpdate
import org.traccar.sync.push.FcmCredentials
import org.traccar.sync.push.FcmRegistrationClient
import org.traccar.sync.push.HttpEceDecryptor
import org.traccar.sync.push.McsClient
import org.traccar.sync.util.LocationDecryptor

class DeviceRepository(context: Context) {

    private val tokenStorage = TokenStorage(context)
    private val androidId: String = Settings.Secure.getString(
        context.contentResolver, Settings.Secure.ANDROID_ID
    )

    private var fcmCredentials: FcmCredentials? = null
    private var mcsClient: McsClient? = null
    private var pendingRequestUuid: String? = null
    private var pendingDeviceId: String? = null

    val savedOauthToken: String? get() = tokenStorage.getToken()

    fun hasSharedKey(): Boolean = tokenStorage.getSharedKey() != null

    fun saveCredentials(email: String, oauthToken: String) {
        tokenStorage.saveEmail(email)
        tokenStorage.saveToken(oauthToken)
    }

    fun saveSharedKey(sharedKey: ByteArray) {
        tokenStorage.saveSharedKey(sharedKey.joinToString("") { "%02x".format(it) })
    }

    fun loadDevices(oauthToken: String, sharedKey: ByteArray? = null): List<Device> {
        ensureAasToken(oauthToken)

        if (sharedKey != null && tokenStorage.getOwnerKey() == null) {
            try {
                fetchOwnerKey(sharedKey)
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching owner key", e)
            }
        }

        val devices = NovaApiClient.listDevices(getAdmToken())
        ensureFcmRegistered()
        return devices
    }

    // Push connection

    fun startPushConnection(onLocationUpdate: (String, LocationResult) -> Unit) {
        val creds = fcmCredentials ?: return
        if (mcsClient != null) return

        val client = McsClient()
        mcsClient = client

        try {
            client.connect(creds.gcmAndroidId, creds.gcmSecurityToken) { message ->
                handlePushMessage(message, onLocationUpdate)
            }
        } catch (e: Exception) {
            Log.e(TAG, "MCS connection error", e)
            mcsClient = null
        }
    }

    fun stopPushConnection() {
        mcsClient?.disconnect()
        mcsClient = null
    }

    // Actions

    fun requestLocation(deviceId: String) {
        val creds = fcmCredentials ?: throw Exception("FCM not registered")
        val admToken = getAdmToken()
        val (payload, requestUuid) = NovaApiClient.buildLocationRequest(deviceId, creds.fcmToken)
        pendingRequestUuid = requestUuid
        pendingDeviceId = deviceId
        NovaApiClient.executeAction(admToken, payload)
    }

    fun playSound(deviceId: String) {
        val creds = fcmCredentials ?: throw Exception("FCM not registered")
        val admToken = getAdmToken()
        val payload = NovaApiClient.buildSoundRequest(deviceId, creds.fcmToken, start = true)
        NovaApiClient.executeAction(admToken, payload)
    }

    fun stopSound(deviceId: String) {
        val creds = fcmCredentials ?: throw Exception("FCM not registered")
        val admToken = getAdmToken()
        val payload = NovaApiClient.buildSoundRequest(deviceId, creds.fcmToken, start = false)
        NovaApiClient.executeAction(admToken, payload)
    }

    // Private helpers

    private fun ensureAasToken(oauthToken: String) {
        if (tokenStorage.getAasToken() != null) return
        val email = tokenStorage.getEmail() ?: ""
        val exchangeResult = GoogleAuthClient.exchangeToken(email, oauthToken, androidId)
        val aasToken = exchangeResult["Token"]
            ?: throw Exception("Failed to get AAS token: ${exchangeResult["Error"]}")
        tokenStorage.saveAasToken(aasToken)
        exchangeResult["Email"]?.let { tokenStorage.saveEmail(it) }
    }

    private fun fetchOwnerKey(sharedKey: ByteArray) {
        val email = tokenStorage.getEmail() ?: ""
        val aasToken = tokenStorage.getAasToken() ?: throw Exception("No AAS token")
        val oauthResult = GoogleAuthClient.performOAuth(email, aasToken, androidId, "spot", "com.google.android.gms")
        val spotToken = oauthResult["Auth"] ?: throw Exception("Failed to get Spot token: ${oauthResult["Error"]}")
        val encryptedOwnerKey = SpotApiClient.getEncryptedOwnerKey(spotToken)
        val ownerKey = LocationDecryptor.decryptOwnerKey(sharedKey, encryptedOwnerKey)
        tokenStorage.saveOwnerKey(ownerKey.joinToString("") { "%02x".format(it) })
    }

    private fun getAdmToken(): String {
        val email = tokenStorage.getEmail() ?: ""
        val aasToken = tokenStorage.getAasToken() ?: throw Exception("No AAS token")
        val oauthResult = GoogleAuthClient.performOAuth(email, aasToken, androidId, "android_device_manager")
        return oauthResult["Auth"] ?: throw Exception("Failed to get ADM token: ${oauthResult["Error"]}")
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
                return
            } catch (_: Exception) {
            }
        }

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
    }

    private fun handlePushMessage(
        message: org.traccar.sync.proto.mcs.DataMessageStanza,
        onLocationUpdate: (String, LocationResult) -> Unit,
    ) {
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
                val requestUuid = deviceUpdate.fcmMetadata?.requestUuid

                if (requestUuid == pendingRequestUuid) {
                    val deviceId = pendingDeviceId ?: return
                    pendingRequestUuid = null
                    pendingDeviceId = null
                    onLocationUpdate(deviceId, buildLocationResult(deviceUpdate))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling push message", e)
        }
    }

    private fun buildLocationResult(deviceUpdate: DeviceUpdate): LocationResult {
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
                locations.add(buildLocationEntry(recentLoc, recentTime, identityKey, "Recent"))
            }

            reports.networkLocations.forEachIndexed { index, loc ->
                val time = reports.networkLocationTimestamps.getOrNull(index)
                locations.add(buildLocationEntry(loc, time, identityKey, "Network ${index + 1}"))
            }
        }

        return LocationResult(deviceName, locations)
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

    companion object {
        private const val TAG = "DeviceRepository"
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
