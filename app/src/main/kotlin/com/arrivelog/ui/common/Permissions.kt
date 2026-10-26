package com.arrivelog.ui.common

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable

/**
 * Android 12+ shows the precise/approximate choice only when FINE and COARSE are requested
 * together. [onResult] gets true only for *precise*: geofences and SSID reads need it.
 */
@Composable
fun rememberFineLocationRequest(onResult: (preciseGranted: Boolean) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        onResult(result[Manifest.permission.ACCESS_FINE_LOCATION] == true)
    }
    return { launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }
}

@Composable
fun rememberPermissionRequest(permission: String, onResult: (Boolean) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), onResult)
    return { launcher.launch(permission) }
}
