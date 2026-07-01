package com.example.openoura.ffi

import android.util.Log
import com.sun.jna.Pointer
import org.json.JSONArray

/**
 * Concrete JNA implementation of [OuraFfiWrapper], bridging to native `liboura_ffi.so`.
 */
object OuraFfiBridge : OuraFfiWrapper {
    private const val TAG = "OuraFfiBridge"

    override fun encryptNonce(key: ByteArray, nonce: ByteArray): Result<ByteArray> {
        if (key.size != 16) {
            return Result.failure(IllegalArgumentException("Key must be exactly 16 bytes (got ${key.size})"))
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
                Result.failure(RuntimeException("oura_encrypt_nonce failed with error code: $result"))
            } else {
                Result.success(out)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during nonce encryption: ${e.message}", e)
            Result.failure(e)
        }
    }

    override fun decodeEvent(tag: Byte, body: ByteArray): Result<String> {
        var ptr: Pointer? = null
        return try {
            ptr = OuraFfiLibrary.INSTANCE.oura_decode_event(tag, body, body.size.toLong())
            val decoded = ptr?.getString(0, "UTF-8")
            if (decoded != null) {
                Result.success(decoded)
            } else {
                Result.failure(RuntimeException("oura_decode_event returned null for tag 0x${"%02x".format(tag)}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during event decoding for tag $tag: ${e.message}", e)
            Result.failure(e)
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

    override fun getEventName(tag: Byte): Result<String> {
        var ptr: Pointer? = null
        return try {
            ptr = OuraFfiLibrary.INSTANCE.oura_event_name(tag)
            val name = ptr?.getString(0, "UTF-8")
            if (name != null) {
                Result.success(name)
            } else {
                Result.success("unknown")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during event name fetch for tag $tag: ${e.message}", e)
            Result.failure(e)
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

    override fun drainDiagnostics(): Result<List<String>> {
        var ptr: Pointer? = null
        return try {
            ptr = OuraFfiLibrary.INSTANCE.oura_drain_diagnostics()
            val jsonStr = ptr?.getString(0, "UTF-8")
            if (jsonStr != null) {
                val array = JSONArray(jsonStr)
                val list = mutableListOf<String>()
                for (i in 0 until array.length()) {
                    list.add(array.getString(i))
                }
                Result.success(list)
            } else {
                Result.success(emptyList())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during drainDiagnostics: ${e.message}", e)
            Result.failure(e)
        } finally {
            ptr?.let {
                try {
                    OuraFfiLibrary.INSTANCE.oura_string_free(it)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to release native diagnostics pointer: ${e.message}", e)
                }
            }
        }
    }
}
