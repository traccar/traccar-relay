package org.traccar.sync.api

import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

object TraccarApiClient {

    private val client = OkHttpClient()

    fun registerDevice(serverUrl: String, deviceId: String, notificationToken: String) {
        val body = FormBody.Builder()
            .add("id", deviceId)
            .add("notificationToken", notificationToken)
            .build()
        val request = Request.Builder()
            .url(serverUrl)
            .post(body)
            .build()
        client.newCall(request).execute().close()
    }

    fun sendLocation(
        serverUrl: String,
        deviceId: String,
        lat: Double,
        lon: Double,
        timestamp: Long,
        accuracy: Float? = null,
        altitude: Int? = null,
    ) {
        val url = serverUrl.toHttpUrl().newBuilder()
            .addQueryParameter("id", deviceId)
            .addQueryParameter("lat", lat.toString())
            .addQueryParameter("lon", lon.toString())
            .addQueryParameter("timestamp", timestamp.toString())
        accuracy?.let { url.addQueryParameter("accuracy", it.toString()) }
        altitude?.let { url.addQueryParameter("altitude", it.toString()) }
        val request = Request.Builder().url(url.build()).build()
        client.newCall(request).execute().close()
    }
}
