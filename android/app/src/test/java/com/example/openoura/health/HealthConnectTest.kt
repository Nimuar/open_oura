package com.example.openoura.health

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/**
 * Local JVM unit test to verify Oura event parsing, time-anchoring math, and Health Connect record mapping.
 */
class HealthConnectTest {

    @Test
    fun testMapEventsToRecords() {
        // Mock TimeSync (0x42) JSON
        val timeSyncEventJson = """
            {
                "tag": 66,
                "timestamp": 500,
                "decoded": {
                    "unix_time": 1782043200
                }
            }
        """.trimIndent()

        // Mock Daytime HR (0x80) JSON with hr_bpm
        val hrEventJson = """
            {
                "tag": 128,
                "timestamp": 1000,
                "decoded": {
                    "hr_bpm": [60, 65, 70]
                }
            }
        """.trimIndent()

        // Mock HRV (0x5d) JSON with rmssd_ms and hr_bpm
        val hrvEventJson = """
            {
                "tag": 93,
                "timestamp": 2000,
                "decoded": {
                    "rmssd_ms": [40.0, 45.0],
                    "hr_bpm": [55, 58]
                }
            }
        """.trimIndent()

        val anchorTime = Instant.parse("2026-06-30T12:00:00Z")
        val (hrSamples, hrvRecords) = HealthConnectManager.mapEventsToRecords(
            listOf(timeSyncEventJson, hrEventJson, hrvEventJson),
            anchorTime
        )

        // HR Samples: 3 from hrEventJson (tag 128) + 2 from hrvEventJson (tag 93) = 5 total
        assertEquals(5, hrSamples.size)

        // Verify calibrated timestamps and values
        // TimeSync anchor is at timestamp 500, unix_time = 1782043200 (2026-06-20T12:00:00Z)
        // HR event is at deciseconds 1000.
        // Offset: (1000 - 500) * 100 ms = 50 seconds.
        // Event time for HR event = 12:00:00Z + 50 seconds = 12:00:50Z.
        val expectedHrTime = Instant.ofEpochSecond(1782043200).plusSeconds(50)
        assertEquals(expectedHrTime, hrSamples[0].time)
        assertEquals(60L, hrSamples[0].beatsPerMinute)
        assertEquals(65L, hrSamples[1].beatsPerMinute)

        // HRV Records: 2 from hrvEventJson
        assertEquals(2, hrvRecords.size)
        // HRV event is at deciseconds 2000.
        // Offset: (2000 - 500) * 100 ms = 150 seconds.
        // Event time for HRV event = 12:00:00Z + 150 seconds = 12:02:30Z.
        val expectedHrvTime = Instant.ofEpochSecond(1782043200).plusSeconds(150)
        assertEquals(expectedHrvTime, hrvRecords[0].time)
        assertEquals(40.0, hrvRecords[0].heartRateVariabilityMillis, 0.001)

        // The second HRV sample is shifted by 5 minutes (300 seconds)
        val expectedSecondHrvTime = expectedHrvTime.plusSeconds(300)
        assertEquals(expectedSecondHrvTime, hrvRecords[1].time)
        assertEquals(45.0, hrvRecords[1].heartRateVariabilityMillis, 0.001)
    }
}
