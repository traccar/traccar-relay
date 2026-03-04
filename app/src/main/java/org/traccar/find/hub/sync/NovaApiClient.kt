package org.traccar.find.hub.sync

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.traccar.find.hub.sync.proto.DeviceType
import org.traccar.find.hub.sync.proto.DevicesList
import org.traccar.find.hub.sync.proto.DevicesListRequest
import org.traccar.find.hub.sync.proto.DevicesListRequestPayload
import org.traccar.find.hub.sync.proto.IdentifierInformationType
import java.util.UUID

data class Device(val name: String, val id: String)

object NovaApiClient {

    private const val BASE_URL = "https://android.googleapis.com/nova/"
    private const val USER_AGENT = "fmd/20006320; gzip"

    private val client = OkHttpClient()

    fun listDevices(admToken: String): List<Device> {
        val payload = DevicesListRequest(
            deviceListRequestPayload = DevicesListRequestPayload(
                type = DeviceType.SPOT_DEVICE,
                id = UUID.randomUUID().toString()
            )
        ).encode()

        val contentType = "application/x-www-form-urlencoded; charset=UTF-8".toMediaType()
        val request = Request.Builder()
            .url(BASE_URL + "nbe_list_devices")
            .post(payload.toRequestBody(contentType))
            .header("Authorization", "Bearer $admToken")
            .header("Accept-Language", "en-US")
            .header("User-Agent", USER_AGENT)
            .build()

        val response = client.newCall(request).execute()
        val bytes = response.body?.bytes() ?: return emptyList()

        val devicesList = DevicesList.ADAPTER.decode(bytes)
        val result = mutableListOf<Device>()

        for (device in devicesList.deviceMetadata) {
            val name = device.userDefinedDeviceName ?: ""
            val identifierInfo = device.identifierInformation ?: continue
            val canonicIds = if (identifierInfo.type == IdentifierInformationType.IDENTIFIER_ANDROID) {
                identifierInfo.phoneInformation?.canonicIds?.canonicId.orEmpty()
            } else {
                identifierInfo.canonicIds?.canonicId.orEmpty()
            }
            for (canonicId in canonicIds) {
                result.add(Device(name, canonicId.id ?: ""))
            }
        }

        return result
    }
}
