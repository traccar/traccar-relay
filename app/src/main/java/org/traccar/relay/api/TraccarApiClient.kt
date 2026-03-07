package org.traccar.relay.api

import okhttp3.FormBody
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
        val body = FormBody.Builder()
            .add("id", deviceId)
            .add("lat", lat.toString())
            .add("lon", lon.toString())
            .add("timestamp", timestamp.toString())
        accuracy?.let { body.add("accuracy", it.toString()) }
        altitude?.let { body.add("altitude", it.toString()) }
        val request = Request.Builder()
            .url(serverUrl)
            .post(body.build())
            .build()
        client.newCall(request).execute().close()
    }
}
