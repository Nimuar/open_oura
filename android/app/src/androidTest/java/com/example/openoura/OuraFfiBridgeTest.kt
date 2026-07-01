package com.example.openoura

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.openoura.ffi.OuraFfiBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device Android instrumentation test to verify the JNA FFI bridge with liboura_ffi.so.
 */
@RunWith(AndroidJUnit4::class)
class OuraFfiBridgeTest {

    @Test
    fun testGetEventName() {
        val name = OuraFfiBridge.getEventName(0x42)
        assertEquals("time_sync", name)

        val name80 = OuraFfiBridge.getEventName(0x80.toByte())
        assertEquals("green_ibi_quality_event", name80)
    }

    @Test
    fun testDecodeTimeSyncEvent() {
        val body = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val json = OuraFfiBridge.decodeEvent(0x42, body)
        assertNotNull(json)
        assertEquals("{\"unix_time\":67305985}", json)
    }

    @Test
    fun testEncryptNonce() {
        val key = ByteArray(16) { 0 }
        val nonce = ByteArray(15) { 1 }
        val encrypted = OuraFfiBridge.encryptNonce(key, nonce)
        assertNotNull(encrypted)
        assertEquals(16, encrypted!!.size)
    }

    @Test
    fun testDiagnosticsLogs() {
        // Clear diagnostics queue
        while (OuraFfiBridge.popDiagnostic() != null) {}

        // Calling an FFI function should generate logs
        OuraFfiBridge.getEventName(0x42)

        val log = OuraFfiBridge.popDiagnostic()
        assertNotNull(log)
        // Check that the log message mentions FFI event name fetch
        assert(log!!.contains("FFI: oura_event_name"))
    }
}
