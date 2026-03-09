package org.traccar.relay.push

import android.util.Base64
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.traccar.relay.proto.checkin.AndroidCheckinProto
import org.traccar.relay.proto.checkin.AndroidCheckinRequest
import org.traccar.relay.proto.checkin.AndroidCheckinResponse
import org.traccar.relay.proto.checkin.ChromeBuildProto
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.UUID

data class FcmCredentials(
    val gcmAndroidId: String,
    val gcmSecurityToken: String,
    val gcmToken: String,
    val gcmAppId: String,
    val fcmToken: String,
    val publicKey: String,
    val privateKey: String,
    val authSecret: String,
)

object FcmRegistrationClient {

    private const val GCM_CHECKIN_URL = "https://android.clients.google.com/checkin"
    private const val GCM_REGISTER_URL = "https://android.clients.google.com/c2dm/register3"
    private const val FCM_INSTALLATION_URL = "https://firebaseinstallations.googleapis.com/v1/"
    private const val FCM_REGISTRATION_URL = "https://fcmregistrations.googleapis.com/v1/"
    private const val FCM_SEND_URL = "https://fcm.googleapis.com/fcm/send/"
    private const val GCM_SERVER_KEY_B64 =
        "BDOU99-h67HcA6JeFXHbSNMu7e2yNNu3RzoMj8TM4W88jITfq7ZmPvIM1Iv-4_l2LxQcYwhqby2xGpWwzjfAnG4"

    private const val PROJECT_ID = "google.com:api-project-289722593072"
    private const val APP_ID = "1:289722593072:android:3cfcf5bc359f0308"
    private const val API_KEY = "AIzaSyD_gko3P392v6how2H7UpdeXQ0v2HLettc"
    private const val BUNDLE_ID = "com.google.android.apps.adm"
    private const val CHROME_VERSION = "133.0.6917.92"
    private const val ADM_PACKAGE = "com.google.android.apps.adm"
    private const val ADM_CERT = "38918a453d07199354f8b19af05ec6562ced5788"

    private val client = OkHttpClient()

    fun register(): FcmCredentials {
        val checkinResult = gcmCheckin()
        val androidId = checkinResult.first
        val securityToken = checkinResult.second

        val gcmRegisterResult = gcmRegister(androidId, securityToken)
        val gcmToken = gcmRegisterResult.first
        val gcmAppId = gcmRegisterResult.second

        val keys = generateKeys()

        val installResult = fcmInstall()
        val installToken = installResult.first

        val fcmToken = fcmRegister(gcmToken, installToken, keys)

        return FcmCredentials(
            gcmAndroidId = androidId,
            gcmSecurityToken = securityToken,
            gcmToken = gcmToken,
            gcmAppId = gcmAppId,
            fcmToken = fcmToken,
            publicKey = keys.public_,
            privateKey = keys.private_,
            authSecret = keys.secret,
        )
    }

    private fun gcmCheckin(): Pair<String, String> {
        val chrome = ChromeBuildProto(
            platform = ChromeBuildProto.Platform.PLATFORM_LINUX,
            chrome_version = CHROME_VERSION,
            channel = ChromeBuildProto.Channel.CHANNEL_STABLE,
        )

        val checkin = AndroidCheckinProto(
            type = org.traccar.relay.proto.checkin.DeviceType.DEVICE_CHROME_BROWSER,
            chrome_build = chrome,
        )

        val payload = AndroidCheckinRequest(
            checkin = checkin,
            version = 3,
            user_serial_number = 0,
        )

        val firstResponse = performCheckin(payload)
        val androidId = firstResponse.android_id
            ?: throw Exception("No android_id in checkin response")
        val securityToken = firstResponse.security_token
            ?: throw Exception("No security_token in checkin response")

        // Second checkin to confirm device registration
        val confirmPayload = AndroidCheckinRequest(
            checkin = checkin,
            version = 3,
            user_serial_number = 0,
            id = androidId,
            security_token = securityToken,
        )
        performCheckin(confirmPayload)

        return Pair(androidId.toString(), securityToken.toString())
    }

