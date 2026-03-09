package org.traccar.relay.push

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.traccar.relay.api.DeviceRepository

class LocationWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val deviceId = inputData.getString(KEY_DEVICE_ID) ?: return Result.failure()
        Log.i(TAG, "Starting periodic location request for device $deviceId")
        return try {
            DeviceRepository(applicationContext).requestAndUploadLocation(deviceId)
            Log.i(TAG, "Periodic location request completed for device $deviceId")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error handling periodic location request", e)
            Result.failure()
        }
    }

    companion object {
        private const val TAG = "LocationWorker"
        const val KEY_DEVICE_ID = "deviceId"
    }
}
