package com.example.openoura.ble

import android.annotation.SuppressLint
import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.openoura.ble.auth.CredentialStore

/**
 * Listens to OS-level CompanionDeviceManager range detection signals,
 * starting the Oura BLE sync service automatically when the associated ring is nearby.
 */
@SuppressLint("MissingPermission")
class OuraCompanionService : CompanionDeviceService() {

    companion object {
        private const val TAG = "OuraCompanionService"
    }

    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        val deviceAddress = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            associationInfo.deviceMacAddress?.toString()
        } else {
            null
        } ?: return

        Log.d(TAG, "Oura Ring detected nearby: $deviceAddress. Triggering sync...")

        val credentialStore = CredentialStore(this)
        val savedMac = credentialStore.getSavedMacAddress()

        if (savedMac != null && savedMac.equals(deviceAddress, ignoreCase = true)) {
            // Check if the service is already busy with a connection (e.g. from the UI)
            val currentState = OuraBleService.connectionState.value
            if (currentState != ConnectionState.Idle && currentState !is ConnectionState.Failed) {
                Log.d(TAG, "Skipping background sync trigger: Service is already busy ($currentState)")
                return
            }

            val keyHex = credentialStore.getSavedKeyHex()
            if (keyHex != null) {
                // Start OuraBleService to execute a target sync loop and stop
                val intent = Intent(this, OuraBleService::class.java).apply {
                    putExtra("mac_address", savedMac)
                    putExtra("auth_key_hex", keyHex)
                    putExtra("trigger_sync_and_stop", true)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            }
        }
    }

    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        Log.d(TAG, "Oura Ring went out of range.")
    }
}
