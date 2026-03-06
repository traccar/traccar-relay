package org.traccar.sync.push

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.traccar.sync.api.DeviceRepository

class PushNotificationService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        DeviceRepository(this).onFirebaseTokenChanged(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val deviceId = message.data["deviceId"] ?: return
        if (message.data["command"] == "positionSingle") {
            try {
                DeviceRepository(this).requestAndUploadLocation(deviceId)
            } catch (e: Exception) {
                Log.e(TAG, "Error handling location request", e)
            }
        }
    }

    companion object {
        private const val TAG = "PushNotificationService"
    }
}
