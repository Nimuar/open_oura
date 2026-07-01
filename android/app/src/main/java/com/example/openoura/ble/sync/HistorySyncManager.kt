package com.example.openoura.ble.sync

import android.content.Context
import android.util.Log
import com.example.openoura.ble.OuraGATT
import com.example.openoura.ble.Packet
import com.example.openoura.ble.Req
import com.example.openoura.ble.auth.CredentialStore
import com.example.openoura.ble.transport.BleTransportEngine
import com.example.openoura.ffi.OuraFfiWrapper
import com.example.openoura.health.HealthConnectManager
import kotlinx.coroutines.Job
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter

/**
 * Orchestrates fetching historical event logs from the Oura Ring wearable,
 * decoding event bodies using the native Rust FFI library, and writing
 * biometric data to the Health Connect storage system.
 */
class HistorySyncManager(
    private val context: Context,
    private val ffi: OuraFfiWrapper,
    private val credentialStore: CredentialStore,
    private val healthConnectManager: HealthConnectManager,
    private val onProgress: (String?) -> Unit,
    private val onEventsSynced: (List<String>) -> Unit
) {
    private companion object {
        private const val TAG = "HistorySyncManager"
    }

    /**
     * Incrementally drains new history logs from the Oura Ring and uploads them.
     */
    suspend fun syncHistory(transport: BleTransportEngine) {
        onProgress("Starting history sync...")
        var cursor = credentialStore.getSyncCursor()
        Log.d(TAG, "Syncing from cursor: $cursor")

        val newEvents = mutableListOf<String>()
        var bytesLeft = 1
        var maxTs = cursor.toLong()

        // Loop until there are no bytes left to read
        while (bytesLeft > 0) {
            onProgress("Syncing events (cursor $cursor)...")

            val batch = getEventBatch(cursor, transport)
            if (batch.events.isEmpty() && batch.bytesLeft == 0) {
                break
            }

            for (p in batch.events) {
                if (p.payload.size < 4) continue
                // Parse timestamp (4 bytes LE)
                val ts = ((p.payload[0].toInt() and 0xff) or
                          ((p.payload[1].toInt() and 0xff) shl 8) or
                          ((p.payload[2].toInt() and 0xff) shl 16) or
                          ((p.payload[3].toInt() and 0xff) shl 24)).toLong() and 0xffffffffL

                if (ts > maxTs) {
                    maxTs = ts
                }

                // Decode event body
                val body = p.payload.copyOfRange(4, p.payload.size)
                val decodeResult = ffi.decodeEvent(p.tag, body)
                val decodedJson = decodeResult.getOrNull() ?: continue

                // Construct event JSON
                val nameResult = ffi.getEventName(p.tag)
                val eventObj = JSONObject().apply {
                    put("tag", p.tag.toInt() and 0xff)
                    put("timestamp", ts)
                    put("name", nameResult.getOrDefault("unknown"))
                    put("decoded", JSONObject(decodedJson))
                }
                newEvents.add(eventObj.toString())
            }

            bytesLeft = batch.bytesLeft
            val nextCursor = (maxTs + 1).toInt()
            if (nextCursor > cursor) {
                cursor = nextCursor
            } else {
                break // Prevents infinite loop if cursor is not advancing
            }
        }

        // Save new events
        if (newEvents.isNotEmpty()) {
            saveEventsToFile(newEvents)
            // Notify caller/service of new synced events
            onEventsSynced(newEvents)

            // Save updated cursor
            credentialStore.saveSyncCursor(cursor)

            // Push to Health Connect
            onProgress("Pushing to Health Connect...")
            healthConnectManager.syncEventsToHealthConnect(context, newEvents)
        }

        onProgress(null)
        Log.d(TAG, "Sync complete. Next cursor: $cursor")
    }

    private suspend fun getEventBatch(start: Int, transport: BleTransportEngine): EventBatch {
        val evs = mutableListOf<Packet>()
        var bytesLeft = 0
        val job = Job()

        val listener = { p: Packet ->
            if (p.tag == 0x11.toByte()) {
                if (p.payload.size >= 6) {
                    bytesLeft = ((p.payload[2].toInt() and 0xff) or
                                 ((p.payload[3].toInt() and 0xff) shl 8) or
                                 ((p.payload[4].toInt() and 0xff) shl 16) or
                                 ((p.payload[5].toInt() and 0xff) shl 24))
                }
                job.complete()
                true
            } else if (p.tag >= OuraGATT.HISTORY_EVENT_PREFIX) {
                evs.add(p)
                false
            } else {
                false
            }
        }

        transport.registerResponseListener(listener)
        transport.writeRaw(Req.getEvent(start, 255.toByte(), -1))

        try {
            kotlinx.coroutines.withTimeout(2000) {
                job.join()
            }
        } catch (e: Exception) {
            transport.unregisterResponseListener(listener)
        }

        return EventBatch(evs, bytesLeft)
    }

    private fun saveEventsToFile(eventsJson: List<String>) {
        try {
            val file = File(context.filesDir, "oura_history.json")
            val existingEvents = mutableListOf<String>()

            // Load existing
            if (file.exists()) {
                val content = file.readText()
                val jsonArray = JSONArray(content)
                for (i in 0 until jsonArray.length()) {
                    existingEvents.add(jsonArray.getString(i))
                }
            }

            // Append new
            existingEvents.addAll(eventsJson)

            // Truncate to last 100,000 to prevent infinite file size growth
            val targetList = if (existingEvents.size > 100000) {
                existingEvents.takeLast(100000)
            } else {
                existingEvents
            }

            val finalArray = JSONArray(targetList)
            FileWriter(file).use { writer ->
                writer.write(finalArray.toString())
            }
            Log.d(TAG, "Saved ${eventsJson.size} events. Total cached: ${targetList.size}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save events to file: ${e.message}", e)
        }
    }

    private data class EventBatch(
        val events: List<Packet>,
        val bytesLeft: Int
    )
}
