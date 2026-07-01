package com.example.openoura

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.openoura.ffi.OuraFfiBridge
import com.example.openoura.health.HealthConnectManager
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/**
 * End-to-end integration test verifying that Rust-decoded Oura events map correctly to Google Health Connect records.
 */
@RunWith(AndroidJUnit4::class)
class OuraIntegrationTest {

    @Test
    fun testEndToEndRustToHealthConnectMapping() {
        // 1. Raw binary payloads from Oura ring (daytime HR tag 0x80)
        // Deciseconds: 5000 (0x88 0x13 0x00 0x00)
        // Values: [60 bpm, 65 bpm]
        val rawTimeSyncPayload = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val rawHrPayload = byteArrayOf(
            0x88.toByte(), 0x13.toByte(), 0x00, 0x00, // Deciseconds timestamp (5000)
            60.toByte(), 65.toByte()                  // HR values
        )

        // 2. Decode using Rust CDYLIB FFI Bridge
        val timeSyncJsonStr = OuraFfiBridge.decodeEvent(0x42, rawTimeSyncPayload)
        val hrJsonStr = OuraFfiBridge.decodeEvent(0x80.toByte(), rawHrPayload)

        assertNotNull(timeSyncJsonStr)
        assertNotNull(hrJsonStr)

        // 3. Construct the database-like JSON strings cache
        val event1 = JSONObject().apply {
            put("tag", 0x42)
            put("timestamp", 67305985L) // time sync timestamp
            put("decoded", JSONObject(timeSyncJsonStr))
        }.toString()

        val event2 = JSONObject().apply {
            put("tag", 0x80)
            put("timestamp", 5000L) // deciseconds timestamp
            put("decoded", JSONObject(hrJsonStr))
        }.toString()

        // 4. Map to Google Health Connect records using the anchor timestamp math
        val anchorTime = Instant.parse("2026-06-30T12:00:00Z")
        val (hrSamples, _) = HealthConnectManager.mapEventsToRecords(
            listOf(event1, event2),
            anchorTime
        )

        // Verify result matches expected count and values
        assertEquals(2, hrSamples.size)
        assertEquals(60L, hrSamples[0].beatsPerMinute)
        assertEquals(65L, hrSamples[1].beatsPerMinute)

        // The maximum timestamp is 5000 deciseconds.
        // Event2 is at 5000 deciseconds (which is maxDs), so it anchors exactly to anchorTime (12:00:00Z).
        assertEquals(anchorTime, hrSamples[0].time)
        assertEquals(anchorTime.plusMillis(1000), hrSamples[1].time)
    }
}
