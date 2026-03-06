package org.traccar.sync.push

import com.google.firebase.messaging.FirebaseMessagingService
import org.traccar.sync.api.DeviceRepository

class PushNotificationService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        DeviceRepository(this).onFirebaseTokenChanged(token)
    }
}
