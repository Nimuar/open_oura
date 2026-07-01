package com.example.openoura.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Local JVM unit test to verify OuraGATT packet parsing and Req builders.
 */
class OuraGattTest {

    @Test
    fun testPacketParseValid() {
        val rawFrame = byteArrayOf(0x42, 0x04, 0x01, 0x02, 0x03, 0x04)
        val packet = Packet.parse(rawFrame)
        assertNotNull(packet)
        assertEquals(0x42.toByte(), packet!!.tag)
        assertArrayEquals(byteArrayOf(0x01, 0x02, 0x03, 0x04), packet.payload)
        assertNull(packet.extTag)
    }

    @Test
    fun testPacketParseExtended() {
        val rawFrame = byteArrayOf(0x2f, 0x02, 0x2b, 0x01)
        val packet = Packet.parse(rawFrame)
        assertNotNull(packet)
        assertEquals(0x2f.toByte(), packet!!.tag)
        assertArrayEquals(byteArrayOf(0x2b, 0x01), packet.payload)
        assertEquals(0x2b.toByte(), packet.extTag)
    }

    @Test
    fun testPacketParseInvalid() {
        val shortFrame = byteArrayOf(0x01)
        val packet = Packet.parse(shortFrame)
        assertNull(packet)
    }

    @Test
    fun testPacketParseTruncated() {
        val truncatedFrame = byteArrayOf(0x42, 0x05, 0x01, 0x02)
        val packet = Packet.parse(truncatedFrame)
        assertNull(packet)
    }

    @Test
    fun testReqBuilders() {
        val payload = byteArrayOf(0xaa.toByte(), 0xbb.toByte())
        val pkt = Req.packet(0x10.toByte(), payload)
        assertArrayEquals(byteArrayOf(0x10, 0x02, 0xaa.toByte(), 0xbb.toByte()), pkt)
    }
}
