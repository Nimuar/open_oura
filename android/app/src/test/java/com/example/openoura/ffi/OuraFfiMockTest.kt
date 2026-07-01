package com.example.openoura.ffi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies that the OuraFfiWrapper interface can be successfully mocked
 * on a local JVM without raising UnsatisfiedLinkError.
 */
class OuraFfiMockTest {

    @Test
    fun testMockedWrapper() {
        val mockFfi = object : OuraFfiWrapper {
            override fun encryptNonce(key: ByteArray, nonce: ByteArray): Result<ByteArray> {
                return Result.success(byteArrayOf(1, 2, 3))
            }

            override fun decodeEvent(tag: Byte, body: ByteArray): Result<String> {
                return Result.success("{\"mock\":true}")
            }

            override fun getEventName(tag: Byte): Result<String> {
                return Result.success("mocked_event")
            }

            override fun drainDiagnostics(): Result<List<String>> {
                return Result.success(listOf("mock_log1", "mock_log2"))
            }
        }

        // Test Event Name Query
        val nameResult = mockFfi.getEventName(0x42.toByte())
        assertEquals("mocked_event", nameResult.getOrNull())

        // Test Encrypt Nonce
        val encryptResult = mockFfi.encryptNonce(byteArrayOf(), byteArrayOf())
        assertTrue(encryptResult.getOrNull()!!.contentEquals(byteArrayOf(1, 2, 3)))

        // Test Event Decoder
        val decodeResult = mockFfi.decodeEvent(0, byteArrayOf())
        assertEquals("{\"mock\":true}", decodeResult.getOrNull())

        // Test Bulk Diagnostics Draining
        val logsResult = mockFfi.drainDiagnostics()
        val logs = logsResult.getOrNull()
        assertEquals(2, logs?.size)
        assertEquals("mock_log1", logs?.get(0))
        assertEquals("mock_log2", logs?.get(1))
    }
}
