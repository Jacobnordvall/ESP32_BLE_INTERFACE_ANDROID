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
import android.content.Context
import android.content.Intent

class MainActivity : AppCompatActivity() {

    private val REQUEST_ENABLE_BT = 1

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions())
    {
            permissions ->

        if (permissions.all { it.value })
        {

        }
        else
        { requestPermissions() }
    }




    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?)
    {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Example of a call to a native method
        binding.sampleText.text = stringFromJNI()


        // Check if Bluetooth permissions are granted
        if (!hasPermissions())
        { requestPermissions() }
        //if bluetooth is off: turn it on?
        RequestBluetoothOn()


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
    { showToast("Clicked Receive!") }

    fun ClickReload(view: View)
    { showToast("Clicked Reload!") }

    fun ClickSave(view: View)
    { showToast("Clicked Save!") }


    //PERMISSIONS==============================================================================================================


    private fun hasPermissions(): Boolean
    {
        return (ContextCompat.checkSelfPermission(this,
            Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this,
                    Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this,
                    Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this,
                    Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this,
                    Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)
    }

    private fun requestPermissions()
    {
        val permissions = arrayOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        permissionLauncher.launch(permissions)
    }


    //BLE==============================================================================================================

    private fun RequestBluetoothOn()
    {
        val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null) {
            // Device doesn't support Bluetooth
            return
        }
        if (!bluetoothAdapter.isEnabled) {
            // Bluetooth is not enabled, prompt the user to enable it
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
        }
    }



}