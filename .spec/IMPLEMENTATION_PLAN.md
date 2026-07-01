# Implementation Plan: open_oura Android Hardening & Observability

This plan describes the architectural changes to harden the `open_oura` Android sync bridge, focusing on edge efficiency, temporal alignment accuracy, and runtime observability without modifying the upstream Rust parsing core.

---

## Proposed Upgrades

### 1. Chronological Anchoring: NTP-Style Drift Calibration
*   **The Problem:** The current anchoring math assumes the wearable's clock does not drift. If the ring has not connected for multiple days, the internal oscillator drift can cause historical sample times to mismatch reality by minutes.
*   **The Fix:** We will update `HealthConnectManager.kt` to scan for `time_sync` events (`0x42` tag) decoded by the Rust core.
    *   Each `time_sync` event contains the ring's decisecond tick counter paired with the absolute phone UTC timestamp at the moment of sync.
    *   We will align each heart rate and HRV sample using the nearest preceding `time_sync` event as a local epoch baseline:
        $$\text{sample\_time} = \text{time\_sync\_unix} + \frac{\text{sample\_deciseconds} - \text{time\_sync\_deciseconds}}{10}$$
    *   This eliminates drift due to thermal variance or oscillator offset.

### 2. BLE Lifecycle: CompanionDeviceManager (CDM) Integration
*   **The Problem:** Running a persistent foreground BLE service for background sync drains battery and is throttled by Android 16's battery optimizations (Doze mode).
*   **The Fix:** We will migrate background auto-sync to Android's **CompanionDeviceManager (CDM)** API.
    *   During the pairing flow, the app will request companion association using the system-managed device picker.
    *   We will implement `OuraCompanionService` extending `CompanionDeviceService`. The OS automatically wakes this service in the background whenever the associated ring is nearby.
    *   Upon wakeup, `OuraCompanionService` will start `OuraBleService` to perform a short, targeted sync loop, then shut down gracefully to consume zero idle memory/battery.

### 3. Observability: Rust-Native Diagnostic Ring Buffer
*   **The Problem:** Troubleshooting decryption failures, AES handshakes, and packet reassembly issues from Kotlin standard outputs is difficult due to system log truncation.
*   **The Fix:** We will add a diagnostic logging buffer directly to our `oura-ffi` crate wrapper.
    *   Define a thread-safe, memory-bounded static queue (`Mutex<VecDeque<String>>`) in `crates/oura-ffi/src/lib.rs`.
    *   Write diagnostic log entries on every FFI boundary call (encryption success, packet decodes, invalid lengths).
    *   Expose a flat C-ABI function `oura_pop_diagnostic()` returning a null-terminated C-string to Kotlin JNA.
    *   Integrate a developer log-viewer panel in the Compose UI.

---

## Proposed Changes

### Android Layer (`android/`)

#### [MODIFY] [HealthConnectManager.kt](file:///c:/Users/kevin/Documents/Github/open_oura/android/app/src/main/java/com/example/openoura/health/HealthConnectManager.kt)
*   Update `mapEventsToRecords` to dynamically search for `time_sync` anchors and offset data timestamps using the nearest baseline sync point.

#### [NEW] [OuraCompanionService.kt](file:///c:/Users/kevin/Documents/Github/open_oura/android/app/src/main/java/com/example/openoura/ble/OuraCompanionService.kt)
*   Implement `CompanionDeviceService` to handle system-level bluetooth attachment and detachment events, automatically spinning up and stopping the BLE sync service.

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

### Rust FFI Layer (`crates/oura-ffi`)

#### [MODIFY] [lib.rs](file:///c:/Users/kevin/Documents/Github/open_oura/crates/oura-ffi/src/lib.rs)
*   Implement `Mutex<VecDeque<String>>` static buffer and a logger utility macro.
*   Expose `oura_pop_diagnostic` C-ABI function.

---

## Verification Plan

### Automated Tests
1.  **Drift Calibration Test (`HealthConnectTest.kt`)**: Verify mapping with non-zero oscillator drift (i.e., data events offset relative to multiple distinct `time_sync` events).
2.  **Diagnostics FFI Test (`OuraFfiBridgeTest.kt`)**: Assert that calling FFI methods populates logs and that `oura_pop_diagnostic` successfully retrieves and clears log entries from the queue.

### Manual Verification
1.  Verify the Companion Device Setup system-controlled dialog pops up and successfully associates with nearby bluetooth devices on the Pixel 10 Pro.
2.  Trigger background wakeups by toggling Bluetooth on/off and checking companion connection service logs.
