package com.example.openoura.ffi

import android.util.Log
import com.sun.jna.Pointer

/**
 * Idiomatic, thread-safe, and memory-safe Kotlin wrapper around the native Oura FFI library.
 * It enforces strict deallocation of native heap memory.
 */
object OuraFfiBridge {
    private const val TAG = "OuraFfiBridge"

    /**
     * Encrypt a ring auth nonce using AES-128 ECB.
     * Returns a 16-byte encrypted byte array, or null if an error occurs.
     */
    fun encryptNonce(key: ByteArray, nonce: ByteArray): ByteArray? {
        if (key.size != 16) {
            Log.e(TAG, "Key must be exactly 16 bytes (got ${key.size})")
            return null
        }
        val out = ByteArray(16)
        return try {
            val result = OuraFfiLibrary.INSTANCE.oura_encrypt_nonce(
                key,
                key.size.toLong(),
                nonce,
                nonce.size.toLong(),
                out
            )
            if (result != 0) {
                Log.e(TAG, "oura_encrypt_nonce failed with error code: $result")
                null
            } else {
                out
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during nonce encryption: ${e.message}", e)
            null
        }
    }

    /**
     * Decode a raw event body.
     * Returns a JSON string, or null if decoding fails or is unsupported.
     * Guarantees native string deallocation.
     */
    fun decodeEvent(tag: Byte, body: ByteArray): String? {
        var ptr: Pointer? = null
        return try {
            ptr = OuraFfiLibrary.INSTANCE.oura_decode_event(tag, body, body.size.toLong())
            ptr?.getString(0, "UTF-8")
        } catch (e: Exception) {
            Log.e(TAG, "Exception during event decoding for tag $tag: ${e.message}", e)
            null
        } finally {
            ptr?.let {
                try {
                    OuraFfiLibrary.INSTANCE.oura_string_free(it)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to release native string pointer: ${e.message}", e)
                }
            }
        }
    }

    /**
     * Get the human-readable name of an event tag.
     * Returns a string (defaults to "unknown").
     * Guarantees native string deallocation.
     */
    fun getEventName(tag: Byte): String {
        var ptr: Pointer? = null
        return try {
            ptr = OuraFfiLibrary.INSTANCE.oura_event_name(tag)
            ptr?.getString(0, "UTF-8") ?: "unknown"
        } catch (e: Exception) {
            Log.e(TAG, "Exception during event name fetch for tag $tag: ${e.message}", e)
            "unknown"
        } finally {
            ptr?.let {
                try {
                    OuraFfiLibrary.INSTANCE.oura_string_free(it)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to release native event name pointer: ${e.message}", e)
                }
            }
        }
    }

    /**
     * Pop the oldest diagnostic log message from the Rust FFI logs queue.
     * Returns a string, or null if the queue is empty.
     * Guarantees native string deallocation.
     */
    fun popDiagnostic(): String? {
        var ptr: Pointer? = null
        return try {
            ptr = OuraFfiLibrary.INSTANCE.oura_pop_diagnostic()
            ptr?.getString(0, "UTF-8")
        } catch (e: Exception) {
            Log.e(TAG, "Exception during popDiagnostic: ${e.message}", e)
            null
        } finally {
            ptr?.let {
                try {
                    OuraFfiLibrary.INSTANCE.oura_string_free(it)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to release native diagnostic pointer: ${e.message}", e)
                }
            }
        }
    }
}
