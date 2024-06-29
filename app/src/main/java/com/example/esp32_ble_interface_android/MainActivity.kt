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
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.util.Log


class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private lateinit var bluetoothManager: BluetoothManager
    private val scanner: BluetoothLeScanner get() = bluetoothManager.adapter.bluetoothLeScanner
    private var selectedDevice: BluetoothDevice? = null
    private var gatt: BluetoothGatt? = null

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


    //BLUETOOTH================================================================================================================

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            super.onScanResult(callbackType, result)

            result?.let {
                val deviceName = it.scanRecord?.deviceName
                val deviceAddress = it.device.address

                //Log.d("BLE_SCAN", "Found device: $deviceName, Address: $deviceAddress")

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
            //Log.e("BLE_SCAN", "Scan failed with error code: $errorCode")
        }
    }

    private fun startScanning() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            //Log.w("BLE_SCAN", "Bluetooth scan permission not granted")
            // Request the missing permissions
            //ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_SCAN), 1)
            goBackToPermissionPage()
            return
        }
        //Log.d("BLE_SCAN", "Starting scan...")
        scanner.startScan(scanCallback)
    }

    fun stopScanning() {
        Log.d("BLE_SCAN", "Stopping scan...")
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
        {
            // here to request the missing permissions, and then overriding
            goBackToPermissionPage()
            return
        }
        scanner.stopScan(scanCallback)
    }


    //Whatever we do with our Bluetooth device connection, whether now or later, we will get the
    //results in this callback object, which can become massive.
    private val callback = object: BluetoothGattCallback() {
        //We will override more methods here as we add functionality.

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)
            //This tells us when we're connected or disconnected from the peripheral.

            if (status != BluetoothGatt.GATT_SUCCESS) {
                //TODO: handle error
                Log.d("BLE_SCAN", "Error on connecting...")
                return
            }

            if (newState == BluetoothGatt.STATE_CONNECTED) {
                //TODO: handle the fact that we've just connected
                Log.d("BLE_SCAN", "Connected...")
            }
            if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                //TODO: handle the fact that we've just disconnected
                Log.d("BLE_SCAN", "Disconnected...")
                startScanning()
            }
        }
    }

    fun connect() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
        {
            // here to request the missing permissions, and then overriding
            goBackToPermissionPage()
            return
        }
        selectedDevice?.connectGatt(this, false, callback) ?: Log.e("BLE_CONNECT", "Selected device is null")
    }

}