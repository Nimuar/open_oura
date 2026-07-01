package com.example.openoura.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

object OuraGATT {
    val SERVICE_UUID: UUID = UUID.fromString("98ED0001-A541-11E4-B6A0-0002A5D5C51B")
    val WRITE_CHAR_UUID: UUID = UUID.fromString("98ED0002-A541-11E4-B6A0-0002A5D5C51B")
    val NOTIFY_CHAR_UUID: UUID = UUID.fromString("98ED0003-A541-11E4-B6A0-0002A5D5C51B")

    // Feature Mode settings
    const val FEATURE_DAYTIME_HR: Byte = 0x02
    const val FEATURE_MODE_CONNECTED_LIVE: Byte = 0x03
    const val FEATURE_MODE_AUTOMATIC: Byte = 0x01

    // Protocol Command Tags
    const val TAG_GET_EVENT: Byte = 0x10
    const val TAG_EVENT_REPORT: Byte = 0x11
    const val TAG_TIME_SYNC: Byte = 0x12
    const val TAG_SET_AUTH_KEY: Byte = 0x24
    const val TAG_AUTH_STATUS: Byte = 0x25
    const val TAG_FLIGHT_MODE: Byte = 0x26
    const val TAG_AUTH: Byte = 0x2f
    const val TAG_NOTIFY_CONFIG: Byte = 0x1c
    const val TAG_FIRMWARE: Byte = 0x08
    const val TAG_BATTERY: Byte = 0x0c
    const val TAG_SERIAL: Byte = 0x18
    const val TAG_REALTIME: Byte = 0x06
    const val TAG_FACTORY_RESET: Byte = 0x1a

    // Protocol Constants
    const val EXT_AUTH_CHALLENGE: Byte = 0x2b
    const val EXT_AUTH_NONCE: Byte = 0x2c
    const val EXT_AUTH_RESPONSE: Byte = 0x2d
    const val EXT_AUTH_VERIFY: Byte = 0x2e

    const val REALTIME_ACM: Int = 0x20
    const val REALTIME_ACM_RESPONSE_TAG: Byte = 0x33
    const val HISTORY_EVENT_PREFIX: Byte = 0x41
}

data class Packet(
    val tag: Byte,
    val payload: ByteArray
) {
    val extTag: Byte?
        get() = if (tag == OuraGATT.TAG_AUTH) payload.firstOrNull() else null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Packet) return false
        if (tag != other.tag) return false
        return payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = tag.toInt()
        result = 31 * result + payload.contentHashCode()
        return result
    }

    companion object {
        fun parse(frame: ByteArray): Packet? {
            if (frame.size < 2) return null
            val tag = frame[0]
            val len = frame[1].toInt() and 0xff
            if (frame.size < 2 + len) {
                return null // Fail-fast: do not silently truncate!
            }
            return Packet(tag, frame.copyOfRange(2, 2 + len))
        }
    }
}

object Req {
    val firmware = byteArrayOf(OuraGATT.TAG_FIRMWARE, 0x03, 0x00, 0x00, 0x00)
    val battery = packet(OuraGATT.TAG_BATTERY, byteArrayOf())
    val authNonce = byteArrayOf(OuraGATT.TAG_AUTH, 0x01, OuraGATT.EXT_AUTH_CHALLENGE)
    val serial = byteArrayOf(OuraGATT.TAG_SERIAL, 0x03, 0x08, 0x00, 0x10)
    val hardware = byteArrayOf(OuraGATT.TAG_SERIAL, 0x03, 0x18, 0x00, 0x10)
    val realtimeOff = packet(OuraGATT.TAG_REALTIME, byteArrayOf(0, 0, 0, 0))
    val factoryReset = byteArrayOf(OuraGATT.TAG_FACTORY_RESET, 0x00)

    fun packet(tag: Byte, payload: ByteArray): ByteArray {
        val buffer = ByteBuffer.allocate(2 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(tag)
        buffer.put(payload.size.toByte())
        buffer.put(payload)
        return buffer.array()
    }

    fun authenticate(enc: ByteArray): ByteArray {
        val payload = ByteBuffer.allocate(1 + enc.size).order(ByteOrder.LITTLE_ENDIAN)
        payload.put(OuraGATT.EXT_AUTH_RESPONSE)
        payload.put(enc)
        return packet(OuraGATT.TAG_AUTH, payload.array())
    }

    fun setAuthKey(key: ByteArray): ByteArray = packet(OuraGATT.TAG_SET_AUTH_KEY, key)

    fun featureStatus(f: Byte): ByteArray {
        val buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(OuraGATT.TAG_AUTH)
        buffer.put(0x02)
        buffer.put(0x20.toByte())
        buffer.put(f)
        return buffer.array()
    }

    fun setFeatureMode(f: Byte, mode: Byte): ByteArray {
        val buffer = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(OuraGATT.TAG_AUTH)
        buffer.put(0x03)
        buffer.put(0x22.toByte())
        buffer.put(f)
        buffer.put(mode)
        return buffer.array()
    }

    fun setNotification(flags: Byte): ByteArray = packet(OuraGATT.TAG_NOTIFY_CONFIG, byteArrayOf(flags))

    fun syncTime(unix: Long, tzHalfHours: Byte): ByteArray {
        val payload = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN)
        payload.putLong(unix)
        payload.put(8, tzHalfHours)
        return packet(OuraGATT.TAG_TIME_SYNC, payload.array())
    }

    fun getEvent(start: Int, maxEvents: Byte, flags: Int): ByteArray {
        val payload = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN)
        payload.putInt(start)
        payload.put(maxEvents)
        payload.putInt(flags)
        return packet(OuraGATT.TAG_GET_EVENT, payload.array())
    }

    fun setRealtime(bitmask: Int, minutes: Short, delay: Byte): ByteArray {
        val payload = ByteBuffer.allocate(7).order(ByteOrder.LITTLE_ENDIAN)
        payload.putInt(bitmask)
        payload.putShort(minutes)
        payload.put(delay)
        return packet(OuraGATT.TAG_REALTIME, payload.array())
    }
}
