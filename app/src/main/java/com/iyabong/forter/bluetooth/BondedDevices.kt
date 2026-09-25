package com.iyabong.forter.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import com.iyabong.forter.Permissions

data class BondedDevice(
    val name: String,
    val address: String,
    val type: Int,
)

sealed interface BondedResult {
    data object NoPermission : BondedResult
    data object BluetoothOff : BondedResult
    data class Devices(val list: List<BondedDevice>) : BondedResult
}

@SuppressLint("MissingPermission")  // 직접 권한 확인
fun loadBondedDevices(context: Context): BondedResult {
    if (!Permissions.isGranted(context, Manifest.permission.BLUETOOTH_CONNECT)) {
        return BondedResult.NoPermission
    }

    val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    if (adapter == null || !adapter.isEnabled) {
        return BondedResult.BluetoothOff
    }

    val devices = adapter.bondedDevices
        .map { BondedDevice(it.name ?: "(이름 없음)", it.address, it.type) }
        .sortedBy { it.name }

    return BondedResult.Devices(devices)
}

fun typeLabel(type: Int): String = when (type) {
    BluetoothDevice.DEVICE_TYPE_CLASSIC -> "Classic"
    BluetoothDevice.DEVICE_TYPE_LE -> "LE"
    BluetoothDevice.DEVICE_TYPE_DUAL -> "Dual"
    else -> "Unknown"
}