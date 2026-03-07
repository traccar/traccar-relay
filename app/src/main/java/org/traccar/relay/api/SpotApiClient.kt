package org.traccar.relay.api

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.traccar.relay.proto.GetEidInfoForE2eeDevicesRequest
import org.traccar.relay.proto.GetEidInfoForE2eeDevicesResponse
import java.nio.ByteBuffer

object SpotApiClient {

    private const val URL =
        "https://spot-pa.googleapis.com/google.internal.spot.v1.SpotService/GetEidInfoForE2eeDevices"

    private val client = OkHttpClient()

    fun getEncryptedOwnerKey(spotToken: String): ByteArray {
        val proto = GetEidInfoForE2eeDevicesRequest(
            ownerKeyVersion = -1,
            hasOwnerKeyVersion = true,
        ).encode()

        val frame = ByteBuffer.allocate(5 + proto.size)
            .put(0) // no compression
            .putInt(proto.size)
            .put(proto)
            .array()

        val request = Request.Builder()
            .url(URL)
            .post(frame.toRequestBody("application/grpc".toMediaType()))
            .header("Content-Type", "application/grpc")
            .header("Te", "trailers")
            .header("Authorization", "Bearer $spotToken")
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.bytes() ?: throw Exception("Empty Spot API response")

        if (body.size < 5) throw Exception("Invalid gRPC response")

        val buf = ByteBuffer.wrap(body)
        buf.get() // compression flag
        val length = buf.int
        val protoBytes = ByteArray(length)
        buf.get(protoBytes)

        val resp = GetEidInfoForE2eeDevicesResponse.ADAPTER.decode(protoBytes)
        return resp.encryptedOwnerKeyAndMetadata?.encryptedOwnerKey?.toByteArray()
            ?: throw Exception("No encrypted owner key in response")
    }
}
