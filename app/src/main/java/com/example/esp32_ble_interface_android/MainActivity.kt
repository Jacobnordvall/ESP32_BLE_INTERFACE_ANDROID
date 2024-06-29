package com.example.esp32_ble_interface_android

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.View
import android.widget.Toast
import com.example.esp32_ble_interface_android.databinding.ActivityMainBinding
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import kotlinx.coroutines.*
import android.util.Log
import android.content.Intent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.app.Activity
import android.net.Uri
import android.provider.Settings
import kotlinx.coroutines.delay
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import android.os.Build
import android.os.Handler
import android.os.Looper


class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private lateinit var bluetoothManager: BluetoothManager
    private val scanner: BluetoothLeScanner
        get() = bluetoothManager.adapter.bluetoothLeScanner

    private var selectedDevice: BluetoothDevice? = null


    override fun onCreate(savedInstanceState: Bundle?)
    {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Example of a call to a native method
        binding.sampleText.text = stringFromJNI()


        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            ?: throw Exception("Bluetooth is not supported by this device")

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
    fun showToast(message: String)
    { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }

    fun ClickWrite(view: View)
    { showToast("Clicked Write!") }

    fun ClickReceive(view: View)
    {
        showToast("Clicked Receive!")
        startScanning()
    }

    fun ClickReload(view: View)
    { showToast("Clicked Reload!") }

    fun ClickSave(view: View)
    { showToast("Clicked Save!") }


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
                    stopScanning()
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            //Log.e("BLE_SCAN", "Scan failed with error code: $errorCode")
        }
    }

    fun startScanning() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            //Log.w("BLE_SCAN", "Bluetooth scan permission not granted")
            // Request the missing permissions
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_SCAN), 1)
            return
        }
        //Log.d("BLE_SCAN", "Starting scan...")
        scanner.startScan(scanCallback)
    }

    fun stopScanning() {
        Log.d("BLE_SCAN", "Stopping scan...")
        scanner.stopScan(scanCallback)
    }
}