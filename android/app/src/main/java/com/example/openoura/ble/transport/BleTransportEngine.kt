package com.example.openoura.ble.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.openoura.ble.ConnectionState
import com.example.openoura.ble.OuraGATT
import com.example.openoura.ble.Packet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Manages raw BLE connection lifecycles, service discoveries, notification descriptors,
 * and pipelines raw incoming fragments into the PacketReassembler sequential queue.
 */
@SuppressLint("MissingPermission")
class BleTransportEngine(
    private val context: Context,
    private val bluetoothAdapter: BluetoothAdapter,
    private val scope: CoroutineScope,
    private val onStateChange: (ConnectionState, String) -> Unit,
    private val onDescriptorEnabled: (isPairing: Boolean) -> Unit
) {
    private companion object {
        private const val TAG = "BleTransportEngine"
    }

    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null

    val inboundPackets = Channel<Packet>(Channel.UNLIMITED)
    private val reassembler = PacketReassembler()

    @Volatile
    var isPairingFlow = false

    @Volatile
    private var isConnecting = false

    private fun updateInternalState(state: ConnectionState, reason: String) {
        onStateChange(state, reason)
        if (state == ConnectionState.Idle || state is ConnectionState.Failed) {
            isConnecting = false
        }
    }

    @Synchronized
    fun connect(macAddress: String, isPairing: Boolean) {
        val sanitizedMac = macAddress.uppercase()
        
        // We allow connection if we are not already in the middle of a physical connection attempt
        if (isConnecting) {
            Log.w(TAG, "Ignoring connect request: already connecting to a device")
            return
        }

        isConnecting = true
        isPairingFlow = isPairing
        reassembler.clear()

        val device = bluetoothAdapter.getRemoteDevice(sanitizedMac)
        updateInternalState(ConnectionState.Connecting, "Transport connect invoked for $sanitizedMac")

        Handler(Looper.getMainLooper()).post {
            bluetoothGatt = device.connectGatt(
                context,
                false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE
            )
        }
    }

    @Synchronized
    fun disconnect() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        writeCharacteristic = null
        isConnecting = false
        updateInternalState(ConnectionState.Idle, "Transport disconnect invoked")
    }

    fun writeRaw(data: ByteArray): Boolean {
        val gatt = bluetoothGatt
        val char = writeCharacteristic
        if (gatt == null || char == null) {
            Log.e(TAG, "Cannot write: gatt or writeCharacteristic is null")
            return false
        }
        char.value = data
        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        return gatt.writeCharacteristic(char)
    }

    private val responseListeners = mutableListOf<(Packet) -> Boolean>()

    fun registerResponseListener(listener: (Packet) -> Boolean) {
        synchronized(responseListeners) {
            responseListeners.add(listener)
        }
    }

    fun unregisterResponseListener(listener: (Packet) -> Boolean) {
        synchronized(responseListeners) {
            responseListeners.remove(listener)
        }
    }

    suspend fun waitForPacket(timeoutMs: Long = 3000, filter: (Packet) -> Boolean): Packet? {
        val channel = Channel<Packet>(1)
        val listener = { packet: Packet ->
            if (filter(packet)) {
                channel.trySend(packet)
                true
            } else {
                false
            }
        }
        registerResponseListener(listener)
        return try {
            kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
                channel.receive()
            }
        } finally {
            unregisterResponseListener(listener)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                val errorCode = "GATT Error $status"
                Log.e(TAG, errorCode)
                updateInternalState(ConnectionState.Failed(errorCode), "GATT status failed")
                disconnect()
                return
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "GATT connected, discovering services...")
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "GATT disconnected")
                updateInternalState(ConnectionState.Idle, "GATT disconnected profile state")
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

                        val descriptor = notifyChar.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                        descriptor?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(descriptor)
                    } else {
                        Log.e(TAG, "Oura characteristics not found")
                        updateInternalState(ConnectionState.Failed("Unsupported ring firmware"), "Missing GATT characteristics")
                    }
                } else {
                    Log.e(TAG, "Oura GATT service not found")
                    updateInternalState(ConnectionState.Failed("Not an Oura ring"), "Missing Service UUID")
                }
            } else {
                Log.e(TAG, "Service discovery failed with status $status")
                updateInternalState(ConnectionState.Failed("Discovery failed"), "GATT discovery failed status $status")
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Notification descriptor enabled, triggering handshake callback...")
                scope.launch {
                    onDescriptorEnabled(isPairingFlow)
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            val packets = reassembler.feed(value.clone())
            for (packet in packets) {
                inboundPackets.trySend(packet)

                synchronized(responseListeners) {
                    val iterator = responseListeners.iterator()
                    while (iterator.hasNext()) {
                        val listener = iterator.next()
                        if (listener(packet)) {
                            iterator.remove()
                        }
                    }
                }
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Failed to write characteristic: status $status")
            }
        }
    }
}
