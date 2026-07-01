package com.example.openoura.ble

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.openoura.ble.auth.CredentialStore
import com.example.openoura.ble.auth.OuraAuthenticator
import com.example.openoura.ble.controller.OuraController
import com.example.openoura.ble.sync.HistorySyncManager
import com.example.openoura.ble.transport.BleTransportEngine
import com.example.openoura.ffi.OuraFfiBridge
import com.example.openoura.health.HealthConnectManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.File

sealed class ConnectionState {
    object Idle : ConnectionState()
    object Scanning : ConnectionState()
    object Connecting : ConnectionState()
    object Authenticating : ConnectionState()
    object Ready : ConnectionState()
    data class Failed(val reason: String) : ConnectionState()
}

data class OuraDeviceMetadata(
    val firmware: String? = null,
    val serial: String? = null,
    val hardware: String? = null,
    val batteryPercent: Int? = null,
    val isCharging: Boolean = false
)

/**
 * Foreground Service for coordinating OpenOura tasks, delegating operations to
 * dedicated modular subsystems (Transport, Authentication, Sync, and Device Controller).
 */
@SuppressLint("MissingPermission")
class OuraBleService : Service() {

    companion object {
        private const val TAG = "OuraBleService"
        private const val CHANNEL_ID = "OuraBleServiceChannel"
        private const val NOTIFICATION_ID = 42

        private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
        val connectionState: StateFlow<ConnectionState> = _connectionState

        /**
         * Thread-safe transition for connection state.
         */
        @Synchronized
        fun updateConnectionState(newState: ConnectionState, criteria: String = "Internal") {
            val oldState = _connectionState.value
            if (oldState != newState) {
                Log.i(TAG, "◆ [STATE CHANGE] $oldState ➔ $newState (Reason: $criteria)")
                _connectionState.value = newState
            }
        }

        private val _deviceMetadata = MutableStateFlow(OuraDeviceMetadata())
        val deviceMetadata: StateFlow<OuraDeviceMetadata> = _deviceMetadata

        private val _syncProgress = MutableStateFlow<String?>(null)
        val syncProgress: StateFlow<String?> = _syncProgress

        private val _decodedEventHistory = MutableStateFlow<List<String>>(emptyList())
        val decodedEventHistory: StateFlow<List<String>> = _decodedEventHistory
    }

