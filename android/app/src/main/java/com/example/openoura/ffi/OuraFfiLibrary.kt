package com.example.openoura.ffi

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer

/**
 * JNA Mapping for the `liboura_ffi.so` Rust FFI library.
 */
interface OuraFfiLibrary : Library {
    companion object {
        val INSTANCE: OuraFfiLibrary = Native.load("oura_ffi", OuraFfiLibrary::class.java)
    }

    /**
     * Encrypt a ring auth nonce (AES-128/ECB/PKCS7) into [out] (must hold 16 bytes).
     * [key] must be exactly 16 bytes; [nonce] is typically 15 bytes.
     * Returns 0 on success, negative on bad arguments.
     */
    fun oura_encrypt_nonce(
        key: ByteArray,
        keyLen: Long,
        nonce: ByteArray,
        nonceLen: Long,
        out: ByteArray
    ): Int

    /**
     * Decode an event body for [tag] into a JSON C string, or null if the tag has no
     * decoder or the body is malformed. The returned string pointer is owned by the caller
     * and must be released with [oura_string_free].
     */
    fun oura_decode_event(
        tag: Byte,
        body: ByteArray,
        bodyLen: Long
    ): Pointer?

    /**
     * Human-readable event name for [tag]. The returned string pointer is owned by the caller
     * and must be released with [oura_string_free].
     */
    fun oura_event_name(
        tag: Byte
    ): Pointer?

    /**
     * Release a C string previously returned by [oura_decode_event] or [oura_event_name].
     */
    fun oura_string_free(
        ptr: Pointer?
    )

    /**
     * Pop the oldest diagnostic log message from the Rust-native diagnostics queue.
     * The returned string pointer is owned by the caller and must be released with [oura_string_free].
     */
    fun oura_pop_diagnostic(): Pointer?
}
