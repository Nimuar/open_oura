package com.example.openoura.ble

import android.annotation.SuppressLint
import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

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

        val sharedPrefs = getSharedPreferences("open_oura_prefs", Context.MODE_PRIVATE)
        val savedMac = sharedPrefs.getString("ring_mac", null)

        if (savedMac != null && savedMac.equals(deviceAddress, ignoreCase = true)) {
            try {
                val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
                val securePrefs = EncryptedSharedPreferences.create(
                    "oura_secure_prefs",
                    masterKeyAlias,
                    this,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
                val keyHex = securePrefs.getString("ring_key", null)
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
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read credentials on Companion wakeup: ${e.message}", e)
            }
        }
    }

    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        Log.d(TAG, "Oura Ring went out of range.")
    }
}
