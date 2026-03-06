package org.traccar.sync.api

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
}
