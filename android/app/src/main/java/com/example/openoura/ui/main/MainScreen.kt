package com.example.openoura.ui.main

import android.Manifest
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import com.example.openoura.ble.ConnectionState
import com.example.openoura.health.HealthConnectManager
import com.example.openoura.MainActivity
import kotlinx.coroutines.launch
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onItemClick: (NavKey) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MainScreenViewModel = viewModel()
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Service flows
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val metadata by viewModel.deviceMetadata.collectAsStateWithLifecycle()
    val syncProgress by viewModel.syncProgress.collectAsStateWithLifecycle()
    val diagnosticLogs by viewModel.diagnosticLogs.collectAsStateWithLifecycle()
    val eventHistory by viewModel.decodedEventHistory.collectAsStateWithLifecycle()

    // Scan flows
    val isScanning by viewModel.isScanning.collectAsStateWithLifecycle()
    val discoveredDevices by viewModel.discoveredDevices.collectAsStateWithLifecycle()

    // Permission states
    var hasHealthConnectPermissions by remember { mutableStateOf(false) }

    // Dialog state
    var showScanDialog by remember { mutableStateOf(false) }
    var showManualInputFields by remember { mutableStateOf(false) }

    // Manual input fields
    var macInput by remember { mutableStateOf(TextFieldValue(viewModel.getSavedMacAddress() ?: "")) }
    var keyInput by remember { mutableStateOf(TextFieldValue(viewModel.getSavedKeyHex() ?: "")) }

    // Bluetooth permission launchers
    val bluetoothPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            viewModel.startScan()
            showScanDialog = true
        } else {
            Toast.makeText(context, "Bluetooth permissions required to scan", Toast.LENGTH_SHORT).show()
        }
    }

    // Health Connect permissions launcher
    val requestPermissionContract = PermissionController.createRequestPermissionResultContract()
    val healthPermissionLauncher = rememberLauncherForActivityResult(requestPermissionContract) { granted ->
        hasHealthConnectPermissions = granted.containsAll(HealthConnectManager.REQUIRED_PERMISSIONS)
        if (hasHealthConnectPermissions) {
            Toast.makeText(context, "Health Connect Authorized!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Permissions Denied", Toast.LENGTH_SHORT).show()
        }
    }

    // Check permissions on start
    LaunchedEffect(Unit) {
        hasHealthConnectPermissions = HealthConnectManager.hasAllPermissions(context)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // App Header
        Text(
            text = "OpenOura Dashboard",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        // Health Connect Authorization Card
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(
                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Google Health Connect",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Text(
                        text = if (hasHealthConnectPermissions) "Authorized & Linked" else "Auth Required",
                        color = if (hasHealthConnectPermissions) Color(0xFF2E7D32) else Color(0xFFC62828),
                        fontSize = 14.sp
                    )
                }

                Button(
                    onClick = {
                        coroutineScope.launch {
                            healthPermissionLauncher.launch(HealthConnectManager.REQUIRED_PERMISSIONS)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (hasHealthConnectPermissions) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(if (hasHealthConnectPermissions) "Re-auth" else "Link")
                }
            }
        }

        // Connection Status Card
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        ) {
            Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                Text(
                    text = "Connection Status",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start
                ) {
                    val statusText = when (connectionState) {
                        ConnectionState.Idle -> "Disconnected"
                        ConnectionState.Scanning -> "Scanning..."
                        ConnectionState.Connecting -> "Connecting..."
                        ConnectionState.Authenticating -> "Authenticating..."
                        ConnectionState.Ready -> "Ready & Connected"
                        is ConnectionState.Failed -> "Connection Failed"
                    }
                    val statusColor = when (connectionState) {
                        ConnectionState.Ready -> Color(0xFF2E7D32)
                        ConnectionState.Idle -> Color.Gray
                        is ConnectionState.Failed -> Color(0xFFC62828)
                        else -> Color(0xFFE65100)
                    }

                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(statusColor)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = statusText, fontWeight = FontWeight.Medium)
                }

                if (connectionState is ConnectionState.Failed) {
                    Text(
                        text = (connectionState as ConnectionState.Failed).reason,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }

        // Device Metadata Card
        if (connectionState == ConnectionState.Ready) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                    Text(
                        text = "Device Information",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    metadata.firmware?.let { Text(text = "Firmware: $it", fontSize = 14.sp) }
                    metadata.serial?.let { Text(text = "Serial: $it", fontSize = 14.sp) }
                    metadata.batteryPercent?.let {
                        Text(
                            text = "Battery: $it% ${if (metadata.isCharging) "(Charging)" else ""}",
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }

        // Event History Card
        if (eventHistory.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                    Text(
                        text = "Synced Event History (${eventHistory.size} total)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    // Show the last 3 events
                    eventHistory.takeLast(3).reversed().forEach { eventStr ->
                        var displayStr = "• Raw event (malformed)"
                        try {
                            val json = JSONObject(eventStr)
                            val name = json.optString("name", "Unknown")
                            val tag = json.optInt("tag")
                            val ts = json.optLong("timestamp")
                            displayStr = "• $name (Tag: 0x${Integer.toHexString(tag)}, TS: $ts)"
                        } catch (e: Exception) {
                            // Keep default malformed text
                        }
                        Text(
                            text = displayStr,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                }
            }
        }

        // Action Buttons
        Spacer(modifier = Modifier.weight(1f))

        if (syncProgress != null) {
            Text(
                text = syncProgress ?: "",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp))
        }

        if (connectionState == ConnectionState.Ready) {
            Button(
                onClick = { viewModel.triggerSync() },
                modifier = Modifier.fillMaxWidth().height(50.dp).padding(bottom = 8.dp)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "Sync")
                Spacer(modifier = Modifier.width(8.dp))
                Text("Sync now")
            }

            OutlinedButton(
                onClick = { viewModel.disconnect() },
                modifier = Modifier.fillMaxWidth().height(50.dp).padding(bottom = 8.dp)
            ) {
                Text("Disconnect")
            }
        } else {
            val savedMac = viewModel.getSavedMacAddress()
            val hasCredentials = savedMac != null && viewModel.getSavedKeyHex() != null

            if (hasCredentials) {
                // Persistent Connection Button - Reuse existing key
                Button(
                    onClick = { viewModel.connectAndSync() },
                    modifier = Modifier.fillMaxWidth().height(50.dp).padding(bottom = 8.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Connect")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Connect to Ring")
                }

                Text(
                    text = "Saved Ring: $savedMac",
                    fontSize = 12.sp,
                    color = Color.Gray,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            }

            // Companion Device Setup - System Picker (Recommended)
            OutlinedButton(
                onClick = {
                    val activity = context as? MainActivity
                    activity?.triggerCompanionDeviceAssociation()
                },
                modifier = Modifier.fillMaxWidth().height(50.dp).padding(bottom = 8.dp)
            ) {
                Icon(Icons.Default.Build, contentDescription = "Companion Setup")
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (hasCredentials) "Repair / Change Ring" else "Companion Setup (Recommended)")
            }

            // Legacy manual scan
            OutlinedButton(
                onClick = {
                    permissionLauncher.launch(bluetoothPermissions)
                },
                modifier = Modifier.fillMaxWidth().height(50.dp).padding(bottom = 8.dp)
            ) {
                Icon(Icons.Default.Search, contentDescription = "Scan")
                Spacer(modifier = Modifier.width(8.dp))
                Text("Legacy BLE Scanner")
            }

            OutlinedButton(
                onClick = { showManualInputFields = !showManualInputFields },
                modifier = Modifier.fillMaxWidth().height(50.dp).padding(bottom = 8.dp)
            ) {
                Text(if (showManualInputFields) "Hide credentials form" else "Enter credentials manually")
            }
        }

        // Credentials form
        if (showManualInputFields && connectionState != ConnectionState.Ready) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
            ) {
                OutlinedTextField(
                    value = macInput,
                    onValueChange = { macInput = it },
                    label = { Text("Ring MAC Address") },
                    placeholder = { Text("00:11:22:33:44:55") },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                )

                OutlinedTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    label = { Text("16-Byte Auth Key (Hex)") },
                    placeholder = { Text("32 hex characters") },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                )

                Button(
                    onClick = {
                        viewModel.connectDevice(macInput.text, keyInput.text)
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Text("Connect with credentials")
                }
            }
        }

        // Diagnostics Logs Card
        if (diagnosticLogs.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .padding(vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
            ) {
                Column(modifier = Modifier.padding(8.dp).fillMaxSize()) {
                    Text(
                        text = "Diagnostics logs (Rust FFI)",
                        color = Color.Green,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        items(diagnosticLogs.reversed()) { log ->
                            Text(
                                text = log,
                                color = Color.LightGray,
                                fontSize = 10.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                modifier = Modifier.padding(bottom = 2.dp)
                            )
                        }
                    }
                }
            }
        }

        // Forget Ring Button
        if (viewModel.getSavedMacAddress() != null) {
            TextButton(
                onClick = { viewModel.forgetDevice() },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Forget Saved Ring")
            }
        }
    }

    // Scanning Dialog
    if (showScanDialog) {
        Dialog(onDismissRequest = {
            viewModel.stopScan()
            showScanDialog = false
        }) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(400.dp)
                    .padding(16.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    Text(
                        text = "Discovered Oura Rings",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    if (isScanning) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(bottom = 8.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Scanning...", fontSize = 12.sp, color = Color.Gray)
                        }
                    }

                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        items(discoveredDevices) { device ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.pairNewDevice(device.address)
                                        showScanDialog = false
                                    }
                                    .padding(vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(text = device.name, fontWeight = FontWeight.Medium)
                                    Text(text = device.address, fontSize = 12.sp, color = Color.Gray)
                                }
                                Icon(Icons.Default.ArrowForward, contentDescription = "Connect")
                            }
                            HorizontalDivider()
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = {
                            viewModel.stopScan()
                            showScanDialog = false
                        }) {
                            Text("Close")
                        }
                    }
                }
            }
        }
    }
}
