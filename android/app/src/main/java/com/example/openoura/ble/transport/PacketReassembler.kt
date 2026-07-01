package com.example.openoura.ble.transport

import android.util.Log
import com.example.openoura.ble.Packet

/**
 * Accumulates incoming BLE notification byte fragments and reassembles complete packets.
 * Enforces strict length boundary validations to prevent silent truncation.
 */
class PacketReassembler {
    private var notificationBuffer = ByteArray(0)

    /**
     * Accumulates incoming raw data fragments and extracts completed packets.
     */
    @Synchronized
    fun feed(data: ByteArray): List<Packet> {
        notificationBuffer += data
        val packets = mutableListOf<Packet>()

        while (notificationBuffer.size >= 2) {
            val tag = notificationBuffer[0]
            val len = notificationBuffer[1].toInt() and 0xff
            val totalExpected = 2 + len

            if (notificationBuffer.size >= totalExpected) {
                val fullPacketBytes = notificationBuffer.copyOfRange(0, totalExpected)
                notificationBuffer = notificationBuffer.copyOfRange(totalExpected, notificationBuffer.size)

                val packet = Packet.parse(fullPacketBytes)
                if (packet != null) {
                    packets.add(packet)
                } else {
                    Log.w("PacketReassembler", "Parsed null packet from buffer of size $totalExpected (Length byte: $len)")
                }
            } else {
                break
            }
        }
        return packets
    }

    /**
     * Resets the reassembly buffer state.
     */
    @Synchronized
    fun clear() {
        notificationBuffer = ByteArray(0)
    }
}
