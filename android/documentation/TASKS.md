# Tasks: open_oura Android Hardening & Observability

- [x] **Phase 1: Chronological Anchoring (Drift Calibration)**
  - [x] Implement `time_sync` extraction in `HealthConnectManager.kt`
  - [x] Implement offset calculations using dynamic anchors in `mapEventsToRecords`
  - [x] Write local unit test in `HealthConnectTest.kt` validating drift correction
- [x] **Phase 2: CompanionDeviceManager (CDM) Integration**
  - [x] Declare CDM permissions and `<uses-feature>` in `AndroidManifest.xml`
  - [x] Create `OuraCompanionService.kt` to handle OS-level range wakeups
  - [x] Connect CDM pairing requests in `MainScreenViewModel.kt`
  - [x] Update `MainScreen.kt` to trigger CDM system-association dialogs
- [x] **Phase 3: Rust-Native Diagnostic Ring Buffer**
  - [x] Create thread-safe static log deque in `crates/oura-ffi/src/lib.rs`
  - [x] Expose `oura_pop_diagnostic` C-ABI function in Rust
  - [x] Bind and wrap `oura_pop_diagnostic` in `OuraFfiLibrary` and `OuraFfiBridge`
  - [x] Build a Compose log-viewer section in `MainScreen.kt`
  - [x] Add an instrumentation test in `OuraFfiBridgeTest.kt` verifying diagnostic log retrieval
