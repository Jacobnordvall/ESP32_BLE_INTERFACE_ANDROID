package com.example.esp32_ble_interface_android

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.content.Intent
import android.content.pm.PackageManager
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.app.Activity
import android.net.Uri
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch


class PermissionScreen : AppCompatActivity() {

    private val REQUEST_ENABLE_BT = 1
    var ALL_DONE: Boolean = false

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions())
    {   permissions ->

        if (permissions.all { it.value })
            requestBluetoothOn()
        else
            requestPermissions()
    }



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_permission_screen)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Check if Bluetooth permissions are granted
        if (!hasPermissions())
            requestPermissions()
        else
            requestBluetoothOn()


        lifecycleScope.launch {
            checkBluetoothStatus(this)
        }
    }


    //SCREEN_SWITCH============================================================================================================

    public fun mainScreen()
    {
        if(!ALL_DONE)
        {
            ALL_DONE = true
            Log.d("FINISHED", "INISHED: ")
            val intent = Intent(this, MainActivity::class.java)
            startActivity(intent)
            finish() // Optional: finish() current activity if not needed anymore
        }
    }


    //BUTTONS==================================================================================================================

    fun goToMainScreen(view: View)
    {
        statusBluetooth()
    }

    fun openAppInfo(view: View)
    {
        val packageUri = Uri.fromParts("package", packageName, null)
        val appInfoIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
        startActivity(appInfoIntent)

    }

    fun openBluetoothSettings(view: View)
    {
        val bluetoothIntent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        startActivity(bluetoothIntent)
    }


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

    private fun requestBluetoothOn() {
        val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null) {
            // Device doesn't support Bluetooth
            return
        }
        if (!bluetoothAdapter.isEnabled)
        {
            // Bluetooth is not enabled, prompt the user to enable it
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)

            // Check if BLUETOOTH_CONNECT permission is granted
            if (ContextCompat.checkSelfPermission( this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQUEST_ENABLE_BT)
                // You can handle the result of permission request in onRequestPermissionsResult
            }
            else
            {
                // Permission is granted, start activity to enable Bluetooth
                startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
            }
        }
        else
        {
            mainScreen()
        }
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            REQUEST_ENABLE_BT -> {
                if (resultCode == Activity.RESULT_OK) {
                    // Bluetooth was enabled successfully
                    // Perform actions here
                    mainScreen()
                } else {
                    // Bluetooth enabling was either canceled or failed
                    // Handle this case accordingly
                }
            }
        }
    }

    private suspend fun checkBluetoothStatus(scope: CoroutineScope) {
        val job = scope.launch {
            for (i in 0..100) {
                statusBluetooth()
                delay(1000)
            }
        }
        // use job.cancel() for cancelling the job or use job.join() for waiting for the job to finish
    }

    private fun statusBluetooth()
    {
        val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter != null) {
            if (bluetoothAdapter.isEnabled && hasPermissions())
                mainScreen()
        }
    }
}