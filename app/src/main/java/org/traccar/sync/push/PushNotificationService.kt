package org.traccar.sync.push

import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.traccar.sync.api.DeviceRepository
import java.util.concurrent.TimeUnit

class PushNotificationService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        DeviceRepository(this).onFirebaseTokenChanged(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val deviceId = message.data["deviceId"] ?: return
        when (message.data["command"]) {
            "positionSingle" -> {
                try {
                    DeviceRepository(this).requestAndUploadLocation(deviceId)
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling location request", e)
                }
            }
            "positionPeriodic" -> {
                val interval = message.data["interval"]?.toLongOrNull()
                    ?.coerceAtLeast(PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS / 1000)
                    ?: return
                val workRequest = PeriodicWorkRequestBuilder<LocationWorker>(
                    interval, TimeUnit.SECONDS,
                ).setInputData(
                    workDataOf(LocationWorker.KEY_DEVICE_ID to deviceId),
                ).build()
                WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                    "$WORK_NAME_PREFIX$deviceId",
                    ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
                    workRequest,
                )
            }
            "positionStop" -> {
                WorkManager.getInstance(this).cancelUniqueWork("$WORK_NAME_PREFIX$deviceId")
            }
        }
    }

    companion object {
        private const val TAG = "PushNotificationService"
        private const val WORK_NAME_PREFIX = "periodic_location_"
    }
}
