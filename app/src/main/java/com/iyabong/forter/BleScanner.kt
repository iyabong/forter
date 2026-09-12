package com.iyabong.forter

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresPermission

class BleScanner(private val context: Context) {

    companion object {
        private const val TAG = "BleScanner"
        private const val SCAN_TIMEOUT_MS = 15_000L
        val TARGET_NAMES = setOf("OBDII", "iCar", "Vgate", "iCar Pro")
    }

    interface ScanListener {
        fun onDeviceFound(device: BluetoothDevice, name: String?, rssi: Int, isTarget: Boolean)
        fun onScanStarted()
        fun onScanStopped()
        fun onScanFailed(errorCode: Int)
    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        manager.adapter
    }

    private val scanner by lazy { bluetoothAdapter?.bluetoothLeScanner }

    private var listener: ScanListener? = null
    private var isScanning = false
    private val timeoutHandler = Handler(Looper.getMainLooper())

    // 중복 표시 방지용 (MAC 주소 기준)
    private val seenAddresses = mutableSetOf<String>()

    private val scanCallback = object : ScanCallback() {
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val address = device.address

            // 같은 기기 중복 콜백 방지
            if (!seenAddresses.add(address)) return

            // 이름을 여러 소스에서 시도: device.name → 광고 패킷(scanRecord)
            val advName = result.scanRecord?.deviceName
            val realName = device.name ?: advName   // 둘 다 null이면 이름 없는 기기

            val isTarget = realName?.let { n ->
                TARGET_NAMES.any { n.contains(it, ignoreCase = true) }
            } ?: false

            Log.d(TAG, "발견: ${realName ?: "(이름없음)"} | $address | RSSI: ${result.rssi} | target=$isTarget")
            listener?.onDeviceFound(device, realName, result.rssi, isTarget)
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            Log.e(TAG, "스캔 실패: $errorCode")
            listener?.onScanFailed(errorCode)
            listener = null
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun startScan(scanListener: ScanListener) {
        if (isScanning) return
        this.listener = scanListener
        seenAddresses.clear()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner?.startScan(null, settings, scanCallback)
        isScanning = true
        Log.i(TAG, "스캔 시작")
        listener?.onScanStarted()
        timeoutHandler.postDelayed({
            if (isScanning) stopScan()
        }, SCAN_TIMEOUT_MS)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun stopScan() {
        if (!isScanning) return
        timeoutHandler.removeCallbacksAndMessages(null)
        scanner?.stopScan(scanCallback)
        isScanning = false
        Log.i(TAG, "스캔 중지")
        listener?.onScanStopped()
        listener = null
    }

    fun isScanning() = isScanning
}