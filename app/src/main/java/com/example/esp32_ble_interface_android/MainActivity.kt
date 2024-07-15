package com.example.esp32_ble_interface_android

import android.Manifest
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
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
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
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
import android.widget.ScrollView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import android.widget.CheckBox
import android.widget.LinearLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var dialog: Dialog? = null
    private var dialogOptions: Dialog? = null
    private val mainScope = CoroutineScope(Dispatchers.Main)
    private val stateChangeDebounceDelay = 300L // 300 milliseconds debounce delay for connection dialog

    //UI
    private lateinit var brightnessSliderText: TextView
    private var checkedItemsLedMode= arrayOf(false,false)

    //BLE
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
    private val esp32Address = "B0:B2:1C:F8:98:0E" //THE ESP PRINTS THIS ON BOOT TO SERIAL. IF YOUR SERIAL IS BUGGY THEN USE A BLE APP LIKE LIGHT-BLUE TO FIND THE ADDRESS
    private val authKey = "your_auth_key" // Replace with your actual auth key
    private val serviceUUID = UUID.fromString("35e2384d-09ba-40ec-8cc2-a491e7bcd763")
    private val authCharacteristicUUID = UUID.fromString("e58b4b34-daa6-4a79-8a4c-50d63e6e767f")
    private val writeCharacteristicUUID = UUID.fromString("9d5cb5f2-5eb2-4b7c-a5d4-21e61c9c6f36")
    private val notifyCharacteristicUUID = UUID.fromString("a9248655-7f1b-4e18-bf36-ad1ee859983f")


    // ON STARTUP===============================================================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            ?: throw Exception("Bluetooth is not supported by this device")

        // configure scrollview, sliders etc
        configureScrollsETC()


        showDialog()
        setDialogState(1)
        startScanning()
    }


    // APP EXIT/ENTER===========================================================================================================

    override fun onPause() {
        super.onPause()
        // Save the current state
    }

    override fun onResume() {
        super.onResume()
        // Restore the saved state
    }


    // BUTTON CLICKS============================================================================================================


    fun ledModeStaticButton(view: View)
    {
        writeCharacteristic("03000")
    }

    fun ledModeBlinkButton(view: View)
    {
        writeCharacteristic("03001")
    }

    fun ledSwitchToggle(view: View)
    {
        if(binding.ledSwitchToggle.isChecked)
        {
            writeCharacteristic("01001") // 01=led 1=ON
        }
        else
        {
            writeCharacteristic("01000") // 01=led 0=OFF
        }
    }
    fun ledSwitchToggleExpand(view: View)
    {
        if(!binding.ledSwitchExpandedLayout.isVisible)
        {
            binding.ledSwitchExpandedLayout.isVisible = true
        }
        else
        {
            binding.ledSwitchExpandedLayout.isVisible = false
        }
    }

    fun ledSwitchModeSelect(view: View)
    {

       // dialogOptions?.show()
        val options = arrayOf("Static", "Blinking")

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(62, 36, 62, 0) // Add padding left and right
        }

        options.forEachIndexed { index, option ->
            val checkBox = CheckBox(this).apply {
                text = option
                isChecked = checkedItemsLedMode[index]
                buttonDrawable = null // Clear default checkbox drawable
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.material_alert_dialog_checkboxes)
                setPaddingRelative(90, 30, 26, 30) // Adjust padding as needed
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 17f)

                setOnCheckedChangeListener { _, isChecked ->
                    checkedItemsLedMode[index] = isChecked // Update state array
                    if (isChecked) // Perform action when checkbox is checked
                    {
                        when (index) {
                            0 -> {
                                checkedItemsLedMode = arrayOf(true, false)
                                binding.ledmodeValue.text = "Static"
                                writeCharacteristic("03000")
                            }
                            1 -> {
                                checkedItemsLedMode = arrayOf(false, true)
                                binding.ledmodeValue.text = "Blinking"
                                writeCharacteristic("03001")
                            }
                        }
                        // Dismiss the dialog after setting the checkbox
                        dialog?.dismiss()
                    }
                    else // Perform action when checkbox is unchecked
                    {
                        when (index) {
                            0 -> {
                                checkedItemsLedMode = arrayOf(true, false)
                                binding.ledmodeValue.text = "Static"
                                writeCharacteristic("03000")
                            }
                            1 -> {
                                checkedItemsLedMode = arrayOf(false, true)
                                binding.ledmodeValue.text = "Blinking"
                                writeCharacteristic("03001")
                            }
                        }
                        // Dismiss the dialog after setting the checkbox
                        dialog?.dismiss()
                    }
                }

                layout.addView(this)
            }
        }

        dialog = MaterialAlertDialogBuilder(this, R.style.DialogStyleBuilder)
            .setTitle("Led mode")
            .setView(layout)
            .setNegativeButton("Cancel") { dialog, which ->
                dialog.dismiss()
            }
            .show()


    }

    fun clickReload(view: View) {
        writeCharacteristic("99002") //ASKS THE ESP TO SEND ITS CURRENT CONFIGURATION/TOGGLES/DATA
    }

    fun clickSave(view: View) {
        writeCharacteristic("99001") //send command to esp to save config
    }


    // UI WORKINGS==============================================================================================================

    private fun configureScrollsETC()
    {
        //top header color when scrolling down
        val scrollView = findViewById<ScrollView>(R.id.ContentScrollView)
        val constraintLayout = findViewById<ConstraintLayout>(R.id.topHeaderConstraintLayout)

        val startColor = ContextCompat.getColor(this, R.color.AppBackgroundColor)
        val endColor = ContextCompat.getColor(this, R.color.ColorHeader)
        val threshold = 0 // adjust as needed for when the color change should start
        var currentColor = startColor

        // Define durations for each transition
        val fadeInDuration = 150L // duration for start to end color
        val fadeOutDuration = 300L // duration for end to start color

        // Define fade-in animation
        val fadeInAnimation = ValueAnimator.ofObject(ArgbEvaluator(), startColor, endColor).apply {
            duration = fadeInDuration
            addUpdateListener { animator ->
                val animatedValue = animator.animatedValue as Int
                constraintLayout.setBackgroundColor(animatedValue)
                window.statusBarColor = animatedValue
            }
        }

        // Define fade-out animation
        val fadeOutAnimation = ValueAnimator.ofObject(ArgbEvaluator(), endColor, startColor).apply {
            duration = fadeOutDuration
            addUpdateListener { animator ->
                val animatedValue = animator.animatedValue as Int
                constraintLayout.setBackgroundColor(animatedValue)
                window.statusBarColor = animatedValue
            }
        }

        scrollView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            val targetColor = if (scrollY > threshold) endColor else startColor

            if (currentColor != targetColor) {
                if (targetColor == endColor) {
                    fadeInAnimation.start()
                } else {
                    fadeOutAnimation.start()
                }
                currentColor = targetColor
            }
        }

        //Brightness Slider
        val BrighnessSlider: Slider = findViewById(R.id.seekBarBrightness)
        brightnessSliderText = findViewById(R.id.brightnessValue)

        BrighnessSlider.addOnChangeListener { slider, value, fromUser ->
            if (fromUser)
            { brightnessSliderText.text = convertToPercentage(binding.seekBarBrightness.value.toInt(), 255) }
        }

        BrighnessSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                // Handle the start of the slider adjustment
                //brightnessSliderText.text = "Started tracking touch"
            }

            override fun onStopTrackingTouch(slider: Slider) {
                // Handle the end of the slider adjustment
                //brightnessSliderText.text = "Stopped tracking touch"
                writeCharacteristic("02"+formatStringAsState(slider.value))
                brightnessSliderText.text = convertToPercentage(binding.seekBarBrightness.value.toInt(), 255)
            }
        })

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

    fun formatStringAsState(number: Float): String {
        val intPart = number.toInt() // Convert to integer to discard the decimal part
        require(intPart in 1..255) { "Number must be between 1 and 255" }
        return "%03d".format(intPart)
    }

    fun convertToPercentage(value: Int, maxValue: Int): String {
        require(value in 1..maxValue) { "Value must be between 1 and $maxValue" }
        require(maxValue > 0) { "Max value must be greater than 0" }

        // Convert the value to a percentage (1-100%)
        val percentage = (value / maxValue.toDouble() * 100).toInt()

        return "$percentage%"
    }

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
            writeCharacteristic("99002") //ASKS THE ESP TO SEND ITS CURRENT CONFIGURATION/TOGGLES/DATA
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
            val command = encodedNumber / 1000 // Extract first two digits (command)   EXAMPLE: 01005:   command = 01 = 1,   state= 005 = 5
            val state = encodedNumber % 1000 // Extract last three digit   (state)     EXAMPLE: 55222:   command = 55 = 55,  state= 222 = 222
            actOnData(command, state)
        } catch (e: NumberFormatException) {
            // Handle exception if the received data is not a valid number
            Log.d("BLE_NOTIFY", "Received data is not a valid string: $data")
        }
    }

    private fun actOnData(command: Int, state: Int) {
        Log.d("BLE_NOTIFY", "command: $command state: $state")

        runOnUiThread()
        {
            return@runOnUiThread when (command) {
                1 -> // LED
                {
                    when (state) {
                        0 -> binding.ledSwitchToggle.isChecked = false;
                        1 -> binding.ledSwitchToggle.isChecked = true;
                        else -> {}
                    }
                }
                2 -> // LED POWER (PWM)
                {
                    binding.seekBarBrightness.value = state.toFloat()
                    brightnessSliderText.text = convertToPercentage(binding.seekBarBrightness.value.toInt(), 255)
                }
                3 -> // LED MODE (STATIC/BLINK)
                {
                    if(state == 0)
                    {
                        checkedItemsLedMode= arrayOf(true,false)
                        binding.ledmodeValue.text = buildString {append("Static")}
                    }
                    else
                    {
                        checkedItemsLedMode= arrayOf(false,true)
                        binding.ledmodeValue.text = buildString {append("Blinking")}
                    }
                }


                99 -> {
                    when (state)
                    {
                        2 -> {
                            if (firstSyncWithEsp)
                                firstSyncWithEsp = false
                            else
                                runOnUiThread { Toast.makeText(this, "Synced with esp", Toast.LENGTH_SHORT).show() }
                        }
                        1 -> {
                            runOnUiThread { Toast.makeText( this, "Saved state as default", Toast.LENGTH_SHORT).show() }
                        }
                        else -> {}
                    }
                }
                else -> {}
            }
        }
    }



}
