package com.example.openoura.ble

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.pm.ServiceInfo
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
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
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.util.UUID

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
 * Foreground Service for managing the Oura BLE connection, authentication, and background sync.
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
         * Thread-safe state transition with logging.
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

        private val _liveHeartRate = MutableStateFlow<Int?>(null)
        val liveHeartRate: StateFlow<Int?> = _liveHeartRate

        private val _syncProgress = MutableStateFlow<String?>(null)
        val syncProgress: StateFlow<String?> = _syncProgress
    }

    private val binder = OuraBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null

    private var activeKey: ByteArray? = null
    private var isPairingFlow = false

    // Raw bytes receiver buffer to handle fragmented packets
    private val notificationBuffer = ArrayList<Byte>()
    private var expectedLength = 0

    // Callback listeners for transactional requests
    private val responseListeners = mutableListOf<(Packet) -> Boolean>()

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
        
        Log.d(TAG, "Service Created. Initial state: ${connectionState.value}")
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

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    /**
     * Connect to an already paired ring MAC address.
     */
    fun connectToDevice(macAddress: String, authKey: ByteArray) {
        if (bluetoothAdapter == null) {
            updateConnectionState(ConnectionState.Failed("Bluetooth unsupported"), "No BT Adapter")
            return
        }

        activeKey = authKey
        isPairingFlow = false
        val device = bluetoothAdapter!!.getRemoteDevice(macAddress)

        updateConnectionState(ConnectionState.Connecting, "Manual connection started for $macAddress")
        updateNotification("Connecting to Oura Ring...")

        android.os.Handler(android.os.Looper.getMainLooper()).post {
            bluetoothGatt = device.connectGatt(
                applicationContext,
                false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE
            )
        }
    }

    /**
     * Scan and Pair with a factory-reset ring.
     */
    fun pairNewRing(macAddress: String, generatedKey: ByteArray) {
        if (bluetoothAdapter == null) {
            updateConnectionState(ConnectionState.Failed("Bluetooth unsupported"), "No BT Adapter")
            return
        }

        activeKey = generatedKey
        isPairingFlow = true
        val device = bluetoothAdapter!!.getRemoteDevice(macAddress)

        updateConnectionState(ConnectionState.Connecting, "Pairing sequence started for $macAddress")
        updateNotification("Pairing with Oura Ring...")

        android.os.Handler(android.os.Looper.getMainLooper()).post {
            bluetoothGatt = device.connectGatt(
                applicationContext,
                false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE
            )
        }
    }

    fun disconnect() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        writeCharacteristic = null
        updateConnectionState(ConnectionState.Idle, "Disconnect invoked")
        updateNotification("Disconnected")
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                val errorCode = "GATT Error $status"
                Log.e(TAG, errorCode)
                updateConnectionState(ConnectionState.Failed(errorCode), "GATT error callback")
                disconnect()
                return
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "GATT connected, discovering services...")
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "GATT disconnected")
                updateConnectionState(ConnectionState.Idle, "GATT disconnected callback")
                disconnect()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(OuraGATT.SERVICE_UUID)
                if (service != null) {
                    val notifyChar = service.getCharacteristic(OuraGATT.NOTIFY_CHAR_UUID)
                    writeCharacteristic = service.getCharacteristic(OuraGATT.WRITE_CHAR_UUID)

                    if (notifyChar != null && writeCharacteristic != null) {
                        Log.d(TAG, "Characteristics found, enabling notifications...")
                        gatt.setCharacteristicNotification(notifyChar, true)

                        // Enable local notification description
                        val descriptor = notifyChar.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                        descriptor?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(descriptor)
                    } else {
                        Log.e(TAG, "Oura characteristics not found")
                        updateConnectionState(ConnectionState.Failed("Unsupported ring firmware"), "Missing Oura Characteristics")
                    }
                } else {
                    Log.e(TAG, "Oura GATT service not found")
                    updateConnectionState(ConnectionState.Failed("Not an Oura ring"), "Missing Oura Service UUID")
                }
            } else {
                Log.e(TAG, "Service discovery failed with status $status")
                updateConnectionState(ConnectionState.Failed("Discovery failed"), "GATT discovery error $status")
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Notification descriptor enabled, starting authentication...")
                serviceScope.launch {
                    if (isPairingFlow) {
                        runPairingHandshake()
                    } else {
                        runAuthentication()
                    }
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val data = characteristic.value ?: return
            serviceScope.launch {
                handleInboundNotification(data)
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Failed to write characteristic: status $status")
            }
        }
    }

    private suspend fun runPairingHandshake() {
        val key = activeKey ?: return
        updateConnectionState(ConnectionState.Authenticating, "Descriptor write success, starting pairing")
        _syncProgress.value = "Installing auth key..."

        // Write the generated 16-byte key to the ring
        val pairPacket = Req.setAuthKey(key)
        writeRaw(pairPacket)

        val response = waitForPacket { it.tag == 0x25.toByte() }
        if (response != null && response.payload.isNotEmpty() && response.payload[0] == 0x00.toByte()) {
            Log.d(TAG, "Auth key installed successfully, running standard authentication...")
            isPairingFlow = false
            runAuthentication()
        } else {
            Log.e(TAG, "Pairing rejected by the ring")
            updateConnectionState(ConnectionState.Failed("Pairing rejected (is the ring factory-reset?)"), "Ring rejected 0x25 pairing packet")
        }
    }

    private suspend fun runAuthentication() {
        val key = activeKey ?: return
        updateConnectionState(ConnectionState.Authenticating, "Starting auth nonce request")
        _syncProgress.value = "Requesting auth nonce..."

        writeRaw(Req.authNonce)

        val noncePkt = waitForPacket { it.tag == 0x2f.toByte() && it.extTag == 0x2c.toByte() }
        if (noncePkt == null || noncePkt.payload.size <= 1) {
            updateConnectionState(ConnectionState.Failed("Auth failed (no nonce)"), "Timed out or empty nonce packet")
            return
        }

        // Extracted nonce is payload excluding the extended tag 0x2c
        val nonce = noncePkt.payload.copyOfRange(1, noncePkt.payload.size)
        val encrypted = OuraFfiBridge.encryptNonce(key, nonce)
        if (encrypted == null) {
            updateConnectionState(ConnectionState.Failed("Crypto error"), "Rust FFI encryption failed")
            return
        }

        _syncProgress.value = "Verifying auth key..."
        writeRaw(Req.authenticate(encrypted))

        val authResp = waitForPacket { it.tag == 0x2f.toByte() && it.extTag == 0x2e.toByte() }
        if (authResp != null && authResp.payload.size > 1 && authResp.payload[1] == 0x00.toByte()) {
            Log.d(TAG, "Authentication successful!")
            updateConnectionState(ConnectionState.Ready, "Auth packet 0x2e verified 0x00")
            _syncProgress.value = null
            updateNotification("OpenOura: Synced and running")

            // Read device info
            readDeviceInfo()
        } else {
            updateConnectionState(ConnectionState.Failed("Wrong authentication key"), "Ring rejected encrypted nonce")
        }
    }

    private suspend fun readDeviceInfo() {
        // Read battery
        writeRaw(Req.battery)
        val batPkt = waitForPacket { it.tag == 0x0d.toByte() }
        var batteryPct = 50
        var isCharging = false
        if (batPkt != null && batPkt.payload.size >= 3) {
            batteryPct = batPkt.payload[0].toInt() and 0xff
            isCharging = batPkt.payload[1] > 0
        }

        // Read firmware
        writeRaw(Req.firmware)
        val fwPkt = waitForPacket { it.tag == 0x09.toByte() }
        var firmware = "Unknown"
        if (fwPkt != null && fwPkt.payload.size >= 6) {
            firmware = "${fwPkt.payload[3]}.${fwPkt.payload[4]}.${fwPkt.payload[5]}"
        }

        _deviceMetadata.value = OuraDeviceMetadata(
            firmware = firmware,
            batteryPercent = batteryPct,
            isCharging = isCharging
        )
    }

    /**
     * Pull history events incrementally from the ring and write to Health Connect.
     */
    suspend fun syncHistory() {
        if (_connectionState.value != ConnectionState.Ready) {
            Log.w(TAG, "Cannot sync: device not ready")
            return
        }

        _syncProgress.value = "Starting history sync..."
        val sharedPrefs = getSharedPreferences("open_oura_prefs", Context.MODE_PRIVATE)
        var cursor = sharedPrefs.getInt("sync_cursor", 0)

        Log.d(TAG, "Syncing from cursor: $cursor")

        val newEvents = mutableListOf<String>()
        var bytesLeft = 1
        var maxTs = cursor.toLong()

        // Loop until there are no bytes left to read
        while (bytesLeft > 0) {
            _syncProgress.value = "Syncing events (cursor $cursor)..."

            val batch = getEventBatch(cursor)
            if (batch.events.isEmpty() && batch.bytesLeft == 0) {
                break
            }

            for (p in batch.events) {
                if (p.payload.size < 4) continue
                // Parse timestamp (4 bytes LE)
                val ts = ((p.payload[0].toInt() and 0xff) or
                          ((p.payload[1].toInt() and 0xff) shl 8) or
                          ((p.payload[2].toInt() and 0xff) shl 16) or
                          ((p.payload[3].toInt() and 0xff) shl 24)).toLong() and 0xffffffffL

                if (ts > maxTs) {
                    maxTs = ts
                }

                // Decode event body
                val body = p.payload.copyOfRange(4, p.payload.size)
                val decodedJson = OuraFfiBridge.decodeEvent(p.tag, body) ?: continue

                // Construct event JSON
                val eventObj = JSONObject().apply {
                    put("tag", p.tag.toInt() and 0xff)
                    put("timestamp", ts)
                    put("name", OuraFfiBridge.getEventName(p.tag))
                    put("decoded", JSONObject(decodedJson))
                }
                newEvents.add(eventObj.toString())
            }

            bytesLeft = batch.bytesLeft
            val nextCursor = (maxTs + 1).toInt()
            if (nextCursor > cursor) {
                cursor = nextCursor
            } else {
                break // Prevents infinite loop if cursor is not advancing
            }
        }

        // Save new events to oura_history.json
        if (newEvents.isNotEmpty()) {
            saveEventsToFile(newEvents)
            // Save updated cursor
            sharedPrefs.edit().putInt("sync_cursor", cursor).apply()

            // Push to Health Connect
            _syncProgress.value = "Pushing to Health Connect..."
            HealthConnectManager.syncEventsToHealthConnect(this, newEvents)
        }

        _syncProgress.value = null
        Log.d(TAG, "Sync complete. Next cursor: $cursor")
    }

    private suspend fun getEventBatch(start: Int): EventBatch {
        val evs = mutableListOf<Packet>()
        var bytesLeft = 0
        var finished = false
        val job = Job()

        val listener = { p: Packet ->
            if (p.tag == 0x11.toByte()) {
                if (p.payload.size >= 6) {
                    bytesLeft = ((p.payload[2].toInt() and 0xff) or
                                 ((p.payload[3].toInt() and 0xff) shl 8) or
                                 ((p.payload[4].toInt() and 0xff) shl 16) or
                                 ((p.payload[5].toInt() and 0xff) shl 24))
                }
                finished = true
                job.complete()
                true
            } else if (p.tag >= OuraGATT.HISTORY_EVENT_PREFIX) {
                evs.add(p)
                false
            } else {
                false
            }
        }

        synchronized(responseListeners) {
            responseListeners.add(listener)
        }

        // Request events
        writeRaw(Req.getEvent(start, 255.toByte(), -1))

        try {
            kotlinx.coroutines.withTimeout(2000) {
                job.join()
            }
        } catch (e: Exception) {
            synchronized(responseListeners) {
                responseListeners.remove(listener)
            }
        }

        return EventBatch(evs, bytesLeft)
    }

    private fun saveEventsToFile(eventsJson: List<String>) {
        try {
            val file = File(filesDir, "oura_history.json")
            val existingEvents = mutableListOf<String>()

            // Load existing
            if (file.exists()) {
                val content = file.readText()
                val jsonArray = JSONArray(content)
                for (i in 0 until jsonArray.length()) {
                    existingEvents.add(jsonArray.getString(i))
                }
            }

            // Append new
            existingEvents.addAll(eventsJson)

            // Truncate to last 100,000 to prevent infinite file size growth
            val targetList = if (existingEvents.size > 100000) {
                existingEvents.takeLast(100000)
            } else {
                existingEvents
            }

            val finalArray = JSONArray(targetList)
            FileWriter(file).use { writer ->
                writer.write(finalArray.toString())
            }
            Log.d(TAG, "Saved ${eventsJson.size} events. Total cached: ${targetList.size}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save events to file: ${e.message}", e)
        }
    }

    private data class EventBatch(
        val events: List<Packet>,
        val bytesLeft: Int
    )

    /**
     * Write raw bytes to the write characteristic.
     */
    private fun writeRaw(data: ByteArray) {
        val gatt = bluetoothGatt ?: return
        val char = writeCharacteristic ?: return
        char.value = data
        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        gatt.writeCharacteristic(char)
    }

    /**
     * Reassemble fragmented packets from notifications.
     */
    private fun handleInboundNotification(data: ByteArray) {
        for (b in data) {
            notificationBuffer.add(b)
        }

        if (notificationBuffer.size >= 2) {
            expectedLength = notificationBuffer[1].toInt() and 0xff
            val totalExpected = 2 + expectedLength

            if (notificationBuffer.size >= totalExpected) {
                val fullPacketBytes = notificationBuffer.take(totalExpected).toByteArray()
                // Clear parsed bytes from buffer
                for (i in 0 until totalExpected) {
                    notificationBuffer.removeAt(0)
                }

                val packet = Packet.parse(fullPacketBytes)
                if (packet != null) {
                    Log.d(TAG, "Parsed packet: tag=${packet.tag}, extTag=${packet.extTag}")
                    // Dispatch to transactional listeners
                    synchronized(responseListeners) {
                        val iterator = responseListeners.iterator()
                        while (iterator.hasNext()) {
                            val listener = iterator.next()
                            if (listener(packet)) {
                                iterator.remove()
                            }
                        }
                    }

                    // Process stream packets (e.g. ACM Live Data)
                    handleStreamPacket(packet)
                }
            }
        }
    }

    private fun handleStreamPacket(packet: Packet) {
        if (packet.tag == OuraGATT.REALTIME_ACM_RESPONSE_TAG) {
            // Reconstruct G-force or BPM if live HR notifications arrive
            val p = packet.payload
            if (p.size >= 10 && p[0] == 0x20.toByte()) { // ACM data
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

    private suspend fun waitForPacket(timeoutMs: Long = 3000, condition: (Packet) -> Boolean): Packet? {
        var result: Packet? = null
        val job = Job()
        val listener = { packet: Packet ->
            if (condition(packet)) {
                result = packet
                job.complete()
                true
            } else {
                false
            }
        }

        synchronized(responseListeners) {
            responseListeners.add(listener)
        }

        try {
            kotlinx.coroutines.withTimeout(timeoutMs) {
                job.join()
            }
        } catch (e: Exception) {
            synchronized(responseListeners) {
                responseListeners.remove(listener)
            }
        }
        return result
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

    override fun onDestroy() {
        disconnect()
        super.onDestroy()
    }
}
