# Specification: open_oura Android Bridge & Headless Infrastructure

## Overview
This specification details the design and requirements for the local-first, headless sync infrastructure connecting a physical Google Pixel 10 Pro to an Oura Ring (Gen 3/4/5). It enables subscription-free, cloud-free data collection, decoding, and state propagation using a stateless Rust FFI layer bridged to Kotlin via Java Native Access (JNA).

## User Journeys
- **As a Developer/System Architect**, I want to cross-compile the Rust FFI crate for Android (`arm64-v8a`) via a single headless script, so that the compiled `liboura_ffi.so` is placed automatically in the correct Android directory.
- **As a Developer/System Architect**, I want a Kotlin JNA wrapper interface mapping `ouraffi.h` that encapsulates memory safety (i.e. auto-calling `oura_string_free` in a `finally` block for all JSON decodes) to prevent native heap leaks.
- **As an Android Application Layer**, I want a BLE ingestion stub that parses incoming ring packet structures (`tag | len | payload`), decrypts/decodes them through the JNA layer, and surfaces them to Jetpack Compose UI state without blocking the main thread.
- **As an Oura Ring User**, I want my heart rate metrics collected opportunistically from the ring's local buffer using the "Live" HR mechanism (`0x80` green IBI quality events) without relying on any Oura servers or subscription checks.

## Functional Requirements

### 1. Rust FFI Layer (`oura-ffi`)
- **F-1.1**: The Rust crate `oura-ffi` must expose:
  - `oura_encrypt_nonce(key: *const u8, nonce: *const u8, out: *mut u8)`
  - `oura_decode_event(tag: u8, payload_ptr: *const u8, payload_len: usize) -> *mut c_char`
  - `oura_event_name(tag: u8) -> *const c_char`
  - `oura_string_free(ptr: *mut c_char)`
- **F-1.2**: Native C-ABI strings returned from `oura_decode_event` and `oura_event_name` must be null-terminated and dynamically allocated on the Rust heap (using `CString`).
- **F-1.3**: `oura_string_free` must safely reconstruct the `CString` and drop it, freeing the memory.

### 2. Automated Cross-Compilation
- **F-2.1**: A bash shell script (or PowerShell script) `build_android.sh` / `build_android.ps1` must be created.
- **F-2.2**: The script must invoke `cargo ndk --target arm64-v8a --android-platform 36 build --release` (or appropriate API level) for `oura-ffi`.
- **F-2.3**: The script must automatically create the target directory `android/app/src/main/jniLibs/arm64-v8a/` if it does not exist and copy `liboura_ffi.so` there.

### 3. JNA Kotlin Layer
- **F-3.1**: Define a JNA interface `OuraFfiLibrary` mapped to `liboura_ffi.so`.
- **F-3.2**: Implement a wrapper class `OuraFfiBridge` that exposes:
  - `fun decodeEvent(tag: Byte, payload: ByteArray): String?`
  - `fun getEventName(tag: Byte): String`
  - `fun encryptNonce(key: ByteArray, nonce: ByteArray): ByteArray`
- **F-3.3**: The `decodeEvent` function must copy the returned C-string pointer into a Kotlin `String`, and invoke `oura_string_free` in a `finally` block to prevent leaks.

### 4. Android BLE Ingestion Frame Stub
- **F-4.1**: A Kotlin class `OuraBleService` extending `LifecycleService` or utilizing `BluetoothGattCallback` to parse incoming packet buffers.
- **F-4.2**: Packet parsing logic must read incoming BLE notification bytes structured as `[tag, len, ...payload]`.
- **F-4.3**: Parse and reconstruct fragmented packets if BLE MTU is smaller than packet length.
- **F-4.4**: Push decoded JSON data into a Jetpack Compose-friendly `StateFlow<List<String>>` or `MutableStateFlow` representing decoded event history.
- **F-4.5**: Implement the "Live" HR flow: write `SetNotification(0x3f)` and `SetFeatureMode(DAYTIME_HR, CONNECTED_LIVE)` to force the ring to record daytime HR history events (`0x80`).
- **F-4.6**: Implement connection state-gating inside `OuraBleService` to reject overlapping connection or pairing requests when a GATT session is already in progress.
- **F-4.7**: Implement safety-gating in `OuraCompanionService` to skip background sync invocations if the service is already busy with an active foreground or background connection.
- **F-4.8**: Expose audit logs showing MAC address normalizations and the first 4 bytes of cryptographic keys upon connection startup to troubleshoot storage desync.

## Success Criteria
1. The compilation script successfully builds `liboura_ffi.so` and places it in the JNI directory.
2. The Kotlin FFI unit test or mock test runs successfully without leaking memory.
3. The BLE service stub compiles and provides clean callbacks for Compose UI.
4. No network or telemetry boilerplate exists in the code.

## Out of Scope
- Full UI screens or visual dashboards (only the Compose state integration points and service stubs are required).
- Persistence/SQLite storage configuration on Android (handled separately).
- Non-arm64-v8a targets (e.g. x86_64 or armv7).
