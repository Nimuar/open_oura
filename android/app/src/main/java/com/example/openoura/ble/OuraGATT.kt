package com.example.openoura.ble

import java.util.UUID

object OuraGATT {
    val SERVICE_UUID: UUID = UUID.fromString("98ED0001-A541-11E4-B6A0-0002A5D5C51B")
    val WRITE_CHAR_UUID: UUID = UUID.fromString("98ED0002-A541-11E4-B6A0-0002A5D5C51B")
    val NOTIFY_CHAR_UUID: UUID = UUID.fromString("98ED0003-A541-11E4-B6A0-0002A5D5C51B")

    const val FEATURE_DAYTIME_HR: Byte = 0x02
    const val FEATURE_MODE_CONNECTED_LIVE: Byte = 0x03
    const val FEATURE_MODE_AUTOMATIC: Byte = 0x01

    const val REALTIME_ACM: Int = 0x20
    const val REALTIME_ACM_RESPONSE_TAG: Byte = 0x33

    const val HISTORY_EVENT_PREFIX: Byte = 0x41
}

data class Packet(
    val tag: Byte,
    val payload: ByteArray
) {
    val extTag: Byte?
        get() = if (tag == 0x2f.toByte()) payload.firstOrNull() else null

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
            val end = minOf(2 + len, frame.size)
            return Packet(tag, frame.copyOfRange(2, end))
        }
    }
}

object Req {
    val firmware = byteArrayOf(0x08, 0x03, 0x00, 0x00, 0x00)
    val battery = packet(0x0c.toByte(), byteArrayOf())
    val authNonce = byteArrayOf(0x2f, 0x01, 0x2b)
    val serial = byteArrayOf(0x18, 0x03, 0x08, 0x00, 0x10)
    val hardware = byteArrayOf(0x18, 0x03, 0x18, 0x00, 0x10)
    val realtimeOff = packet(0x06.toByte(), byteArrayOf(0, 0, 0, 0))
    val factoryReset = byteArrayOf(0x1a, 0x00)

    fun packet(tag: Byte, payload: ByteArray): ByteArray {
        val frame = ByteArray(2 + payload.size)
        frame[0] = tag
        frame[1] = payload.size.toByte()
        System.arraycopy(payload, 0, frame, 2, payload.size)
        return frame
    }

    private fun le32(v: Int): ByteArray {
        return byteArrayOf(
            (v and 0xff).toByte(),
            ((v shr 8) and 0xff).toByte(),
            ((v shr 16) and 0xff).toByte(),
            ((v shr 24) and 0xff).toByte()
        )
    }

    private fun le16(v: Short): ByteArray {
        return byteArrayOf(
            (v.toInt() and 0xff).toByte(),
            ((v.toInt() shr 8) and 0xff).toByte()
        )
    }

    fun authenticate(enc: ByteArray): ByteArray = packet(0x2f, byteArrayOf(0x2d.toByte()) + enc)

    fun setAuthKey(key: ByteArray): ByteArray = packet(0x24, key)

    fun featureStatus(f: Byte): ByteArray = byteArrayOf(0x2f, 0x02, 0x20, f)

    fun setFeatureMode(f: Byte, mode: Byte): ByteArray = byteArrayOf(0x2f, 0x03, 0x22, f, mode)

    fun setNotification(flags: Byte): ByteArray = packet(0x1c, byteArrayOf(flags))

    fun syncTime(unix: Long, tzHalfHours: Byte): ByteArray {
        val p = ByteArray(9)
        for (i in 0..7) {
            p[i] = ((unix shr (8 * i)) and 0xff).toByte()
        }
        p[8] = tzHalfHours
        return packet(0x12, p)
    }

    fun getEvent(start: Int, maxEvents: Byte, flags: Int): ByteArray {
        return packet(0x10, le32(start) + byteArrayOf(maxEvents) + le32(flags))
    }

    fun setRealtime(bitmask: Int, minutes: Short, delay: Byte): ByteArray {
        return packet(0x06, le32(bitmask) + le16(minutes) + byteArrayOf(delay))
    }
}
