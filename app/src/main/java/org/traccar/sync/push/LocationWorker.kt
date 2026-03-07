package org.traccar.sync.push

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.traccar.sync.api.DeviceRepository

class LocationWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val deviceId = inputData.getString(KEY_DEVICE_ID) ?: return Result.failure()
        return try {
            DeviceRepository(applicationContext).requestAndUploadLocation(deviceId)
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error handling periodic location request", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "LocationWorker"
        const val KEY_DEVICE_ID = "deviceId"
    }
}
