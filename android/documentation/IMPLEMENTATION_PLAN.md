# Implementation Plan: open_oura Android Hardening & Observability

This plan describes the architectural changes to harden the `open_oura` Android sync bridge, focusing on edge efficiency, temporal alignment accuracy, and runtime observability.

---

## 1. Proposed Upgrades

### 1.1 Chronological Anchoring: NTP-Style Drift Calibration
*   **The Problem:** The current anchoring math assumes the wearable's clock does not drift. If the ring has not connected for multiple days, the internal oscillator drift can cause historical sample times to mismatch reality by minutes.
*   **The Fix:** We will update `HealthConnectManager.kt` to scan for `time_sync` events (`0x42` tag) decoded by the Rust core.
    *   Each `time_sync` event contains the ring's decisecond tick counter paired with the absolute phone UTC timestamp at the moment of sync.
    *   We will align each heart rate and HRV sample using the nearest preceding `time_sync` event as a local epoch baseline:
        $$\text{sampleTimeMillis} = (\text{timeSyncUnix} \times 1000) + ((\text{sampleDeciseconds} - \text{timeSyncDeciseconds}) \times 100)$$

### 1.2 BLE Lifecycle: CompanionDeviceManager (CDM) Integration
*   **The Problem:** Running a persistent foreground BLE service for background sync drains battery and is throttled by Android 16's battery optimizations (Doze mode).
*   **The Fix:** We will migrate background auto-sync to Android's **CompanionDeviceManager (CDM)** API.
    *   During the pairing flow, the app will request companion association using the system-managed device picker.
    *   We will implement `OuraCompanionService` extending `CompanionDeviceService`. The OS automatically wakes this service in the background whenever the associated ring is nearby.
    *   Upon wakeup, `OuraCompanionService` will start `OuraBleService` to perform a short, targeted sync loop, then shut down gracefully to consume zero idle memory/battery.

### 1.3 Concurrency Hardening & Connection Collision Gating
*   **The Problem:** Overlapping connection requests (caused by background Companion wakeups triggering at the same time as UI binds) trigger duplicate `connectGatt` sessions, causing the ring to reject the cryptographic handshake and drop the connection.
*   **The Fix:**
    *   Add state checks in `OuraBleService` to abort `connectToDevice` and `pairNewRing` if a connection is currently active.
    *   Add safety checks in `OuraCompanionService` to skip starting the background service if it is already connecting or active.
    *   Print audit logs containing normalized uppercase MAC addresses and the first 4 bytes of cryptographic keys upon connection startup to allow storage validation.

### 1.4 Observability: Rust-Native Diagnostic Ring Buffer
*   **The Problem:** Troubleshooting decryption failures, AES handshakes, and packet reassembly issues from Kotlin standard outputs is difficult due to system log truncation. Furthermore, popping entries one-by-one causes heavy lock contention during high-volume sync logging.
*   **The Fix:** We will add a diagnostic logging buffer directly to our FFI.
    *   Expose a flat C-ABI function `oura_drain_diagnostics()` returning a bulk JSON-serialized array of all accumulated logs, minimizing FFI boundary crossing and lock overhead.
    *   Integrate a developer log-viewer panel in the Compose UI.

### 1.5 Codebase Compartmentalization & Subsystems
*   **The Problem:** `OuraBleService.kt` currently holds connection state machine, JNA decryption, CDM integration, clock calibration, packet reassembly, and Health Connect sync logic in a single file (~800 lines). This architecture is fragile, non-scalable, and makes testing low-level systems impossible without mocking the Android Service framework.
*   **The Fix:** Split the BLE service into decoupled subsystems:
    *   **Transport Subsystem (`transport/`)**: `BleTransportEngine` handles raw `BluetoothGatt` callbacks and `PacketReassembler` reassembles incoming 2-byte packet buffers sequentially.
    *   **Authentication Subsystem (`auth/`)**: `OuraAuthenticator` manages cryptographic pairing (`pairNewRing`) and key validation sequences (`runAuthentication`), isolated from BLE callbacks.
    *   **Sync Subsystem (`sync/`)**: `HistorySyncManager` manages historical log synchronization and cursor persistence, delegating clock drift calculations to `DriftCalibrator`.
    *   **Controller Subsystem (`controller/`)**: `OuraController` exposes clean APIs for high-level commands (Flight Mode, Fast HR, Factory Reset) without coupling writes to the service lifecycle.
    *   **Slim Coordinator (`OuraBleService.kt`)**: Remains as a thin entry point for foreground service notifications, binding hooks, and companion intents, delegating all operations to subsystems.

