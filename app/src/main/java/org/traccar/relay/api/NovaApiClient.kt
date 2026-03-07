package org.traccar.relay.api

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.traccar.relay.proto.CanonicId
import org.traccar.relay.proto.DeviceType
import org.traccar.relay.proto.DevicesList
import org.traccar.relay.proto.DevicesListRequest
import org.traccar.relay.proto.DevicesListRequestPayload
import org.traccar.relay.proto.ExecuteActionDeviceIdentifier
import org.traccar.relay.proto.DeviceComponent
import org.traccar.relay.proto.ExecuteActionLocateTrackerType
import org.traccar.relay.proto.ExecuteActionSoundType
import org.traccar.relay.proto.ExecuteActionRequest
import org.traccar.relay.proto.ExecuteActionRequestMetadata
import org.traccar.relay.proto.ExecuteActionScope
import org.traccar.relay.proto.ExecuteActionType
import org.traccar.relay.proto.GcmCloudMessagingIdProtobuf
import org.traccar.relay.proto.IdentifierInformationType
import org.traccar.relay.proto.SpotContributorType
import org.traccar.relay.proto.Time
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

    fun executeAction(admToken: String, payload: ByteArray) {
        val contentType = "application/x-www-form-urlencoded; charset=UTF-8".toMediaType()
        val request = Request.Builder()
            .url(BASE_URL + "nbe_execute_action")
            .post(payload.toRequestBody(contentType))
            .header("Authorization", "Bearer $admToken")
            .header("Accept-Language", "en-US")
            .header("User-Agent", USER_AGENT)
            .build()

        client.newCall(request).execute()
    }

    fun buildLocationRequest(
        canonicDeviceId: String,
        fcmToken: String,
        requestUuid: String = UUID.randomUUID().toString(),
    ): Pair<ByteArray, String> {
        val request = ExecuteActionRequest(
            scope = ExecuteActionScope(
                type = DeviceType.SPOT_DEVICE,
                device = ExecuteActionDeviceIdentifier(
                    canonicId = CanonicId(id = canonicDeviceId)
                ),
            ),
            action = ExecuteActionType(
                locateTracker = ExecuteActionLocateTrackerType(
                    lastHighTrafficEnablingTime = Time(seconds = 1732120060, nanos = 0),
                    contributorType = SpotContributorType.FMDN_ALL_LOCATIONS,
                ),
            ),
            requestMetadata = ExecuteActionRequestMetadata(
                type = DeviceType.SPOT_DEVICE,
                requestUuid = requestUuid,
                fmdClientUuid = UUID.randomUUID().toString(),
                gcmRegistrationId = GcmCloudMessagingIdProtobuf(id = fcmToken),
                unknown = true,
            ),
        )
        return Pair(request.encode(), requestUuid)
    }

    fun buildSoundRequest(
        canonicDeviceId: String,
        fcmToken: String,
        start: Boolean,
    ): ByteArray {
        val soundType = ExecuteActionSoundType(
            component = DeviceComponent.DEVICE_COMPONENT_UNSPECIFIED,
        )
        val request = ExecuteActionRequest(
            scope = ExecuteActionScope(
                type = DeviceType.SPOT_DEVICE,
                device = ExecuteActionDeviceIdentifier(
                    canonicId = CanonicId(id = canonicDeviceId)
                ),
            ),
            action = ExecuteActionType(
                startSound = if (start) soundType else null,
                stopSound = if (!start) soundType else null,
            ),
            requestMetadata = ExecuteActionRequestMetadata(
                type = DeviceType.SPOT_DEVICE,
                requestUuid = UUID.randomUUID().toString(),
                fmdClientUuid = UUID.randomUUID().toString(),
                gcmRegistrationId = GcmCloudMessagingIdProtobuf(id = fcmToken),
                unknown = true,
            ),
        )
        return request.encode()
    }
}
