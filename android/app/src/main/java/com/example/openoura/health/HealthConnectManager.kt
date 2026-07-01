package com.example.openoura.health

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import com.example.openoura.ble.OuraBleService
import com.example.openoura.ffi.OuraFfiBridge
import org.json.JSONObject
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Manages syncing biometric events to Google Health Connect.
 */
object HealthConnectManager {
    private const val TAG = "HealthConnectManager"

    // Set of permissions required to read and write Heart Rate and HRV
    val REQUIRED_PERMISSIONS = setOf(
        androidx.health.connect.client.permission.HealthPermission.getWritePermission(HeartRateRecord::class),
        androidx.health.connect.client.permission.HealthPermission.getReadPermission(HeartRateRecord::class),
        androidx.health.connect.client.permission.HealthPermission.getWritePermission(HeartRateVariabilityRmssdRecord::class),
        androidx.health.connect.client.permission.HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class)
    )

    /**
     * Checks if all write permissions are granted.
     */
    suspend fun hasAllPermissions(context: Context): Boolean {
        return try {
            val client = HealthConnectClient.getOrCreate(context)
            val granted = client.permissionController.getGrantedPermissions()
            granted.containsAll(REQUIRED_PERMISSIONS)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking Health Connect permissions: ${e.message}")
            false
        }
    }

    /**
     * Parse raw event JSONs, resolve timestamps, and write Heart Rate & HRV samples to Health Connect.
     * Uses the iOS/Rust anchoring math:
     *   event_time = sync_completed_time - (max_ring_deciseconds - event_deciseconds) / 10
     */
    suspend fun syncEventsToHealthConnect(context: Context, eventsJson: List<String>) {
        if (!hasAllPermissions(context)) {
            Log.w(TAG, "Missing Health Connect write permissions. Sync skipped.")
            return
        }

        val client = HealthConnectClient.getOrCreate(context)
        val anchorTime = Instant.now()
        val (hrSamples, hrvRecords) = mapEventsToRecords(eventsJson, anchorTime)

        // Write Heart Rate Records in chunks (Health Connect has batch limit recommendations)
        if (hrSamples.isNotEmpty()) {
            val sortedSamples = hrSamples.sortedBy { it.time }
            val startTime = sortedSamples.first().time
            val endTime = sortedSamples.last().time

            val hrRecord = HeartRateRecord(
                startTime = startTime,
                startZoneOffset = null,
                endTime = endTime,
                endZoneOffset = null,
                samples = sortedSamples
            )

            try {
                client.insertRecords(listOf(hrRecord))
                Log.d(TAG, "Successfully synced ${hrSamples.size} Heart Rate samples to Health Connect")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to insert Heart Rate records: ${e.message}", e)
            }
        }

        // Write HRV records
        if (hrvRecords.isNotEmpty()) {
            try {
                client.insertRecords(hrvRecords)
                Log.d(TAG, "Successfully synced ${hrvRecords.size} HRV records to Health Connect")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to insert HRV records: ${e.message}", e)
            }
        }
    }

    /**
     * Map events JSON to Health Connect records, isolated for unit testing.
     * Uses NTP-style drift calibration by aligning sample timestamps relative to their
     * nearest preceding `time_sync` (tag 0x42) anchor event, converting to millisecond offsets
     * to avoid precision loss or integer overflow.
     */
    fun mapEventsToRecords(
        eventsJson: List<String>,
        anchorTime: Instant
    ): Pair<List<HeartRateRecord.Sample>, List<HeartRateVariabilityRmssdRecord>> {
        var maxDs: Long = 0
        val parsedEvents = mutableListOf<ParsedOuraEvent>()
        val timeSyncAnchors = mutableListOf<TimeSyncAnchor>()

        for (jsonStr in eventsJson) {
            try {
                val json = JSONObject(jsonStr)
                val tag = json.getInt("tag").toByte()
                val ds = json.getLong("timestamp")
                val decodedJson = json.optJSONObject("decoded") ?: continue
                val bodyStr = decodedJson.toString()

                parsedEvents.add(ParsedOuraEvent(tag, ds, bodyStr))
                if (ds > maxDs) {
                    maxDs = ds
                }

                // If this is a time_sync event, record it as a drift calibration anchor
                if (tag == 0x42.toByte()) {
                    val unixTime = decodedJson.optLong("unix_time", -1L)
                    if (unixTime > 0) {
                        timeSyncAnchors.add(TimeSyncAnchor(ds, unixTime))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse event JSON: ${e.message}")
            }
        }

        if (parsedEvents.isEmpty()) {
            return Pair(emptyList(), emptyList())
        }

        // Sort anchors by decisecond ticks to enable binary search or linear scans
        timeSyncAnchors.sortBy { it.deciseconds }

        val hrSamples = mutableListOf<HeartRateRecord.Sample>()
        val hrvRecords = mutableListOf<HeartRateVariabilityRmssdRecord>()

        for (event in parsedEvents) {
            // Find the closest preceding time_sync anchor
            val anchor = timeSyncAnchors.filter { it.deciseconds <= event.deciseconds }
                .maxByOrNull { it.deciseconds }

            val eventTime: Instant = if (anchor != null) {
                // Precise calibration math (Option 1: Milliseconds-first to avoid overflow/truncation)
                val baseTimeMillis = anchor.unixTimeSeconds * 1000L
                val offsetMillis = (event.deciseconds - anchor.deciseconds) * 100L
                Instant.ofEpochMilli(baseTimeMillis + offsetMillis)
            } else {
                // Fallback to sync-completion anchor if no preceding time_sync is available
                val timeOffsetMs = (maxDs - event.deciseconds) * 100L
                anchorTime.minusMillis(timeOffsetMs)
            }

            try {
                val bodyJson = JSONObject(event.decodedBody)

                when (event.tag) {
                    0x80.toByte(), 0x60.toByte() -> { // green_ibi_quality / ibi_and_amplitude (Heart Rate)
                        val hrArray = bodyJson.optJSONArray("hr_bpm") ?: continue
                        for (i in 0 until hrArray.length()) {
                            val bpm = hrArray.getInt(i)
                            if (bpm in 31..239) {
                                val sampleTime = eventTime.plusSeconds(i.toLong())
                                hrSamples.add(
                                    HeartRateRecord.Sample(
                                        time = sampleTime,
                                        beatsPerMinute = bpm.toLong()
                                    )
                                )
                            }
                        }
                    }
                    0x5d.toByte() -> { // hrv_event: averages hr_bpm + rmssd_ms (per 5 min)
                        // HRV RMSSD
                        val rmssdArray = bodyJson.optJSONArray("rmssd_ms") ?: continue
                        for (i in 0 until rmssdArray.length()) {
                            val rmssd = rmssdArray.getDouble(i)
                            if (rmssd > 0.0) {
                                val recordTime = eventTime.plus(i * 5L, ChronoUnit.MINUTES)
                                hrvRecords.add(
                                    HeartRateVariabilityRmssdRecord(
                                        time = recordTime,
                                        zoneOffset = null,
                                        heartRateVariabilityMillis = rmssd
                                    )
                                )
                            }
                        }

                        // Also extract secondary heart rate averages
                        val hrArray = bodyJson.optJSONArray("hr_bpm") ?: continue
                        for (i in 0 until hrArray.length()) {
                            val bpm = hrArray.getInt(i)
                            if (bpm in 31..239) {
                                val recordTime = eventTime.plus(i * 5L, ChronoUnit.MINUTES)
                                hrSamples.add(
                                    HeartRateRecord.Sample(
                                        time = recordTime,
                                        beatsPerMinute = bpm.toLong()
                                    )
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error mapping event data: ${e.message}")
            }
        }

        return Pair(hrSamples, hrvRecords)
    }

    private data class TimeSyncAnchor(
        val deciseconds: Long,
        val unixTimeSeconds: Long
    )

    private data class ParsedOuraEvent(
        val tag: Byte,
        val deciseconds: Long,
        val decodedBody: String
    )
}
