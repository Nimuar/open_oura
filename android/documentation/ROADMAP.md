# open_oura Android Application Roadmap

This roadmap details the path toward finalizing the Minimum Viable Product (MVP) and outlines post-MVP feature expansions. It draws guidance from the existing iOS app's settings structure and user requests.

---

## Phase 1: MVP Core Polish (Immediate Focus)

The current MVP codebase has successfully implemented 2-byte native packet framing, NTP drift calibration, background wakes via `CompanionDeviceManager`, and Rust FFI integration. To finalize the MVP, the following immediate gap must be resolved:

### 1. Reconnection & Sync UX Separation
*   **The Problem:** The app lacks a dedicated way to connect and sync using existing credentials. Tapping "Companion Setup" triggers a new key generation sequence, overwriting the saved credentials and breaking the connection for non-factory-reset rings.
*   **The Fix:**
    *   Expose a public `connectAndSync()` ViewModel routine that initiates a standard connection via `OuraBleService.connectToDevice(mac, key)` using current SharedPreferences.
    *   Add a prominent **Connect & Sync** button in `MainScreen.kt` visible whenever valid credentials exist in storage but the connection state is not `Ready`.
    *   Gate the **Companion Setup** button so it is only used for first-run configuration or after a "Forget Device" action.

---

## Phase 1.5: Ingestion, State, and UI Hardening (Bugs and Gaps Resolution)

The following bug-fixes and integration gaps will be resolved to ensure clean data delivery:

### 1. Dashboard UI Log Filtering
*   Filter out tag `0x43` (`debug_event`) and `0x61` (`debug_data`) events from the "Last Synced Events" Compose card so diagnostic data doesn't push actual heart rate/HRV summaries out of view.

### 2. Extended Biometric Tag Ingestion
*   Update `HealthConnectManager.kt` to parse heart rate and HRV samples from tags `0x55` (`sleep_heart_rate`), `0x71` (`green_ibi_and_amplitude_event`), and `0x6e` (`spo2_ibi_and_amplitude_event`) as they are decoded by the Rust core, preventing Health Connect data gaps.

### 3. Setup Handshake Fallback & Binding Re-Push
*   Auto-fallback to standard `0x2f` challenge-response authentication if the `0x24` (`SetAuthKey`) pairing handshake fails or times out.
*   Force-push the current connection state inside `onServiceConnected` in the ViewModel immediately upon binding to resolve UI state refresh lag.

---

## Phase 2: Post-MVP Feature Expansion

Once the MVP's connection lifecycle is fully polished, the following features will be introduced to elevate the user experience.

### Feature 1: Naming the Oura Ring (User Highlight)
*   **Local UI Nickname:** Allow users to assign a custom nickname (e.g. *"Kevin's Ring"*) in `SharedPreferences`. The Compose UI will render this nickname instead of the generic BLE advertisement name (`Oura Ring Gen3` or `Oura Ring 5`).
*   **GATT Name Query:** Read the friendly advertised device name and persist it to show context (e.g., distinguishing between ring generations in the dashboard).

### Feature 2: Advanced Connection & Auto-Sync Controls (iOS Alignment)
*   **Auto-Reconnect Toggle:** Add a settings toggle to enable/disable background proximity-based reconnection wakes via `CompanionDeviceService`.
*   **Auto-Sync on Connect Toggle:** A setting to specify whether the app should automatically trigger history event drainage (`syncHistory()`) immediately upon reaching the `Ready` state, or wait for manual sync commands.

### Feature 3: Advanced Key Import & Export
*   **Import Existing Key:** Allow power users to paste a 32-character hex string (16-byte key) generated from the Python CLI or iOS KeyStore. This permits immediate synchronization without having to factory-reset the ring.
*   **Export Key:** Provide a secure tap-to-copy button to export the locally generated cryptographic auth key so users can reuse it on other clients.

### Feature 4: Bluetooth Mode & Wearable Toggles
*   **Flight Mode Toggle:** Package and expose the `0x26` BLE mode packet to allow users to disconnect BLE and put the ring into Flight Mode (reactivating only when placed back on its physical charger).
*   **Fast HR/Realtime Mode Toggle:** Add a control switch to toggle fast heart-rate measurements (`0x16`/`0x31` commands) for active workout monitoring.

### Feature 5: Native Device Reset Controls (Danger Zone)
*   **Factory Reset Device:** Integrate a secure dialog box prompting the user to send the `1a 00` factory reset command. This wipes the on-device auth key, clears raw ring data, and prepares it for a fresh pairing sequence.

### Feature 6: Event Log Viewer & Share Export
*   A viewer to inspect successfully synced events from `oura_history.json` directly inside the Compose layout.
*   A share button to export `oura_history.json` and diagnostic logs for bug reporting.
