# Specification: open_oura Android Application

This document specifies the requirements, functional components, and success criteria for the local-first, headless Oura Ring sync app on Android.

---

## 1. JNA Kotlin Layer
*   **F-1.1**: Define a JNA interface `OuraFfiLibrary` mapped to `liboura_ffi.so`.
*   **F-1.2**: Define an interface `OuraFfiWrapper` encapsulating FFI interactions. Implement `OuraFfiBridge` as the concrete JNA implementation of this interface. ViewModels and services must consume the interface to allow local JVM unit tests to mock FFI stubs and avoid loading the native `.so` library.
*   **F-1.3**: The `decodeEvent` function must copy the returned C-string pointer into a Kotlin `String`, and invoke `oura_string_free` in a `finally` block to prevent leaks.
*   **F-1.4**: Methods on `OuraFfiWrapper` must return a Kotlin `Result<T>` instead of swallowing exceptions and returning nullable values, preserving domain error contexts for upper application layers.

---

## 2. Android BLE Ingestion & Framing
*   **F-2.1**: A Kotlin class `OuraBleService` extending `LifecycleService` or utilizing `BluetoothGattCallback` to parse incoming packet buffers.
*   **F-2.2**: Packet parsing logic must read incoming BLE notification bytes structured as `[tag, len, ...payload]`. The parser must perform strict length validation: if the buffer size is less than `2 + len`, the parser must fail fast and return `null` instead of silently truncating the payload.
*   **F-2.3**: Parse and reconstruct fragmented packets if BLE MTU is smaller than packet length.
*   **F-2.4**: Push decoded JSON data into a Jetpack Compose-friendly `StateFlow<List<String>>` or `MutableStateFlow` representing decoded event history.
*   **F-2.5**: Implement the "Live" HR flow: write `SetNotification(0x3f)` and `SetFeatureMode(DAYTIME_HR, CONNECTED_LIVE)` to force the ring to record daytime HR history events (`0x80`).
*   **F-2.6**: Implement connection state-gating inside `OuraBleService` to reject overlapping connection or pairing requests when a GATT session is already in progress.
*   **F-2.7**: Implement safety-gating in `OuraCompanionService` to skip background sync invocations if the service is already busy with an active foreground or background connection.
*   **F-2.8**: Expose audit logs showing MAC address normalizations and the first 4 bytes of cryptographic keys upon connection startup to troubleshoot storage desync.
*   **F-2.9**: Outbound request packages in `Req` must construct frames using pre-allocated `ByteBuffer` buffers to optimize memory and minimize GC allocation pressure.
*   **F-2.10**: Declare all raw byte constants (e.g. `CMD_AUTH_NONCE`, `TAG_TIME_SYNC`) explicitly to eliminate magic numbers from the protocol definition.

---

## 3. Architectural Subsystems (Modular Design)
*   **F-3.1**: The codebase must be partitioned into decoupled subsystems: `transport`, `auth`, `sync`, and `controller`.
*   **F-3.2**: The `transport` subsystem must manage BluetoothGatt connection states and sequential byte-channel reassembly, exposing a thread-safe `Channel<Packet>` stream.
*   **F-3.3**: The `auth` subsystem must encapsulate cryptographic handshakes, pairing processes, and session key installations, fully isolated from BLE callback hooks.
*   **F-3.4**: The `sync` subsystem must isolate clock drift calibration (NTP anchors) and local database sync loops.
*   **F-3.5**: The `controller` subsystem must expose high-level, type-safe commands (live HR, flight mode, resets) to decouple outbound writes from the service lifecycle.

---

## Success Criteria
1. The BLE service successfully discovers and connects to the associated ring.
2. The Kotlin FFI unit test suite runs and passes on local JVMs without needing the native dynamic library.
3. Decoded events are correctly routed to local memory flows and display in Compose views.
4. No network or remote server dependencies are present.
