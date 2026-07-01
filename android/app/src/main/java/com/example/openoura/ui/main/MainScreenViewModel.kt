package com.example.openoura.ui.main

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.companion.CompanionDeviceManager
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.example.openoura.ble.ConnectionState
import com.example.openoura.ble.OuraBleService
import com.example.openoura.ble.OuraDeviceMetadata
import com.example.openoura.ffi.OuraFfiBridge
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.security.SecureRandom

class DiscoveredDevice(val name: String, val address: String)

/**
 * ViewModel for the single Main Screen UI. It manages Bluetooth scanning state,
 * credentials storage, and interacts with the foreground OuraBleService.
 */
@SuppressLint("MissingPermission")
class MainScreenViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private var service: OuraBleService? = null
    private var isBound = false

    private val sharedPrefs = context.getSharedPreferences("open_oura_prefs", Context.MODE_PRIVATE)

    private val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
    private val securePrefs = EncryptedSharedPreferences.create(
        "oura_secure_prefs",
        masterKeyAlias,
        context,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    // Flow states mirroring service state
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _deviceMetadata = MutableStateFlow(OuraDeviceMetadata())
    val deviceMetadata: StateFlow<OuraDeviceMetadata> = _deviceMetadata.asStateFlow()

    private val _syncProgress = MutableStateFlow<String?>(null)
    val syncProgress: StateFlow<String?> = _syncProgress.asStateFlow()

    // Decoded event history flow
    private val _decodedEventHistory = MutableStateFlow<List<String>>(emptyList())
    val decodedEventHistory: StateFlow<List<String>> = _decodedEventHistory.asStateFlow()

    // Diagnostics logs flow
    private val _diagnosticLogs = MutableStateFlow<List<String>>(emptyList())
    val diagnosticLogs: StateFlow<List<String>> = _diagnosticLogs.asStateFlow()

    // Bluetooth scanning states
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = _discoveredDevices.asStateFlow()

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }

    private var pendingPairing: Pair<String, ByteArray>? = null

    private var pairingAttemptCount = 0

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, binder: IBinder) {
            val ouraBinder = binder as OuraBleService.OuraBinder
            val boundService = ouraBinder.getService()
            service = boundService
            isBound = true

            // Attach listeners to service flows
            viewModelScope.launch {
                OuraBleService.connectionState.collect { state ->
                    _connectionState.value = state
                    if (state == ConnectionState.Ready) {
                        pairingAttemptCount = 0
                    } else if (state is ConnectionState.Failed) {
                        pairingAttemptCount++
                    }
                }
            }
            viewModelScope.launch {
                OuraBleService.deviceMetadata.collect { _deviceMetadata.value = it }
            }
            viewModelScope.launch {
                OuraBleService.syncProgress.collect { _syncProgress.value = it }
            }
            viewModelScope.launch {
                OuraBleService.decodedEventHistory.collect { _decodedEventHistory.value = it }
            }

            // CRITICAL: Prioritize volatile pending pair states over passive auto-reconnections
            pendingPairing?.let { (mac, key) ->
                Log.i("MainScreenViewModel", "Executing recovered pending pairing for $mac")
                boundService.pairNewRing(mac, key)
                pendingPairing = null
            } ?: run {
                // Otherwise auto-reconnect if ring address and key are already paired
                autoReconnectIfPossible()
            }
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            service = null
            isBound = false
        }
    }

    init {
        // Poll diagnostics from Rust FFI queue every 500ms
        viewModelScope.launch {
            while (true) {
                delay(500)
                try {
                    val logMsg = OuraFfiBridge.popDiagnostic()
                    if (logMsg != null) {
                        val currentLogs = _diagnosticLogs.value.toMutableList()
                        if (currentLogs.size >= 200) {
                            currentLogs.removeAt(0)
                        }
                        currentLogs.add(logMsg)
                        _diagnosticLogs.value = currentLogs
                    }
                } catch (e: Throwable) {
                    Log.e("MainScreenViewModel", "Failed to pop diagnostic: ${e.message}")
                    delay(2000) // Back off on error
                }
            }
        }
    }

    /**
     * Start and bind to the foreground OuraBleService.
     * Called after permissions are confirmed in MainActivity.
     */
    fun initializeService() {
        if (isBound) return

        val intent = Intent(context, OuraBleService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            Log.e("MainScreenViewModel", "Failed to start service: ${e.message}")
        }
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    fun startScan() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        if (_isScanning.value) return

        setScanning(true)

        // Stop scan after 15 seconds
        Handler(Looper.getMainLooper()).postDelayed({
            stopScan()
        }, 15000)

        scanner.startScan(scanCallback)
    }

    fun stopScan() {
        if (!_isScanning.value) return
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        scanner.stopScan(scanCallback)
        setScanning(false)
    }

    /**
     * Update scanning state for UI feedback.
     */
    fun setScanning(active: Boolean) {
        _isScanning.value = active
        if (active) {
            OuraBleService.updateConnectionState(ConnectionState.Scanning, "User started scanning")
        } else {
            // When stopping, only revert to Idle if we were actually scanning.
            // This prevents overwriting 'Connecting' or 'Ready' states during rebirth recovery.
            val currentState = OuraBleService.connectionState.value
            if (currentState == ConnectionState.Scanning) {
                OuraBleService.updateConnectionState(ConnectionState.Idle, "Scanning stopped/cancelled")
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val name = device.name ?: result.scanRecord?.deviceName ?: "Unknown Device"
            
            Log.d("MainScreenViewModel", "Discovered: $name (${device.address})")

            if (name.contains("Oura", ignoreCase = true) || name.contains("Ring", ignoreCase = true)) {
                val currentList = _discoveredDevices.value
                if (currentList.none { it.address == device.address }) {
                    _discoveredDevices.value = currentList + DiscoveredDevice(name, device.address)
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e("MainScreenViewModel", "Scan failed with error: $errorCode")
            setScanning(false)
        }
    }

    /**
     * Connect to an existing ring with a known MAC and key hex.
     */
    fun connectDevice(macAddress: String, keyHex: String) {
        stopScan()
        try {
            val sanitizedMac = macAddress.trim().uppercase()
            val keyBytes = hexStringToByteArray(keyHex.trim())
            if (keyBytes.size != 16) {
                OuraBleService.updateConnectionState(ConnectionState.Failed("Key must be 16 bytes (32 hex characters)"), "Invalid manual key length")
                return
            }

            // Save credentials
            sharedPrefs.edit().putString("ring_mac", sanitizedMac).apply()
            securePrefs.edit().putString("ring_key", keyHex).apply()

            service?.connectToDevice(sanitizedMac, keyBytes)
        } catch (e: Exception) {
            OuraBleService.updateConnectionState(ConnectionState.Failed("Invalid key format: ${e.message}"), "Hex parsing error")
        }
    }

    /**
     * Called when a ring is successfully associated via CompanionDeviceManager.
     * Generates a key, registers for range/presence events, and runs initial pairing.
     */
    fun onCompanionAssociated(macAddress: String) {
        // If we've already tried too many times, abort auto-retry to prevent loops
        if (pairingAttemptCount >= 3) {
            Log.w("MainScreenViewModel", "Max pairing retries reached. Standing down.")
            _connectionState.value = ConnectionState.Failed("Maximum connection retries exceeded.")
            return
        }
        setScanning(false)
        val sanitizedMac = macAddress.uppercase()

        // Check if we have an existing key context, otherwise allocate a clean token
        var keyHex = securePrefs.getString("ring_key", null)
        val keyBytes = if (keyHex == null) {
            ByteArray(16).apply { SecureRandom().nextBytes(this) }.also {
                keyHex = byteArrayToHexString(it)
                securePrefs.edit().putString("ring_key", keyHex).apply()
            }
        } else {
            hexStringToByteArray(keyHex!!)
        }

        sharedPrefs.edit().putString("ring_mac", sanitizedMac).apply()

        // Start observing presence at the OS level
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val cdm = context.getSystemService(Context.COMPANION_DEVICE_SERVICE) as CompanionDeviceManager
            try {
                cdm.startObservingDevicePresence(sanitizedMac)
                Log.d("MainScreenViewModel", "Presence observation started for: $sanitizedMac")
            } catch (e: Exception) {
                Log.e("MainScreenViewModel", "Failed to start presence observation: ${e.message}")
            }
        }

        // Perform initial pairing handshake
        if (service != null) {
            Log.i("MainScreenViewModel", "Service alive, entering initial pairing handshake.")
            service?.pairNewRing(sanitizedMac, keyBytes)
        } else {
            Log.i("MainScreenViewModel", "Service unlinked during rebirth. Caching pending configuration.")
            // CACHE HERE: This forces onServiceConnected to execute the link immediately on bind
            pendingPairing = Pair(sanitizedMac, keyBytes)
            initializeService()
        }
    }

    /**
     * Generate a new key and pair a factory-reset ring.
     */
    fun pairNewDevice(macAddress: String) {
        stopScan()
        val sanitizedMac = macAddress.uppercase()
        val keyBytes = ByteArray(16)
        SecureRandom().nextBytes(keyBytes)
        val keyHex = byteArrayToHexString(keyBytes)

        // Save credentials
        sharedPrefs.edit().putString("ring_mac", sanitizedMac).apply()
        securePrefs.edit().putString("ring_key", keyHex).apply()

        service?.pairNewRing(sanitizedMac, keyBytes)
    }

    fun triggerSync() {
        viewModelScope.launch {
            service?.syncHistory()
        }
    }

    fun disconnect() {
        service?.disconnect()
    }

    fun forgetDevice() {
        val mac = sharedPrefs.getString("ring_mac", null)
        disconnect()

        if (mac != null) {
            val cdm = context.getSystemService(Context.COMPANION_DEVICE_SERVICE) as CompanionDeviceManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    cdm.stopObservingDevicePresence(mac)
                } catch (e: Exception) {
                    Log.e("MainScreenViewModel", "Failed to stop presence observation: ${e.message}")
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    cdm.disassociate(mac)
                } catch (e: Exception) {
                    Log.e("MainScreenViewModel", "Failed to disassociate companion device: ${e.message}")
                }
            }
        }

        sharedPrefs.edit().remove("ring_mac").remove("sync_cursor").apply()
        securePrefs.edit().remove("ring_key").apply()
        OuraBleService.updateConnectionState(ConnectionState.Idle, "User cleared device")
        _deviceMetadata.value = OuraDeviceMetadata()
    }

    /**
     * Public trigger to connect to the saved device using existing credentials.
     */
    fun connectAndSync() {
        val mac = sharedPrefs.getString("ring_mac", null)
        val keyHex = securePrefs.getString("ring_key", null)
        if (mac != null && keyHex != null) {
            connectDevice(mac, keyHex)
        } else {
            OuraBleService.updateConnectionState(ConnectionState.Failed("No saved device found."), "Manual Connect Trigger")
        }
    }

    private fun autoReconnectIfPossible() {
        val mac = sharedPrefs.getString("ring_mac", null)
        val keyHex = securePrefs.getString("ring_key", null)
        if (mac != null && keyHex != null) {
            val sanitizedMac = mac.uppercase()
            val keyBytes = hexStringToByteArray(keyHex)

            // If our application state indicates we dropped mid-pairing or just associated,
            // run pairNewRing instead of connectToDevice to force a 0x25 token write.
            if (OuraBleService.connectionState.value == ConnectionState.Idle) {
                Log.i("MainScreenViewModel", "Auto-recovering link state for device: $sanitizedMac")
                service?.connectToDevice(sanitizedMac, keyBytes)
            }
        }
    }

    fun getSavedMacAddress(): String? = sharedPrefs.getString("ring_mac", null)
    fun getSavedKeyHex(): String? = securePrefs.getString("ring_key", null)

    private fun hexStringToByteArray(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    private fun byteArrayToHexString(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }

    override fun onCleared() {
        super.onCleared()
        if (isBound) {
            context.unbindService(serviceConnection)
            isBound = false
        }
    }
}
