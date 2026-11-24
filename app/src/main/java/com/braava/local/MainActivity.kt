package com.braava.local

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.braava.local.ui.BraavaApp

class MainActivity : ComponentActivity() {

    /** Android 13+ "Nearby devices": also unlocks LAN sockets on Android 16 LNP. */
    private val nearbyPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNearbyPermission()
        setContent {
            BraavaApp()
        }
    }

    private fun requestNearbyPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val permission = Manifest.permission.NEARBY_WIFI_DEVICES
        if (ContextCompat.checkSelfPermission(this, permission) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            nearbyPermission.launch(permission)
        }
    }
}
