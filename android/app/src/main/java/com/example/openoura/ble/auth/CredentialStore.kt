package com.example.openoura.ble.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * Handles persistence of Oura Ring credentials (MAC address and cryptographic authentication key)
 * using standard SharedPreferences and keystore-backed EncryptedSharedPreferences.
 */
class CredentialStore(private val context: Context) {
    private companion object {
        private const val TAG = "CredentialStore"
        private const val PREFS_NAME = "open_oura_prefs"
        private const val SECURE_PREFS_NAME = "oura_secure_prefs"
        private const val KEY_RING_MAC = "ring_mac"
        private const val KEY_RING_KEY = "ring_key"
        private const val KEY_PAIRING_VERIFIED = "pairing_verified"
        private const val KEY_SYNC_CURSOR = "sync_cursor"
    }

    private val sharedPrefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val securePrefs: SharedPreferences by lazy {
        try {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedSharedPreferences.create(
                SECURE_PREFS_NAME,
                masterKeyAlias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize EncryptedSharedPreferences: ${e.message}", e)
            context.getSharedPreferences(SECURE_PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    fun getSavedMacAddress(): String? {
        return sharedPrefs.getString(KEY_RING_MAC, null)?.uppercase()
    }

    fun getSavedKeyHex(): String? {
        return securePrefs.getString(KEY_RING_KEY, null)
    }

    fun isPairingVerified(): Boolean {
        return sharedPrefs.getBoolean(KEY_PAIRING_VERIFIED, false)
    }

    fun saveCredentials(macAddress: String, keyHex: String) {
        val sanitizedMac = macAddress.uppercase()
        Log.d(TAG, "Saving credentials for MAC: $sanitizedMac | Key Prefix: ${keyHex.take(8)}...")
        sharedPrefs.edit().putString(KEY_RING_MAC, sanitizedMac).apply()
        securePrefs.edit().putString(KEY_RING_KEY, keyHex).apply()
    }

    fun setPairingVerified(verified: Boolean) {
        sharedPrefs.edit().putBoolean(KEY_PAIRING_VERIFIED, verified).apply()
    }

    fun getSyncCursor(): Int {
        return sharedPrefs.getInt(KEY_SYNC_CURSOR, 0)
    }

    fun saveSyncCursor(cursor: Int) {
        sharedPrefs.edit().putInt(KEY_SYNC_CURSOR, cursor).apply()
    }

    fun clear() {
        Log.i(TAG, "Clearing stored credentials")
        sharedPrefs.edit().remove(KEY_RING_MAC).remove(KEY_PAIRING_VERIFIED).remove(KEY_SYNC_CURSOR).apply()
        securePrefs.edit().remove(KEY_RING_KEY).apply()
    }
}
