package com.example.openoura.ble.auth

import android.util.Log
import com.example.openoura.ble.ConnectionState
import com.example.openoura.ble.Req
import com.example.openoura.ble.transport.BleTransportEngine
import com.example.openoura.ffi.OuraFfiWrapper

/**
 * Handles Oura Ring BLE authentication protocols:
 * 1. Installing a pairing key on factory-reset rings.
 * 2. Performing standard AES-128/ECB nonce challenge-responses.
 */
class OuraAuthenticator(
    private val ffi: OuraFfiWrapper,
    private val onProgress: (String?) -> Unit,
    private val onStateChange: (ConnectionState, String) -> Unit
) {
    private companion object {
        private const val TAG = "OuraAuthenticator"
    }

    /**
     * Executes the initial pairing key registration sequence with a factory-reset ring.
     */
    suspend fun runPairingHandshake(
        key: ByteArray,
        transport: BleTransportEngine
    ): Boolean {
        val keySnippet = key.take(4).joinToString("") { "%02x".format(it) }
        Log.i(TAG, "Starting pairing handshake (Set Auth Key). Key Prefix: $keySnippet...")
        onStateChange(ConnectionState.Authenticating, "Descriptor write success, starting pairing")
        onProgress("Installing auth key...")

        val pairPacket = Req.setAuthKey(key)
        transport.writeRaw(pairPacket)

        val response = transport.waitForPacket { it.tag == 0x25.toByte() }
        if (response != null && response.payload.isNotEmpty() && response.payload[0] == 0x00.toByte()) {
            Log.d(TAG, "Auth key installed successfully on the ring.")
            return true
        } else {
            val errCode = if (response != null && response.payload.isNotEmpty()) "0x${"%02x".format(response.payload[0])}" else "timeout"
            Log.e(TAG, "Pairing rejected by the ring: $errCode")
            onStateChange(ConnectionState.Failed("Pairing rejected ($errCode)"), "Ring rejected 0x25 pairing packet")
            return false
        }
    }

    /**
     * Authenticates the current connection session using the saved cryptographic key.
     */
    suspend fun runAuthentication(
        key: ByteArray,
        transport: BleTransportEngine
    ): Boolean {
        val keySnippet = key.take(4).joinToString("") { "%02x".format(it) }
        Log.i(TAG, "Starting standard challenge-response authentication. Key Prefix: $keySnippet...")
        onStateChange(ConnectionState.Authenticating, "Starting auth nonce request")
        onProgress("Requesting auth nonce...")

        transport.writeRaw(Req.authNonce)

        // Nonce is 0x2f type 0x01. Extended tag is 0x2c.
        val noncePkt = transport.waitForPacket { it.tag == 0x2f.toByte() && it.payload.isNotEmpty() && it.payload[0] == 0x2c.toByte() }
        if (noncePkt == null || noncePkt.payload.size <= 1) {
            onStateChange(ConnectionState.Failed("Auth failed (no nonce)"), "Timed out or empty nonce packet")
            return false
        }

        // Extracted nonce is payload excluding the extended tag 0x2c
        val nonce = noncePkt.payload.copyOfRange(1, noncePkt.payload.size)
        Log.d(TAG, "FFI: Encrypting nonce. Key size: ${key.size}, Nonce size: ${nonce.size}")

        val encryptResult = ffi.encryptNonce(key, nonce)
        val encrypted = encryptResult.getOrNull()
        if (encrypted == null) {
            val errMsg = encryptResult.exceptionOrNull()?.message ?: "Rust FFI encryption failed"
            Log.e(TAG, "FFI encryption error: $errMsg")
            onStateChange(ConnectionState.Failed("Crypto error"), errMsg)
            return false
        }

        onProgress("Verifying auth key...")
        transport.writeRaw(Req.authenticate(encrypted))

        val authResp = transport.waitForPacket { it.tag == 0x2f.toByte() && it.payload.size > 1 && it.payload[0] == 0x2e.toByte() }
        if (authResp != null && authResp.payload.size > 1 && authResp.payload[1] == 0x00.toByte()) {
            Log.d(TAG, "Challenge verification successful!")
            return true
        } else {
            val errCode = if (authResp != null && authResp.payload.size > 1) "0x${"%02x".format(authResp.payload[1])}" else "Unknown"
            Log.e(TAG, "Challenge verification rejected: $errCode")
            onStateChange(ConnectionState.Failed("Wrong auth key ($errCode)"), "Ring rejected encrypted nonce")
            return false
        }
    }
}
