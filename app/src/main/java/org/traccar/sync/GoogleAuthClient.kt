package org.traccar.sync

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request

object GoogleAuthClient {

    private const val AUTH_URL = "https://android.clients.google.com/auth"
    private const val USER_AGENT = "GoogleAuth/1.4"
    private const val CLIENT_SIG = "38918a453d07199354f8b19af05ec6562ced5788"

    private val client = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()

    fun exchangeToken(email: String, oauthToken: String, androidId: String): Map<String, String> {
        val body = FormBody.Builder()
            .add("accountType", "HOSTED_OR_GOOGLE")
            .add("Email", email)
            .add("has_permission", "1")
            .add("add_account", "1")
            .add("ACCESS_TOKEN", "1")
            .add("Token", oauthToken)
            .add("service", "ac2dm")
            .add("source", "android")
            .add("androidId", androidId)
            .add("device_country", "us")
            .add("operatorCountry", "us")
            .add("lang", "en")
            .add("sdk_version", "17")
            .add("google_play_services_version", "240913000")
            .add("client_sig", CLIENT_SIG)
            .add("callerSig", CLIENT_SIG)
            .add("droidguard_results", "dummy123")
            .build()

        return performAuthRequest(body)
    }

    fun performOAuth(
        email: String,
        aasToken: String,
        androidId: String,
        scope: String,
        app: String = "com.google.android.apps.adm",
    ): Map<String, String> {
        val body = FormBody.Builder()
            .add("accountType", "HOSTED_OR_GOOGLE")
            .add("Email", email)
            .add("has_permission", "1")
            .add("EncryptedPasswd", aasToken)
            .add("service", "oauth2:https://www.googleapis.com/auth/$scope")
            .add("source", "android")
            .add("androidId", androidId)
            .add("app", app)
            .add("client_sig", CLIENT_SIG)
            .add("device_country", "us")
            .add("operatorCountry", "us")
            .add("lang", "en")
            .add("sdk_version", "17")
            .add("google_play_services_version", "240913000")
            .build()

        return performAuthRequest(body)
    }

    private fun performAuthRequest(body: FormBody): Map<String, String> {
        val request = Request.Builder()
            .url(AUTH_URL)
            .post(body)
            .header("User-Agent", USER_AGENT)
            .header("Content-type", "application/x-www-form-urlencoded")
            .header("Accept-Encoding", "identity")
            .build()

        val response = client.newCall(request).execute()
        val text = response.body?.string() ?: ""

        return text.lines()
            .filter { it.contains("=") }
            .associate {
                val (key, value) = it.split("=", limit = 2)
                key to value
            }
    }
}