    private fun performCheckin(payload: AndroidCheckinRequest): AndroidCheckinResponse {
        val request = Request.Builder()
            .url(GCM_CHECKIN_URL)
            .post(payload.encode().toRequestBody("application/x-protobuf".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        val bytes = response.body?.bytes() ?: throw Exception("Empty checkin response")
        return AndroidCheckinResponse.ADAPTER.decode(bytes)
    }

    private fun gcmRegister(androidId: String, securityToken: String): Pair<String, String> {
        val appId = "wp:$BUNDLE_ID#${UUID.randomUUID()}"

        for (attempt in 1..5) {
            val body = FormBody.Builder()
                .add("app", "org.chromium.linux")
                .add("X-subtype", appId)
                .add("device", androidId)
                .add("sender", GCM_SERVER_KEY_B64)
                .build()

            val request = Request.Builder()
                .url(GCM_REGISTER_URL)
                .post(body)
                .header("Authorization", "AidLogin $androidId:$securityToken")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .build()

            val response = client.newCall(request).execute()
            val text = response.body?.string() ?: throw Exception("Empty GCM register response")

            if (text.contains("Error")) {
                if (attempt < 5) {
                    Thread.sleep(1000L * attempt)
                    continue
                }
                throw Exception("GCM register error: $text")
            }

            val token = text.substringAfter("token=", "")
            if (token.isEmpty()) {
                throw Exception("No token in GCM register response: $text")
            }

            return Pair(token, appId)
        }

        throw Exception("GCM register failed after retries")
    }

    data class EcKeys(val public_: String, val private_: String, val secret: String)

    private fun generateKeys(): EcKeys {
        val keyPairGenerator = KeyPairGenerator.getInstance("EC")
        keyPairGenerator.initialize(ECGenParameterSpec("secp256r1"))
        val keyPair = keyPairGenerator.generateKeyPair()

        val publicKey = keyPair.public as ECPublicKey
        val privateKey = keyPair.private as ECPrivateKey

        val publicKeyBytes = publicKey.encoded // DER SubjectPublicKeyInfo
        val privateKeyBytes = privateKey.encoded // DER PKCS8

        // Skip 26-byte DER header to get raw public key bytes
        val publicKeyRaw = publicKeyBytes.copyOfRange(26, publicKeyBytes.size)

        val secret = ByteArray(16)
        SecureRandom().nextBytes(secret)

        return EcKeys(
            public_ = Base64.encodeToString(publicKeyRaw, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP),
            private_ = Base64.encodeToString(privateKeyBytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP),
            secret = Base64.encodeToString(secret, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP),
        )
    }

    private fun fcmInstall(): Pair<String, String> {
        val fid = ByteArray(17)
        SecureRandom().nextBytes(fid)
        fid[0] = (0b01110000 or (fid[0].toInt() and 0x0F)).toByte()
        val fid64 = Base64.encodeToString(fid, Base64.NO_WRAP)

        val hbHeader = Base64.encodeToString(
            """{"heartbeats":[],"version":2}""".toByteArray(),
            Base64.NO_WRAP
        )

        val payload = JSONObject().apply {
            put("appId", APP_ID)
            put("authVersion", "FIS_v2")
            put("fid", fid64)
            put("sdkVersion", "w:0.6.6")
        }

        val request = Request.Builder()
            .url("${FCM_INSTALLATION_URL}projects/$PROJECT_ID/installations")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .header("x-firebase-client", hbHeader)
            .header("x-goog-api-key", API_KEY)
            .header("X-Android-Package", ADM_PACKAGE)
            .header("X-Android-Cert", ADM_CERT)
            .build()

        val response = client.newCall(request).execute()
        val text = response.body?.string() ?: throw Exception("Empty FCM install response")

        if (response.code != 200) {
            throw Exception("FCM install error ${response.code}: $text")
        }

        val json = JSONObject(text)
        val token = json.getJSONObject("authToken").getString("token")
        val installFid = json.getString("fid")

        return Pair(token, installFid)
    }

    private fun fcmRegister(gcmToken: String, installToken: String, keys: EcKeys): String {
        val payload = JSONObject().apply {
            put("web", JSONObject().apply {
                put("applicationPubKey", JSONObject.NULL)
                put("auth", keys.secret)
                put("endpoint", "$FCM_SEND_URL$gcmToken")
                put("p256dh", keys.public_)
            })
        }

        val request = Request.Builder()
            .url("${FCM_REGISTRATION_URL}projects/$PROJECT_ID/registrations")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .header("x-goog-api-key", API_KEY)
            .header("x-goog-firebase-installations-auth", installToken)
            .header("X-Android-Package", ADM_PACKAGE)
            .header("X-Android-Cert", ADM_CERT)
            .header("Content-Type", "application/json")
            .build()

        val response = client.newCall(request).execute()
        val text = response.body?.string() ?: throw Exception("Empty FCM register response")

        if (response.code != 200) {
            throw Exception("FCM register error ${response.code}: $text")
        }

        val json = JSONObject(text)
        return json.getString("token")
    }
}