### 1.6 Testability and Code Quality Refactoring
*   **The Problem:** The app suffers from untestable static coupling, silent truncation bugs, magic numbers, and swallowed exceptions.
*   **The Fix:**
    *   **JNA Abstraction**: Extract `OuraFfiBridge` into an interface `OuraFfiWrapper`. Inject this wrapper into the services to allow JUnit JVM tests to mock FFI behavior and avoid `UnsatisfiedLinkError`.
    *   **Fail-Fast Parsing**: Modify `Packet.parse` to validate the frame length against the actual buffer size, returning `null` if the buffer is incomplete rather than silently truncating it.
    *   **Domain Exception Propagation**: Wrap JNA FFI decoders and encryptors to return `Result<T>` instead of swallowing errors and returning null, ensuring upper layers can handle domain failures appropriately.
    *   **ByteBuffer Serialization**: Rewrite array builders in `Req` utilizing `ByteBuffer` allocations to optimize memory and minimize GC pauses during active BLE streams.
    *   **Constants over Magic Numbers**: Define protocol command bytes (tags, sub-tags, flags) as clean descriptive Kotlin constants at the top of `OuraGATT.kt`.

### 1.7 Dashboard Log Filtering (UI Bug Fix)
*   **The Problem:** The Compose dashboard's "Last Synced Events" card displays trailing system telemetry event packets (tags `0x43` and `0x61`) emitted at the end of a sync, pushing actual biometric events out of view.
*   **The Fix:** Update the dashboard event rendering logic to filter out non-biometric tag logs (`0x43` `debug_event` and `0x61` `debug_data`), leaving only actual metric summaries (HR/HRV) visible to the user.

### 1.8 Missing Biometric Tag Mapping (Health Ingestion Fix)
*   **The Problem:** Newer Oura Ring models store sleep or live biometric readings under tags `0x55` (`sleep_heart_rate`), `0x71` (`green_ibi_and_amplitude_event`), and `0x6e` (`spo2_ibi_and_amplitude_event`). The native Rust library does not decode these tags, and `HealthConnectManager.kt` drops them.
*   **The Fix:** Implement decoders in `crates/oura-protocol/src/events.rs` and extend the Kotlin event mapper to bind and write these samples to Health Connect.

### 1.9 Pairing State Machine Fallback & State Re-Push (State Recovery Fix)
*   **The Problem:** If a user pairs a pre-keyed ring (key already written), the ring ignores the `0x24` (`SetAuthKey`) pairing handshake packet, causing the connection setup sequence to time out. Additionally, binding race conditions can cause the UI to miss early connection state updates.
*   **The Fix:** Update `runSetupFlow` to fall back immediately to standard `0x2f` challenge-response authentication if the `0x24` handshake fails. Force-push the current connection state inside `onServiceConnected` during ViewModel binding.

---

## 2. Proposed Changes

#### [MODIFY] [HealthConnectManager.kt](file:///c:/Users/kevin/Documents/Github/open_oura/android/app/src/main/java/com/example/openoura/health/HealthConnectManager.kt)
*   Update `mapEventsToRecords` to dynamically search for `time_sync` anchors and offset data timestamps using the nearest baseline sync point.

#### [NEW] [OuraCompanionService.kt](file:///c:/Users/kevin/Documents/Github/open_oura/android/app/src/main/java/com/example/openoura/ble/OuraCompanionService.kt)
*   Implement `CompanionDeviceService` to handle system-level bluetooth attachment and detachment events, automatically spinning up and stopping the BLE sync service.

