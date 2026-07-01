package com.example.openoura.ffi

/**
 * Interface defining all cryptographic and decoding operations passing through the native Rust FFI bridge.
 * Abstracting this enables local JVM unit testing (JUnit) to mock FFI behavior without UnsatisfiedLinkError.
 */
interface OuraFfiWrapper {
    /**
     * Encrypt a ring auth nonce using AES-128 ECB.
     * Returns a [Result] containing the 16-byte encrypted byte array.
     */
    fun encryptNonce(key: ByteArray, nonce: ByteArray): Result<ByteArray>

    /**
     * Decode a raw event body into a JSON string.
     * Returns a [Result] containing the JSON representation.
     */
    fun decodeEvent(tag: Byte, body: ByteArray): Result<String>

    /**
     * Retrieve the friendly human-readable name of an event tag.
     * Returns a [Result] containing the name.
     */
    fun getEventName(tag: Byte): Result<String>

    /**
     * Drain the accumulated native diagnostic log queue.
     * Returns a [Result] containing the list of log messages.
     */
    fun drainDiagnostics(): Result<List<String>>
}
