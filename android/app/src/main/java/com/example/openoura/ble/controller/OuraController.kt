package com.example.openoura.ble.controller

import android.util.Log
import com.example.openoura.ble.OuraDeviceMetadata
import com.example.openoura.ble.OuraGATT
import com.example.openoura.ble.Req
import com.example.openoura.ble.transport.BleTransportEngine

/**
 * Exposes type-safe APIs to write commands to the Oura Ring wearable
 * and query device metadata (battery, firmware, etc.), decoupling callers from raw GATT writes.
 */
class OuraController {
    private companion object {
        private const val TAG = "OuraController"
    }

    /**
     * Reads battery status and firmware version metadata from the Oura Ring.
     */
    suspend fun readDeviceMetadata(transport: BleTransportEngine): OuraDeviceMetadata {
        Log.i(TAG, "Reading device metadata (battery and firmware)")

        // Read battery (tag 0x0d)
        transport.writeRaw(Req.battery)
        val batPkt = transport.waitForPacket { it.tag == 0x0d.toByte() }
        var batteryPct = 50
        var isCharging = false
        if (batPkt != null && batPkt.payload.size >= 3) {
            batteryPct = batPkt.payload[0].toInt() and 0xff
            isCharging = batPkt.payload[1] > 0
            Log.d(TAG, "Battery level: $batteryPct%, Charging: $isCharging")
        } else {
            Log.w(TAG, "Failed to read battery info or packet was malformed")
        }

        // Read firmware (tag 0x09)
        transport.writeRaw(Req.firmware)
        val fwPkt = transport.waitForPacket { it.tag == 0x09.toByte() }
        var firmware = "Unknown"
        if (fwPkt != null && fwPkt.payload.size >= 6) {
            firmware = "${fwPkt.payload[3]}.${fwPkt.payload[4]}.${fwPkt.payload[5]}"
            Log.d(TAG, "Firmware version: $firmware")
        } else {
            Log.w(TAG, "Failed to read firmware info or packet was malformed")
        }

        return OuraDeviceMetadata(
            firmware = firmware,
            batteryPercent = batteryPct,
            isCharging = isCharging
        )
    }

    /**
     * Requests the ring to enable daytime heart rate notifications and live tracking.
     */
    suspend fun setupLiveHr(transport: BleTransportEngine): Boolean {
        return try {
            Log.i(TAG, "Setting up Live HR stream notifications...")
            transport.writeRaw(Req.setNotification(0x3f.toByte()))
            transport.writeRaw(Req.setFeatureMode(OuraGATT.FEATURE_DAYTIME_HR, OuraGATT.FEATURE_MODE_CONNECTED_LIVE))
            Log.d(TAG, "Successfully sent Live HR setup requests.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure Live HR stream: ${e.message}", e)
            false
        }
    }

    /**
     * Instructs the ring to go into Flight Mode.
     */
    suspend fun triggerFlightMode(transport: BleTransportEngine): Boolean {
        return try {
            Log.i(TAG, "Sending Flight Mode command (0x26)...")
            transport.writeRaw(Req.setNotification(0x26.toByte()))
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to trigger Flight Mode: ${e.message}", e)
            false
        }
    }

    /**
     * Instructs the ring to execute a Factory Reset.
     */
    suspend fun triggerFactoryReset(transport: BleTransportEngine): Boolean {
        return try {
            Log.i(TAG, "Sending Factory Reset command...")
            transport.writeRaw(Req.factoryReset)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to trigger Factory Reset: ${e.message}", e)
            false
        }
    }
}
