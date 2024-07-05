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

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var dialog: Dialog? = null
    private val mainScope = CoroutineScope(Dispatchers.Main)
    private val stateChangeDebounceDelay = 300L // 300 milliseconds debounce delay

    private lateinit var bluetoothManager: BluetoothManager
    private val scanner: BluetoothLeScanner get() = bluetoothManager.adapter.bluetoothLeScanner
    private var selectedDevice: BluetoothDevice? = null
    private var gatt: BluetoothGatt? = null
    private var services: List<BluetoothGattService> = emptyList()
    private var isScanning = false
    private var isConnecting = false
    private var firstSyncWithEsp = true

    //DEBUG CONNECTION CODES
    private val gattConnTimeout = 0x08
    private val gattConnTerminatePeerUser = 0x13
    private val gattConnFailEstablish = 0x3E
    private val gattConnL2CFailure = 0x22

    //CONNECTION RETRIES BEFORE RESTART APP
    private var connectionRetryCount = 0
    private val maxConnectionRetries = 4
    private val connectionRetryDelay = 2000L // 2 seconds

    //CONFIGURE THESE TO MATCH THE ESP32 ONES
    private val esp32Address = "B0:B2:1C:F8:B4:8A" //THE ESP PRINTS THIS ON BOOT TO SERIAL. IF YOUR SERIAL IS BUGGY THEN USE A BLE APP LIKE LIGHT-BLUE TO FIND THE ADDRESS
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
        writeCharacteristic("010") // 01=led 0=OFF
    }

    fun clickOn(view: View) {
        writeCharacteristic("011") // 01=led 1=ON
    }

    fun clickReload(view: View) {
        writeCharacteristic("999") //ASKS THE ESP TO SEND ITS CURRENT CONFIGURATION/TOGGLES/DATA
    }

    fun clickSave(view: View) {
        writeCharacteristic("991") //send command to esp to save config
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
                        it.text = Html.fromHtml(
                            "$baseText$dots<br>$additionalText",
                            Html.FROM_HTML_MODE_LEGACY
                        )
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

    private fun performAppRestart() {
        val ctx = applicationContext
        val pm = ctx.packageManager
        val intent = pm.getLaunchIntentForPackage(ctx.packageName)
        val mainIntent = Intent.makeRestartActivityTask(intent!!.component)
        ctx.startActivity(mainIntent)
        Runtime.getRuntime().exit(0)
    }

    private fun ByteArray.toHexString() = joinToString("") { "%02x".format(it) }

    // BLUETOOTH================================================================================================================

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            super.onScanResult(callbackType, result)

            result?.let {
                val device = it.device
                // Replace "desired_mac_address" with the actual MAC address of the device you want to connect to
                if (device.address == esp32Address && !isConnecting) {
                    selectedDevice = device
                    isConnecting = true
                    Log.d("BLE_SCAN", "Selected device: ${device.address}")
                    mainScope.launch {
                        delay(stateChangeDebounceDelay)
                        setDialogState(2) // Device found
                    }
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
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            goBackToPermissionPage()
            return
        }
        if (!isScanning) {
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            val scanner = bluetoothManager.adapter?.bluetoothLeScanner
            scanner?.startScan(null, settings, scanCallback) // Passing null instead of filters
            Log.d("BLE_SCAN", "Started scanning")
            isScanning = true
        }
    }

    private fun stopScanning() {
        Log.d("BLE_SCAN", "Stopping scan...")
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            goBackToPermissionPage()
            return
        }
        if (isScanning) {
            val scanner = bluetoothManager.adapter?.bluetoothLeScanner
            scanner?.stopScan(scanCallback)
            Log.d("BLE_SCAN", "Stopped scanning")
            isScanning = false
        }
    }

    private fun refreshDeviceCache(gatt: BluetoothGatt): Boolean {
        try {
            val method = gatt.javaClass.getMethod("refresh")
            return method.invoke(gatt) as Boolean
        } catch (exception: Exception) {
            Log.e("BLE_SCAN", "Exception occurred while refreshing device: ${exception.message}")
        }
        return false
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)

            runOnUiThread {
                Log.d(
                    "BLE_SCAN",
                    "Connection State Change: Status - $status, New State - $newState"
                )
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.e("BLE_SCAN", "Error on connecting: Status - $status")
                    handleConnectionError(status)
                    refreshDeviceCache(gatt)
                    if (ActivityCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.BLUETOOTH_CONNECT
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        goBackToPermissionPage()
                        return@runOnUiThread
                    }
                    gatt.close()
                    if (status == 133) {
                        retryConnection()
                    } else {
                        setDialogState(1) // Disconnected
                        mainScope.launch {
                            delay(stateChangeDebounceDelay)
                            if (dialog?.isShowing == false) {
                                dialog!!.show()
                            }
                        }
                        startScanning()
                    }
                    return@runOnUiThread
                }
                if (newState == BluetoothGatt.STATE_CONNECTED) {
                    Log.d("BLE_SCAN", "Connected...")
                    if (ActivityCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.BLUETOOTH_CONNECT
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        goBackToPermissionPage()
                        return@runOnUiThread
                    }
                    this@MainActivity.gatt = gatt // Assign to member variable
                    gatt.discoverServices()
                    connectionRetryCount = 0 // Reset retry count on successful connection
                }
                if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                    Log.d("BLE_SCAN", "Disconnected...")
                    setDialogState(1) // Disconnected
                    isConnecting = false // Reset the flag on disconnection
                    mainScope.launch {
                        delay(stateChangeDebounceDelay)
                        if (dialog?.isShowing == false) {
                            dialog!!.show()
                        }
                    }
                    this@MainActivity.gatt?.close() // Close the GATT connection
                    this@MainActivity.gatt = null // Assign to member variable
                    firstSyncWithEsp = true
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
                133 -> Log.e("BLE_SCAN", "GATT Error 133: Unknown Connection Error")
                else -> Log.e("BLE_SCAN", "Unknown Error: $status")
            }
        }

        private fun toggleBluetoothAdapter() {
            val bluetoothAdapter = bluetoothManager.adapter
            if (bluetoothAdapter.isEnabled) {
                if (ActivityCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    goBackToPermissionPage()
                    return
                }
                bluetoothAdapter.disable()
                mainScope.launch {
                    delay(2000) // Wait for 2 seconds before enabling again
                    bluetoothAdapter.enable()
                }
            } else {
                bluetoothAdapter.enable()
            }
        }

        private fun retryConnection() {
            if (connectionRetryCount < maxConnectionRetries) {
                connectionRetryCount++
                Log.d("BLE_SCAN", "Retrying connection... Attempt: $connectionRetryCount")
                mainScope.launch {
                    delay(connectionRetryDelay)
                    toggleBluetoothAdapter() // Toggle Bluetooth before retrying
                    delay(3000) // Additional delay to ensure the Bluetooth adapter is properly toggled
                    connect() // Reattempt to connect
                }
            } else {
                Log.e("BLE_SCAN", "Device probably has a bad ble implementation")
                performAppRestart()
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
                mainScope.launch {
                    delay(stateChangeDebounceDelay)
                    setDialogState(3) // Device connected
                }
            } else {
                Log.w("BLE_SERVICES", "onServicesDiscovered received: $status")
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            super.onCharacteristicChanged(gatt, characteristic)
            val data = characteristic.value.toHexString().decodeHex()
            Log.d("BLE_NOTIFY", "Data received from ${characteristic.uuid}: $data")
            handleData(data)
        }
    }

    private fun connect() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            goBackToPermissionPage()
            return
        }
        selectedDevice?.connectGatt(this, false, callback) ?: Log.e(
            "BLE_SCAN",
            "Selected device is null"
        )
    }

    private fun enableNotifications(gatt: BluetoothGatt, characteristicUUID: UUID) {
        val characteristic = gatt.services.flatMap { it.characteristics }
            .find { it.uuid == characteristicUUID }
        if (characteristic != null) {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                goBackToPermissionPage()
                return
            }
            gatt.setCharacteristicNotification(characteristic, true)
            val descriptor =
                characteristic.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
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
        val authCharacteristic =
            gatt.getService(serviceUUID)?.getCharacteristic(authCharacteristicUUID)
        if (authCharacteristic != null) {
            authCharacteristic.value = authKey.toByteArray()
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
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

        CoroutineScope(Dispatchers.Main).launch {
            delay(500) // Delay to ensure everything is done and ready to proceed
            writeCharacteristic("999") //ASKS THE ESP TO SEND ITS CURRENT CONFIGURATION/TOGGLES/DATA
        }
        CoroutineScope(Dispatchers.Main).launch {
            delay(700) // Delay to ensure the loading and applying of the device state is finished
            dismissDialog()
        }
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
        val success = if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
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

        try {
            val encodedNumber = data.toInt() // Convert received string to integer
            val command = encodedNumber / 10 // Extract digits (tens and hundreds place)
            val state = encodedNumber % 10 // Extract last digit (units place)
            actOnData(command, state)
        } catch (e: NumberFormatException) {
            // Handle exception if the received data is not a valid number
            Log.d("BLE_NOTIFY", "Received data is not a valid string: $data")
        }
    }

    private fun actOnData(command: Int, state: Int) {
        Log.d("BLE_NOTIFY", "command: $command state: $state")

        return when (command)
        {
            1 -> // LED
            {
                when (state) {
                    0 -> {
                        binding.button2.backgroundTintList = getColorStateList(R.color.MainColor) //ON
                        binding.button3.backgroundTintList = getColorStateList(R.color.MainColorButtonON) //OFF
                    }
                    1 -> {
                        binding.button2.backgroundTintList = getColorStateList(R.color.MainColorButtonON) //ON
                        binding.button3.backgroundTintList = getColorStateList(R.color.MainColor) //OFF
                    }
                    else -> {}
                }
            }
            99 ->
            {
                when (state) {
                    9 -> {
                        if(firstSyncWithEsp) {firstSyncWithEsp = false}
                        else { runOnUiThread { Toast.makeText(this, "Synced with esp", Toast.LENGTH_SHORT).show() } }
                    }
                    1 -> { runOnUiThread { Toast.makeText(this, "Saved state as default", Toast.LENGTH_SHORT).show() } }
                    else -> {}
                }
            }
            else -> {}


        }
    }



}
