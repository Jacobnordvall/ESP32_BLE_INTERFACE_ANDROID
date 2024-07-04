package com.example.esp32_ble_interface_android

import android.Manifest
import android.app.Dialog
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.ParcelUuid
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.example.esp32_ble_interface_android.databinding.ActivityMainBinding
import java.util.UUID
import kotlinx.coroutines.*
import android.text.Html

@Suppress("DEPRECATION")
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var dialog: Dialog? = null
    private val mainScope = CoroutineScope(Dispatchers.Main)

    private lateinit var bluetoothManager: BluetoothManager
    private val scanner: BluetoothLeScanner get() = bluetoothManager.adapter.bluetoothLeScanner
    private var selectedDevice: BluetoothDevice? = null
    private var gatt: BluetoothGatt? = null
    private var services: List<BluetoothGattService> = emptyList()
    private var isScanning = false

    //DEBUG CONNECTION CODES
    private val gattConnTimeout = 0x08
    private val gattConnTerminatePeerUser = 0x13
    private val gattConnFailEstablish = 0x3E
    private val gattConnL2CFailure = 0x22

    //CONFIGURE THESE TO MATCH THE ESP32 ONES
    private val authKey = "your_auth_key" // Replace with your actual auth key
    private val serviceUUID = UUID.fromString("35e2384d-09ba-40ec-8cc2-a491e7bcd763")
    private val authCharacteristicUUID = UUID.fromString("e58b4b34-daa6-4a79-8a4c-50d63e6e767f")
    private val writeCharacteristicUUID = UUID.fromString("9d5cb5f2-5eb2-4b7c-a5d4-21e61c9c6f36")
    private val notifyCharacteristicUUID = UUID.fromString("a9248655-7f1b-4e18-bf36-ad1ee859983f")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Example of a call to a native method
        binding.sampleText.text = stringFromJNI()

        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            ?: throw Exception("Bluetooth is not supported by this device")

        showDialog()
        setDialogState(1)
        startScanning()
    }

    override fun onPause() {
        super.onPause()
        // Save the current state
    }

    override fun onResume() {
        super.onResume()
        // Restore the saved state
    }

    /**
     * A native method that is implemented by the 'esp32_ble_interface_android' native library,
     * which is packaged with this application.
     */
    private external fun stringFromJNI(): String

    companion object {
        // Used to load the 'esp32_ble_interface_android' library on application startup.
        init {
            System.loadLibrary("esp32_ble_interface_android")
        }
    }

    // BUTTON CLICKS============================================================================================================

    fun clickOff(view: View) {
        writeCharacteristic("0")
    }

    fun clickOn(view: View) {
        writeCharacteristic("1")
    }

    fun clickReload(view: View) {
        // Hard reload lol (this should be changed though... just reload the whole Bluetooth...)
        val ctx = applicationContext
        val pm = ctx.packageManager
        val intent = pm.getLaunchIntentForPackage(ctx.packageName)
        val mainIntent = Intent.makeRestartActivityTask(intent!!.component)
        ctx.startActivity(mainIntent)
        Runtime.getRuntime().exit(0)
    }

    fun clickSave(view: View) {
        Toast.makeText(this, "Clicked Save!", Toast.LENGTH_SHORT).show()
    }

    // NAVIGATION===============================================================================================================

    private fun goBackToPermissionPage() {
        val intent = Intent(this, PermissionScreen::class.java)
        startActivity(intent)
        finish()
    }


    // CONNECTION DIALOG========================================================================================================

    private fun showDialog() {
        Log.d("Dialog", "showDialog() called")
        if (dialog?.isShowing == true) {
            Log.d("Dialog", "Dialog is already showing")
            return
        }

        dialog = Dialog(this, R.style.DialogStyle).apply {
            setContentView(R.layout.ble_connection_status_dialog)
            window?.setBackgroundDrawableResource(R.drawable.ble_conncection_dialog_background)
            setCancelable(false)
        }

        dialog?.show()
        Log.d("Dialog", "Dialog shown")
    }

    private fun dismissDialog() {
        dialog?.dismiss()
    }

    private var connectingDotsJob: Job? = null

    private fun setDialogState(state: Int) {
        val dialogText: TextView? = dialog?.findViewById(R.id.dialogText)

        dialogText?.let {
            val baseText = "<b>CONNECTING TO DEVICE</b><br>"
            val additionalText = when (state) {
                1 -> "Device found: ✕<br>Device Connected: ✕"
                2 -> "Device found: ✓<br>Device Connected: ✕"
                3 -> "Device found: ✓<br>Device Connected: ✓"
                else -> "Device found: ✕<br>Device Connected: ✕"
            }

            // Start coroutine to update dots every 500ms
            connectingDotsJob?.cancel()  // Cancel any existing job
            connectingDotsJob = mainScope.launch {
                while (isActive) { // Continue while coroutine is active
                    var dotsCount = 0
                    while (dotsCount < 8) {
                        delay(120)
                        val dots = when (dotsCount) {
                            1 -> ".."
                            2 -> "..."
                            3 -> "...."
                            4 -> "...."
                            5 -> "...."
                            6 -> "...."
                            7 -> "...."
                            else -> "."
                        }
                        it.text = Html.fromHtml("$baseText$dots<br>$additionalText", Html.FROM_HTML_MODE_LEGACY)
                        dotsCount++
                    }
                }
            }
        }
    }

    // HELPER FUNCTIONS=========================================================================================================

    fun String.decodeHex(): String {
        require(length % 2 == 0) { "Must have an even length" }
        return chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
            .toString(Charsets.ISO_8859_1)  // Or whichever encoding your input uses
    }

    private fun ByteArray.toHexString() = joinToString("") { "%02x".format(it) }

    // BLUETOOTH================================================================================================================

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            super.onScanResult(callbackType, result)

            result?.let {
                val scanRecord = it.scanRecord
                val serviceUuids = scanRecord?.serviceUuids

                // Check if the scanned device advertises the desired service UUID
                if (serviceUuids?.any { uuid -> uuid.uuid == serviceUUID } == true) {
                    selectedDevice = it.device
                    Log.d("BLE_SCAN", "Selected device: ${it.device.address}")
                    setDialogState(2) // Device found
                    connect()
                    stopScanning()
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            Log.e("BLE_SCAN", "Scan failed with error code: $errorCode")
        }
    }

    private fun startScanning() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            goBackToPermissionPage()
            return
        }
        if (!isScanning) {
            val filter = ScanFilter.Builder()
                .setServiceUuid(ParcelUuid.fromString(serviceUUID.toString()))
                .build()

            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            scanner.startScan(listOf(filter), settings, scanCallback)
            isScanning = true
            Log.d("BLE_SCAN", "Scan started")
        } else {
            Log.w("BLE_SCAN", "Scan already started")
        }
    }

    private fun stopScanning() {
        Log.d("BLE_SCAN", "Stopping scan...")
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            goBackToPermissionPage()
            return
        }
        if (isScanning) {
            scanner.stopScan(scanCallback)
            isScanning = false
            Log.d("BLE_SCAN", "Scan stopped")
        } else {
            Log.w("BLE_SCAN", "No scan to stop")
        }
    }

    private fun refreshDeviceCache(gatt: BluetoothGatt): Boolean {
        try
        {
            val method = gatt.javaClass.getMethod("refresh")
            return method.invoke(gatt) as Boolean
        } catch (exception: Exception)
        {
            Log.e("BLE_SCAN", "Exception occurred while refreshing device: ${exception.message}")
        }
        return false
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)

            runOnUiThread {
                Log.d("BLE_SCAN", "Connection State Change: Status - $status, New State - $newState")
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.e("BLE_SCAN", "Error on connecting: Status - $status")
                    handleConnectionError(status)
                    refreshDeviceCache(gatt)
                    if (ActivityCompat.checkSelfPermission(AppCompatActivity(), Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                        goBackToPermissionPage()
                        return@runOnUiThread
                    }
                    gatt.close()
                    setDialogState(1)
                    if (dialog?.isShowing == false) {
                        dialog!!.show()
                    }
                    startScanning()
                    return@runOnUiThread
                }
                if (newState == BluetoothGatt.STATE_CONNECTED) {
                    Log.d("BLE_SCAN", "Connected...")
                    if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                        goBackToPermissionPage()
                        return@runOnUiThread
                    }
                    setDialogState(2)
                    this@MainActivity.gatt = gatt // Assign to member variable
                    gatt.discoverServices()
                }
                if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                    Log.d("BLE_SCAN", "Disconnected...")
                    setDialogState(1)
                    if (dialog?.isShowing == false) {
                        dialog!!.show()
                    }
                    this@MainActivity.gatt?.close() // Close the GATT connection
                    this@MainActivity.gatt = null // Assign to member variable
                    startScanning()
                }
            }
        }

        private fun handleConnectionError(status: Int) {
            when (status) {
                gattConnTimeout -> Log.e("BLE_SCAN", "Connection Timeout")
                gattConnTerminatePeerUser -> Log.e("BLE_SCAN", "Connection Terminated by Peer User")
                gattConnFailEstablish -> Log.e("BLE_SCAN", "Connection Failed to Establish")
                gattConnL2CFailure -> Log.e("BLE_SCAN", "L2CAP Failure")
                else -> Log.e("BLE_SCAN", "Unknown Error: $status")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            super.onServicesDiscovered(gatt, status)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                services = gatt.services
                Log.d("BLE_SERVICES", "Services discovered:")
                services.forEach { service ->
                    Log.d("BLE_SERVICES", "Service UUID: ${service.uuid}")
                    service.characteristics.forEach { characteristic ->
                        Log.d("BLE_SERVICES", "  Characteristic UUID: ${characteristic.uuid}")
                        Log.d("BLE_SERVICES", "  Properties: ${characteristic.properties}")
                    }
                }

                // Authenticate by writing the key to the AUTH characteristic
                authenticateDevice(gatt)
                setDialogState(3)
            } else {
                Log.w("BLE_SERVICES", "onServicesDiscovered received: $status")
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            super.onCharacteristicChanged(gatt, characteristic)
            val data = characteristic.value.toHexString().decodeHex()
            Log.d("BLE_NOTIFY", "Notification received from ${characteristic.uuid}: $data")
            handleData(data)
        }
    }

    private fun connect() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            goBackToPermissionPage()
            return
        }
        selectedDevice?.connectGatt(this, false, callback) ?: Log.e("BLE_CONNECT", "Selected device is null")
    }

    private fun enableNotifications(gatt: BluetoothGatt, characteristicUUID: UUID) {
        val characteristic = gatt.services.flatMap { it.characteristics }
            .find { it.uuid == characteristicUUID }
        if (characteristic != null) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                goBackToPermissionPage()
                return
            }
            gatt.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
            descriptor?.let {
                it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(it)
                Log.d("BLE_NOTIFY", "Notifications enabled for characteristic: $characteristicUUID")
            }
        } else {
            Log.w("BLE_NOTIFY", "Characteristic $characteristicUUID not found")
        }
    }

    private fun authenticateDevice(gatt: BluetoothGatt) {
        val authCharacteristic = gatt.getService(serviceUUID)?.getCharacteristic(authCharacteristicUUID)
        if (authCharacteristic != null) {
            authCharacteristic.value = authKey.toByteArray()
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                goBackToPermissionPage()
                return
            }
            val success = gatt.writeCharacteristic(authCharacteristic)
            Log.d("BLE_AUTH", "Authentication key written to characteristic")

            if (success) {
                CoroutineScope(Dispatchers.Main).launch {
                    delay(500) // Delay to ensure the write operation completes
                    startEnablingNotifications()
                }
            } else {
                Log.e("BLE_AUTH", "Failed to write authentication characteristic")
            }
        } else {
            Log.e("BLE_AUTH", "Authentication characteristic not found")
        }
    }

    private fun startEnablingNotifications() {
        // Assuming you know the UUID of the characteristic you want to enable notifications for
        gatt?.let { enableNotifications(it, notifyCharacteristicUUID) }
        dismissDialog()
    }

    // WRITING DATA ==============================================================================================================

    private fun writeCharacteristic(data: String) {
        // Check if BluetoothGatt is null or not connected
        if (gatt == null || gatt?.services.isNullOrEmpty()) {
            Log.e("BLE_WRITE", "Gatt connection not established or services not discovered")
            return
        }

        // Find the service by UUID
        val service = gatt!!.getService(serviceUUID)
        if (service == null) {
            Log.e("BLE_WRITE", "Service not found: $serviceUUID")
            return
        }

        // Find the characteristic by UUID
        val characteristic = service.getCharacteristic(writeCharacteristicUUID)
        if (characteristic == null) {
            Log.e("BLE_WRITE", "Characteristic not found: $writeCharacteristicUUID")
            return
        }

        // First, set the new value to our local copy of the characteristic
        val dataToSend = data.toByteArray() // Replace with your actual data to send
        characteristic.value = dataToSend

        // Then, send the updated characteristic to the device
        val success = if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            goBackToPermissionPage()
            return
        } else {
            gatt!!.writeCharacteristic(characteristic)
        }

        if (!success) {
            Log.e("BLE_WRITE", "Failed to write characteristic")
        } else {
            Log.d("BLE_WRITE", "Characteristic written successfully")
        }
    }

    // HANDLE DATA ==============================================================================================================

    fun handleData(data: String) {
        // Data is already decoded and turned into a string
        binding.TextForDebug.text = buildString { append("Received: "); append(data) }
    }
}
