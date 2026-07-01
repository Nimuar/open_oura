package com.example.openoura

import android.Manifest
import android.app.Activity
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityCompat
import androidx.lifecycle.ViewModelProvider
import com.example.openoura.ble.ConnectionState
import com.example.openoura.ble.OuraBleService
import com.example.openoura.theme.OpenOuraTheme
import com.example.openoura.ui.main.MainScreenViewModel
import java.util.regex.Pattern

/**
 * Main Host Activity. It orchestrates the CompanionDeviceManager association requests
 * using system picker overlays, feeding the result back into MainScreenViewModel.
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var viewModel: MainScreenViewModel

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            Log.d(TAG, "All permissions granted. Initializing service.")
            viewModel.initializeService()
        } else {
            Log.e(TAG, "Permissions denied. App functionality will be limited.")
            Toast.makeText(this, "Bluetooth & Notification permissions are required.", Toast.LENGTH_LONG).show()
        }
    }

    private val cdmLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val intentData = result.data ?: return@registerForActivityResult
            val associationInfo: AssociationInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intentData.getParcelableExtra(
                    "android.companion.extra.ASSOCIATION_INFO",
                    AssociationInfo::class.java
                )
            } else {
                @Suppress("DEPRECATION")
                intentData.getParcelableExtra("android.companion.extra.ASSOCIATION_INFO")
            }

            val mac = associationInfo?.deviceMacAddress?.toString()

            if (mac != null) {
                Log.d(TAG, "Companion associated successfully post-rebirth: $mac")

                // CRITICAL: Ensure permissions are validated before updating the VM or trigger connection
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    viewModel.onCompanionAssociated(mac)
                } else {
                    // Cache the mac explicitly in preferences so it survives if we must re-request permissions
                    getSharedPreferences("open_oura_prefs", Context.MODE_PRIVATE)
                        .edit().putString("ring_mac", mac).apply()
                    checkAndRequestPermissions()
                }
                Toast.makeText(this, "Associated ring: $mac", Toast.LENGTH_SHORT).show()
            }
        } else {
            Log.e(TAG, "Companion picker overlay returned non-OK result code: ${result.resultCode}")
            viewModel.setScanning(false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[MainScreenViewModel::class.java]

        enableEdgeToEdge()
        setContent {
            OpenOuraTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainNavigation()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Perform verification checks only when the user returns to focus
        checkAndRequestPermissions()
        recoverPostRebirthAssociation()
    }

    /**
     * Checks if a new association was created while the process was dead.
     */
    private fun recoverPostRebirthAssociation() {
        // Only attempt recovery if the app is currently Idle or Scanning.
        val state = OuraBleService.connectionState.value
        if (state != ConnectionState.Idle && state != ConnectionState.Scanning) {
            Log.d(TAG, "Skipping post-rebirth recovery: current state is $state")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val cdm = getSystemService(Context.COMPANION_DEVICE_SERVICE) as CompanionDeviceManager
            val associations = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                cdm.myAssociations.map { it.deviceMacAddress?.toString() }
            } else {
                @Suppress("DEPRECATION")
                cdm.associations
            }

            val savedMac = getSharedPreferences("open_oura_prefs", Context.MODE_PRIVATE)
                .getString("ring_mac", null)

            // If we have an OS-level association, ensure the connection flow is triggered
            associations.filterNotNull().firstOrNull()?.let { mac ->
                // If we are Idle or Scanning, and we have an association, trigger the link.
                // We don't check for 'mac != savedMac' here because we want to recover 
                // the session even if the MAC was already stored before the rebirth.
                Log.i(TAG, "Recovering association post-rebirth: $mac")
                viewModel.onCompanionAssociated(mac)
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missingPermissions = permissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            viewModel.initializeService()
        } else {
            permissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    /**
     * Launch the system-managed Companion Setup picker for Oura/Ring BLE devices.
     */
    fun triggerCompanionDeviceAssociation() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Toast.makeText(this, "Companion Setup requires Android 8.0+", Toast.LENGTH_SHORT).show()
            return
        }

        val cdm = getSystemService(Context.COMPANION_DEVICE_SERVICE) as CompanionDeviceManager
        val filter = BluetoothLeDeviceFilter.Builder()
            .setNamePattern(Pattern.compile(".*Oura.*|.*Ring.*", Pattern.CASE_INSENSITIVE))
            .build()

        val request = AssociationRequest.Builder()
            .addDeviceFilter(filter)
            .setSingleDevice(true)
            .build()

        Log.d(TAG, "Starting Companion Device Manager scanning picker...")
        viewModel.setScanning(true) // Update UI state
        cdm.associate(request, object : CompanionDeviceManager.Callback() {
            override fun onDeviceFound(intentSender: android.content.IntentSender) {
                val intentSenderRequest = IntentSenderRequest.Builder(intentSender).build()
                cdmLauncher.launch(intentSenderRequest)
            }

            override fun onFailure(error: CharSequence?) {
                Log.e(TAG, "Companion association request failed: $error")
                viewModel.setScanning(false)
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Association failed: $error", Toast.LENGTH_LONG).show()
                }
            }
        }, null)
    }
}
