package com.example.esp32_ble_interface_android

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.TextView
import com.example.esp32_ble_interface_android.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
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
}