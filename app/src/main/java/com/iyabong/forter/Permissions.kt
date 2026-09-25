package com.iyabong.forter

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

object Permissions {

    // TODO: GPS, foreground service...
    val required: Array<String> = arrayOf(
        Manifest.permission.BLUETOOTH_CONNECT,
    )

    fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun missing(context: Context): List<String> =
        required.filterNot { isGranted(context, it) }
}