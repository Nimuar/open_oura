package com.example.openoura.ble.sync

import java.time.Instant

/**
 * Data structure representing a local epoch mapping anchor point on the Oura Ring.
 */
data class TimeSyncAnchor(val deciseconds: Long, val unixTimeSeconds: Long)

/**
 * Implements NTP-style clock drift calculations, mapping decisecond tick sequences
 * from the wearable to absolute phone UTC timestamps relative to nearest preceding time-sync benchmarks.
 */
class DriftCalibrator {
    /**
     * Computes the absolute [Instant] of an event by calibrating its relative deciseconds
     * against the nearest preceding [TimeSyncAnchor]. If no preceding anchor is available,
     * falls back to the sync completion baseline.
     */
    fun calculateInstant(
        eventDeciseconds: Long,
        maxDeciseconds: Long,
        timeSyncAnchors: List<TimeSyncAnchor>,
        fallbackCompletionTime: Instant
    ): Instant {
        val anchor = timeSyncAnchors.filter { it.deciseconds <= eventDeciseconds }
            .maxByOrNull { it.deciseconds }

        return if (anchor != null) {
            val baseTimeMillis = anchor.unixTimeSeconds * 1000L
            val offsetMillis = (eventDeciseconds - anchor.deciseconds) * 100L
            Instant.ofEpochMilli(baseTimeMillis + offsetMillis)
        } else {
            val timeOffsetMs = (maxDeciseconds - eventDeciseconds) * 100L
            fallbackCompletionTime.minusMillis(timeOffsetMs)
        }
    }
}
