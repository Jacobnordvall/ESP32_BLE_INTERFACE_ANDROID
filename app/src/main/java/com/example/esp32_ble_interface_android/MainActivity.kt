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

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?)
    {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Example of a call to a native method
        binding.sampleText.text = stringFromJNI()
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
}