#### [NEW] [BleTransportEngine.kt](file:///c:/Users/kevin/Documents/Github/open_oura/android/app/src/main/java/com/example/openoura/ble/transport/BleTransportEngine.kt) & [PacketReassembler.kt](file:///c:/Users/kevin/Documents/Github/open_oura/android/app/src/main/java/com/example/openoura/ble/transport/PacketReassembler.kt)
*   Encapsulate BluetoothGatt connections and callbacks. Maintain sequential channel ingestion.

#### [NEW] [OuraAuthenticator.kt](file:///c:/Users/kevin/Documents/Github/open_oura/android/app/src/main/java/com/example/openoura/ble/auth/OuraAuthenticator.kt) & [CredentialStore.kt](file:///c:/Users/kevin/Documents/Github/open_oura/android/app/src/main/java/com/example/openoura/ble/auth/CredentialStore.kt)
*   Wrap session nonces, JNA encrypt calls, and EncryptedSharedPreferences reads/writes.

#### [NEW] [HistorySyncManager.kt](file:///c:/Users/kevin/Documents/Github/open_oura/android/app/src/main/java/com/example/openoura/ble/sync/HistorySyncManager.kt) & [DriftCalibrator.kt](file:///c:/Users/kevin/Documents/Github/open_oura/android/app/src/main/java/com/example/openoura/ble/sync/DriftCalibrator.kt)
*   Isolate the history sync logs drainage and clock drift anchoring calibration.

#### [NEW] [OuraController.kt](file:///c:/Users/kevin/Documents/Github/open_oura/android/app/src/main/java/com/example/openoura/ble/controller/OuraController.kt)
*   Provide high-level command interfaces (Live HR, Flight Mode, Factory Reset).

#### [MODIFY] [OuraBleService.kt](file:///c:/Users/kevin/Documents/Github/open_oura/android/app/src/main/java/com/example/openoura/ble/OuraBleService.kt)
*   Refactor to decouple connection state management and delegates from low-level implementations.

#### [MODIFY] [AndroidManifest.xml](file:///c:/Users/kevin/Documents/Github/open_oura/android/app/src/main/AndroidManifest.xml)
*   Register `OuraCompanionService` with the `BIND_COMPANION_DEVICE_SERVICE` permission.
*   Add `<uses-feature android:name="android.software.companion_device_setup" />`.

#### [MODIFY] [MainScreen.kt](file:///c:/Users/kevin/Documents/Github/open_oura/android/app/src/main/java/com/example/openoura/ui/main/MainScreen.kt)
*   Add Companion Device association flow trigger.
*   Add a scrollable developer diagnostic logs viewer section.

#### [MODIFY] [MainScreenViewModel.kt](file:///c:/Users/kevin/Documents/Github/open_oura/android/app/src/main/java/com/example/openoura/ui/main/MainScreenViewModel.kt)
*   Integrate CDM association callbacks and expose diagnostic log collection from JNA.

#### [MODIFY] [OuraFfiLibrary.kt](file:///c:/Users/kevin/Documents/Github/open_oura/android/app/src/main/java/com/example/openoura/ffi/OuraFfiLibrary.kt) & [OuraFfiBridge.kt](file:///c:/Users/kevin/Documents/Github/open_oura/android/app/src/main/java/com/example/openoura/ffi/OuraFfiBridge.kt)
*   Add bindings for `oura_pop_diagnostic` with automatic pointer freeing.

---

## 3. Verification Plan

### Automated Tests
1.  **Drift Calibration Test (`HealthConnectTest.kt`)**: Verify mapping with non-zero oscillator drift (i.e., data events offset relative to multiple distinct `time_sync` events).
2.  **Diagnostics FFI Test (`OuraFfiBridgeTest.kt`)**: Assert that calling FFI methods populates logs and that `oura_pop_diagnostic` successfully retrieves and clears log entries from the queue.

### Manual Verification
1.  Verify the Companion Device Setup system-controlled dialog pops up and successfully associates with nearby bluetooth devices on the Pixel 10 Pro.
2.  Trigger background wakeups by toggling Bluetooth on/off and checking companion connection service logs.
