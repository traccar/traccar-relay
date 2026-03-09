package org.traccar.relay.push

import android.util.Log
import org.traccar.relay.proto.mcs.Close
import org.traccar.relay.proto.mcs.DataMessageStanza
import org.traccar.relay.proto.mcs.HeartbeatAck
import org.traccar.relay.proto.mcs.HeartbeatPing
import org.traccar.relay.proto.mcs.IqStanza
import org.traccar.relay.proto.mcs.HeartbeatStat
import org.traccar.relay.proto.mcs.LoginRequest
import org.traccar.relay.proto.mcs.LoginResponse
import org.traccar.relay.proto.mcs.Setting
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import javax.net.ssl.SSLSocketFactory

class McsClient {

    companion object {
        private const val TAG = "McsClient"
        private const val MCS_HOST = "mtalk.google.com"
        private const val MCS_PORT = 5228
        private const val MCS_VERSION = 41

        private const val TAG_HEARTBEAT_PING = 0
        private const val TAG_HEARTBEAT_ACK = 1
        private const val TAG_LOGIN_REQUEST = 2
        private const val TAG_LOGIN_RESPONSE = 3
        private const val TAG_CLOSE = 4
        private const val TAG_IQ_STANZA = 7
        private const val TAG_DATA_MESSAGE = 8
    }

    private var socket: javax.net.ssl.SSLSocket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null
    @Volatile
    private var running = false
    private var firstMessage = true

    fun connect(
        androidId: String,
        securityToken: String,
        onMessage: (DataMessageStanza) -> Unit,
    ) {
        running = true
        firstMessage = true

        val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
        socket = sslFactory.createSocket(MCS_HOST, MCS_PORT) as javax.net.ssl.SSLSocket
        socket!!.startHandshake()

        input = DataInputStream(socket!!.inputStream)
        output = DataOutputStream(socket!!.outputStream)

        sendLogin(androidId, securityToken)

        while (running) {
            try {
                val msg = receiveMessage() ?: continue
                handleMessage(msg, onMessage)
            } catch (e: IOException) {
                if (running) {
                    Log.e(TAG, "Read error", e)
                }
                break
            }
        }
    }

    fun disconnect() {
        running = false
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        input = null
        output = null
    }

    private fun sendLogin(androidId: String, securityToken: String) {
        val hexId = androidId.toLong().toString(16)
        val req = LoginRequest(
            id = "133.0.6917.92",
            domain = "mcs.android.com",
            user = androidId,
            resource = androidId,
            auth_token = securityToken,
            device_id = "android-$hexId",
            setting = listOf(Setting(name = "new_vc", value_ = "1")),
            use_rmq2 = true,
            adaptive_heartbeat = false,
            heartbeat_stat = HeartbeatStat(ip = "", timeout = true, interval_ms = 10000),
            auth_service = LoginRequest.AuthService.ANDROID_ID,
            network_type = 1,
        )

        sendMessage(TAG_LOGIN_REQUEST, req.encode())
    }

    private fun sendMessage(tag: Int, payload: ByteArray) {
        val out = output ?: return
        synchronized(out) {
            if (firstMessage) {
                out.writeByte(MCS_VERSION)
            }
            out.writeByte(tag)
            writeVarint32(out, payload.size)
            out.write(payload)
            out.flush()
        }
    }

    private fun receiveMessage(): Any? {
        val inp = input ?: return null

        val tag: Int
        if (firstMessage) {
            val version = inp.readUnsignedByte()
            tag = inp.readUnsignedByte()
            if (version < 38) {
                throw IOException("Unsupported MCS version: $version")
            }
            firstMessage = false
        } else {
            tag = inp.readUnsignedByte()
        }

        val size = readVarint32(inp)
        if (size < 0) return null

        val buf = ByteArray(size)
        inp.readFully(buf)

        return when (tag) {
            TAG_HEARTBEAT_PING -> HeartbeatPing.ADAPTER.decode(buf)
            TAG_HEARTBEAT_ACK -> HeartbeatAck.ADAPTER.decode(buf)
            TAG_LOGIN_RESPONSE -> LoginResponse.ADAPTER.decode(buf)
            TAG_CLOSE -> Close.ADAPTER.decode(buf)
            TAG_IQ_STANZA -> IqStanza.ADAPTER.decode(buf)
            TAG_DATA_MESSAGE -> DataMessageStanza.ADAPTER.decode(buf)
            else -> null
        }
    }

    private fun handleMessage(msg: Any, onDataMessage: (DataMessageStanza) -> Unit) {
        when (msg) {
            is LoginResponse -> {
                if (msg.error != null) {
                    Log.e(TAG, "Login error: ${msg.error}")
                }
            }
            is HeartbeatPing -> {
                sendMessage(TAG_HEARTBEAT_ACK, HeartbeatAck().encode())
            }
            is DataMessageStanza -> {
                onDataMessage(msg)
            }
            is Close -> {
                running = false
            }
        }
    }

    private fun readVarint32(input: DataInputStream): Int {
        var result = 0
        var shift = 0
        while (true) {
            val b = input.readUnsignedByte()
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
        }
        return result
    }

    private fun writeVarint32(output: DataOutputStream, value: Int) {
        var v = value
        while (true) {
            if (v and 0x7F.inv() == 0) {
                output.writeByte(v)
                return
            }
            output.writeByte((v and 0x7F) or 0x80)
            v = v ushr 7
        }
    }
}