    private val binder = OuraBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())

    private var bluetoothAdapter: BluetoothAdapter? = null

    @Volatile
    private var pendingPairingMac: String? = null
    @Volatile
    private var pendingPairingKey: ByteArray? = null

    // Subsystems
    lateinit var credentialStore: CredentialStore
    lateinit var transport: BleTransportEngine
    lateinit var authenticator: OuraAuthenticator
    lateinit var historySyncManager: HistorySyncManager
    lateinit var controller: OuraController

    inner class OuraBinder : Binder() {
        fun getService(): OuraBleService = this@OuraBleService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                createNotification("Initializing OpenOura sync..."),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification("Initializing OpenOura sync..."))
        }

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        // Instantiate Subsystems
        credentialStore = CredentialStore(this)
        controller = OuraController()

        authenticator = OuraAuthenticator(
            ffi = OuraFfiBridge,
            onProgress = { progress -> _syncProgress.value = progress },
            onStateChange = { state, reason -> updateConnectionState(state, reason) }
        )

        historySyncManager = HistorySyncManager(
            context = this,
            ffi = OuraFfiBridge,
            credentialStore = credentialStore,
            healthConnectManager = HealthConnectManager,
            onProgress = { progress -> _syncProgress.value = progress },
            onEventsSynced = { synced ->
                _decodedEventHistory.value = _decodedEventHistory.value + synced
            }
        )

        transport = BleTransportEngine(
            context = this,
            bluetoothAdapter = bluetoothAdapter!!,
            scope = serviceScope,
            onStateChange = { state, reason -> updateConnectionState(state, reason) },
            onDescriptorEnabled = { isPairing ->
                serviceScope.launch {
                    runSetupFlow(isPairing)
                }
            }
        )

        Log.d(TAG, "Service Created. Initial state: ${connectionState.value}")

        // Load initial history from disk
        loadEventHistory()

        // Start listening to inbound notifications for stream processing
        serviceScope.launch {
            for (packet in transport.inboundPackets) {
                handleStreamPacket(packet)
            }
        }
    }

    private fun loadEventHistory() {
        try {
            val file = File(filesDir, "oura_history.json")
            if (file.exists()) {
                val content = file.readText()
                val jsonArray = JSONArray(content)
                val list = mutableListOf<String>()
                for (i in 0 until jsonArray.length()) {
                    list.add(jsonArray.getString(i))
                }
                _decodedEventHistory.value = list
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load event history: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            val shouldSyncAndStop = intent.getBooleanExtra("trigger_sync_and_stop", false)
            val mac = intent.getStringExtra("mac_address")
            val keyHex = intent.getStringExtra("auth_key_hex")

            if (shouldSyncAndStop && mac != null && keyHex != null) {
                runLimitedSyncBudget(mac, keyHex)
            }
        }
        return START_STICKY
    }

    private fun runLimitedSyncBudget(mac: String, keyHex: String) {
        serviceScope.launch {
            // Start strict 45-second execution budget timeout
            val timeoutJob = launch {
                delay(45000)
                Log.w(TAG, "Sync budget exceeded 45s. Forcing shutdown.")
                disconnect()
                stopSelf()
            }

            try {
                // 1. Establish connection and authenticate
                val keyBytes = hexStringToByteArray(keyHex)
                connectToDevice(mac, keyBytes)

                // 2. Wait until connection state is Ready (or fails)
                var elapsed = 0
                while (connectionState.value != ConnectionState.Ready && elapsed < 15) {
                    delay(1000)
                    elapsed++
                }

                // 3. Trigger sync if ready
                if (connectionState.value == ConnectionState.Ready) {
                    syncHistory()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed during background sync budget run: ${e.message}", e)
            } finally {
                // 4. Cancel the timeout timer, disconnect, and stop service
                timeoutJob.cancel()
                disconnect()
                stopSelf()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    /**
     * Connect to an already paired ring MAC address.
     */
    fun connectToDevice(macAddress: String, authKey: ByteArray) {
        val sanitizedMac = macAddress.uppercase()
        val currentState = connectionState.value
        if (currentState != ConnectionState.Idle && currentState !is ConnectionState.Failed) {
            Log.w(TAG, "Ignoring connect request for $sanitizedMac: Already in state $currentState")
            return
        }

        val keyHex = byteArrayToHexString(authKey)
        credentialStore.saveCredentials(sanitizedMac, keyHex)
        credentialStore.setPairingVerified(true)

        val snippet = authKey.take(4).joinToString("") { "%02x".format(it) }
        Log.i(TAG, "◆ [CONN ATTEMPT] Target: $sanitizedMac | Key Prefix: $snippet... | Size: ${authKey.size}")

        updateNotification("Connecting to Oura Ring...")
        transport.connect(sanitizedMac, isPairing = false)
    }

    /**
     * Scan and Pair with a factory-reset ring.
     */
    fun pairNewRing(macAddress: String, generatedKey: ByteArray) {
        val sanitizedMac = macAddress.uppercase()
        val currentState = connectionState.value
        if (currentState != ConnectionState.Idle && currentState !is ConnectionState.Failed && currentState != ConnectionState.Scanning) {
            Log.w(TAG, "Ignoring pairing request for $sanitizedMac: Already in state $currentState")
            return
        }

        // Defer credentialStore persistence until setup handshake is confirmed (Key Erasure Fix)
        pendingPairingMac = sanitizedMac
        pendingPairingKey = generatedKey

        val snippet = generatedKey.take(4).joinToString("") { "%02x".format(it) }
        Log.i(TAG, "◆ [PAIR ATTEMPT] Target: $sanitizedMac | Pending Key Prefix: $snippet... | Size: ${generatedKey.size}")

        updateNotification("Pairing with Oura Ring...")
        transport.connect(sanitizedMac, isPairing = true)
    }

    fun disconnect() {
        transport.disconnect()
        updateNotification("Disconnected")
    }

    private suspend fun runSetupFlow(isPairing: Boolean) {
        val key: ByteArray
        val mac: String

        if (isPairing && pendingPairingKey != null && pendingPairingMac != null) {
            key = pendingPairingKey!!
            mac = pendingPairingMac!!
        } else {
            val keyHex = credentialStore.getSavedKeyHex()
            val savedMac = credentialStore.getSavedMacAddress()
            if (keyHex == null || savedMac == null) {
                updateConnectionState(ConnectionState.Failed("No saved key"), "Missing credentials")
                return
            }
            key = hexStringToByteArray(keyHex)
            mac = savedMac
        }

        if (isPairing) {
            val success = authenticator.runPairingHandshake(key, transport)
            if (success) {
                // Connection successfully paired, persist credentials now (Key Erasure Fix)
                credentialStore.saveCredentials(mac, byteArrayToHexString(key))
                credentialStore.setPairingVerified(true)
                val authSuccess = authenticator.runAuthentication(key, transport)
                if (authSuccess) {
                    onAuthSuccess()
                }
            } else {
                Log.w(TAG, "Pairing handshake failed/ignored, falling back to standard authentication (key may already exist)")
                val authSuccess = authenticator.runAuthentication(key, transport)
                if (authSuccess) {
                    // Ring already has this key, persist credentials now (Key Erasure Fix)
                    credentialStore.saveCredentials(mac, byteArrayToHexString(key))
                    credentialStore.setPairingVerified(true)
                    onAuthSuccess()
                } else {
                    updateConnectionState(ConnectionState.Failed("Authentication failed"), "Pairing & standard auth both failed")
                }
            }
            // Clean up temporary pending contexts
            pendingPairingKey = null
            pendingPairingMac = null
        } else {
            val authSuccess = authenticator.runAuthentication(key, transport)
            if (authSuccess) {
                onAuthSuccess()
            }
        }
    }

    private suspend fun onAuthSuccess() {
        updateConnectionState(ConnectionState.Ready, "Authentication verified successfully")
        _syncProgress.value = null
        updateNotification("OpenOura: Synced and running")

        // Read metadata
        val meta = controller.readDeviceMetadata(transport)
        _deviceMetadata.value = meta

        // Setup Live HR
        controller.setupLiveHr(transport)
    }

    /**
     * Trigger Incremental History Sync.
     */
    suspend fun syncHistory() {
        if (connectionState.value != ConnectionState.Ready) {
            Log.w(TAG, "Cannot sync: device not ready")
            return
        }
        historySyncManager.syncHistory(transport)
    }

    private fun handleStreamPacket(packet: Packet) {
        if (packet.tag == OuraGATT.REALTIME_ACM_RESPONSE_TAG) {
            val p = packet.payload
            if (p.size >= 10 && p[0] == 0x20.toByte()) {
                fun s(o: Int): Short {
                    return ((p[o].toInt() and 0xff) or ((p[o + 1].toInt() and 0xff) shl 8)).toShort()
                }
                val x = s(4).toDouble()
                val y = s(6).toDouble()
                val z = s(8).toDouble()
                val g = Math.sqrt(x*x + y*y + z*z) / 1024.0
                Log.d(TAG, "Live motion: $g g")
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "OpenOura BLE Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(content: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OpenOura Daemon")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .build()
    }

    private fun updateNotification(content: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, createNotification(content))
    }

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

    override fun onDestroy() {
        disconnect()
        super.onDestroy()
    }
}
