package com.example.esp32_ble_interface_android

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.View
import android.widget.Toast
import com.example.esp32_ble_interface_android.databinding.ActivityMainBinding
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private lateinit var bluetoothManager: BluetoothManager
    private val scanner: BluetoothLeScanner get() = bluetoothManager.adapter.bluetoothLeScanner
    private var selectedDevice: BluetoothDevice? = null
    private var gatt: BluetoothGatt? = null
    private var services: List<BluetoothGattService> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?)
    {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Example of a call to a native method
        binding.sampleText.text = stringFromJNI()

        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            ?: throw Exception("Bluetooth is not supported by this device")

        startScanning()
    }

    /**
     * A native method that is implemented by the 'esp32_ble_interface_android' native library,
     * which is packaged with this application.
     */
    external fun stringFromJNI(): String

    companion object {
        // Used to load the 'esp32_ble_interface_android' library on application startup.
        init {
            System.loadLibrary("esp32_ble_interface_android")
        }
    }

    //BUTTON CLICKS============================================================================================================

    //MAKES TOAST POPUPS FOR EACH BUTTON PRESS.
    private fun showToast(message: String)
    { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }

    fun clickWrite(view: View)
    { showToast("Clicked Write!") }

    fun clickReceive(view: View)
    { showToast("Clicked Receive!")  }

    fun clickReload(view: View)
    {
        showToast("Clicked Reload!")
        startScanning()
    }

    fun clickSave(view: View)
    { showToast("Clicked Save!") }

    //NAVIGATION===============================================================================================================

    private fun goBackToPermissionPage()
    {
        val intent = Intent(this, PermissionScreen::class.java)
        startActivity(intent)
        finish() // Optional: finish() current activity if not needed anymore
    }


    //HELPER FUNCTIONS=========================================================================================================

    fun String.decodeHex(): String {
        require(length % 2 == 0) {"Must have an even length"}
        return chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
            .toString(Charsets.ISO_8859_1)  // Or whichever encoding your input uses
    }

    //BLUETOOTH================================================================================================================

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            super.onScanResult(callbackType, result)

            result?.let {
                val deviceName = it.scanRecord?.deviceName
                val deviceAddress = it.device.address

                if (deviceName == "ESP32") {
                    selectedDevice = it.device
                    Log.d("BLE_SCAN", "Selected device: $deviceName")
                    connect()
                    stopScanning()
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
        }
    }

    private fun startScanning() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            goBackToPermissionPage()
            return
        }
        scanner.startScan(scanCallback)
    }

    fun stopScanning() {
        Log.d("BLE_SCAN", "Stopping scan...")
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            goBackToPermissionPage()
            return
        }
        scanner.stopScan(scanCallback)
    }

    private val callback = object: BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)

            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.d("BLE_SCAN", "Error on connecting...")
                return
            }
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                Log.d("BLE_SCAN", "Connected...")
                if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    goBackToPermissionPage()
                    return
                }
                gatt.discoverServices()
            }
            if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                Log.d("BLE_SCAN", "Disconnected...")
                startScanning()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int)
        {
            super.onServicesDiscovered(gatt, status)
            if (status == BluetoothGatt.GATT_SUCCESS)
            {
                services = gatt.services
                Log.d("BLE_SERVICES", "Services discovered:")
                services.forEach { service ->
                    Log.d("BLE_SERVICES", "Service UUID: ${service.uuid}")
                    service.characteristics.forEach { characteristic ->
                        Log.d("BLE_SERVICES", "  Characteristic UUID: ${characteristic.uuid}")
                        Log.d("BLE_SERVICES", "  Properties: ${characteristic.properties}")
                    }
                }

                // Assuming you know the UUID of the characteristic you want to enable notifications for
                val characteristicUUID = UUID.fromString("a9248655-7f1b-4e18-bf36-ad1ee859983f")
                enableNotifications(gatt, characteristicUUID)
            } else {
                Log.w("BLE_SERVICES", "onServicesDiscovered received: $status")
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            super.onCharacteristicChanged(gatt, characteristic)
            val data = characteristic.value
            Log.d("BLE_NOTIFY", "Notification received from ${characteristic.uuid}: ${data.toHexString()}")
            var text = "Received: " + data.toHexString().decodeHex()
            binding.TextForDebug.text = text
        }
    }

    fun connect() {
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
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            {
                goBackToPermissionPage()
                return
            }
            gatt.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
            descriptor?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(descriptor)
            Log.d("BLE_NOTIFY", "Notifications enabled for characteristic: $characteristicUUID")
        } else {
            Log.w("BLE_NOTIFY", "Characteristic $characteristicUUID not found")
        }
    }

    private fun ByteArray.toHexString() = joinToString("") { "%02x".format(it) }
}
