package com.iyabong.forter

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat

/**
 * 클래식 블루투스(BR/EDR) 스캐너.
 *
 * BLE 스캔과 달리 콜백이 아니라 시스템 브로드캐스트로 결과가 온다.
 *  - ACTION_FOUND             : 기기 1대 발견
 *  - ACTION_DISCOVERY_FINISHED: 탐색 종료 (약 12초, 시스템이 알아서 끝냄)
 *
 * 페어링된 기기(bondedDevices)는 discovery에 안 잡히는 경우가 많아서
 * 스캔 시작 시 먼저 목록에 뿌려준다. 토크프로용으로 이미 페어링해 뒀다면
 * iCar Pro 2s가 여기서 바로 보일 가능성이 높다.
 */
class ClassicBtScanner(private val context: Context) {

    companion object {
        private const val TAG = "ClassicBtScanner"
        // BLE 쪽 타겟 이름에 클래식 어댑터가 자주 쓰는 이름을 더한다
        val TARGET_NAMES = BleScanner.TARGET_NAMES + setOf("OBD", "ELM", "V-LINK", "VEEPEAK")
    }

    interface ScanListener {
        fun onDeviceFound(
            device: BluetoothDevice,
            name: String?,
            rssi: Int?,        // 페어링 목록에서 온 기기는 RSSI 없음 → null
            isTarget: Boolean,
            bonded: Boolean
        )
        fun onScanStarted()
        fun onScanStopped()
        fun onScanFailed(reason: String)
    }

    private val adapter: BluetoothAdapter? by lazy {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        manager.adapter
    }

    private var listener: ScanListener? = null
    private var isScanning = false
    private var receiverRegistered = false
    private val seenAddresses = mutableSetOf<String>()

    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(ctx: Context?, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = intent.extraDevice() ?: return
                    val rawRssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE)
                    val rssi = if (rawRssi == Short.MIN_VALUE) null else rawRssi.toInt()
                    // device.name 이 null 이면 브로드캐스트가 실어 보낸 이름을 쓴다
                    val name = device.name ?: intent.getStringExtra(BluetoothDevice.EXTRA_NAME)
                    report(device, name, rssi, bonded = false)
                }

                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    Log.i(TAG, "탐색 종료 (시스템)")
                    stopScan()
                }
            }
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT])
    fun startScan(scanListener: ScanListener) {
        if (isScanning) return

        val a = adapter
        if (a == null) {
            scanListener.onScanFailed("블루투스 어댑터 없음")
            return
        }
        if (!a.isEnabled) {
            scanListener.onScanFailed("블루투스가 꺼져 있습니다")
            return
        }

        listener = scanListener
        seenAddresses.clear()
        registerReceiver()

        // 1) 이미 페어링된 기기부터 (discovery에 안 잡히는 경우가 많다)
        a.bondedDevices?.forEach { device ->
            report(device, device.name, rssi = null, bonded = true)
        }

        // 2) 주변 탐색 시작
        if (a.isDiscovering) a.cancelDiscovery()
        val started = a.startDiscovery()
        if (!started) {
            unregisterReceiver()
            listener = null
            scanListener.onScanFailed("탐색 시작 실패 (권한/블루투스 상태 확인)")
            return
        }

        isScanning = true
        Log.i(TAG, "클래식 탐색 시작")
        scanListener.onScanStarted()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun stopScan() {
        if (!isScanning) {
            unregisterReceiver()
            return
        }
        adapter?.takeIf { it.isDiscovering }?.cancelDiscovery()
        unregisterReceiver()
        isScanning = false
        Log.i(TAG, "클래식 탐색 중지")
        listener?.onScanStopped()
        listener = null
    }

    fun isScanning() = isScanning

    // ── 내부 ────────────────────────────────────────────────────────────
    @SuppressLint("MissingPermission")
    private fun report(device: BluetoothDevice, name: String?, rssi: Int?, bonded: Boolean) {
        if (!seenAddresses.add(device.address)) return

        val isTarget = name?.let { n -> TARGET_NAMES.any { n.contains(it, ignoreCase = true) } } ?: false
        val type = when (device.type) {
            BluetoothDevice.DEVICE_TYPE_CLASSIC -> "CLASSIC"
            BluetoothDevice.DEVICE_TYPE_LE      -> "LE"
            BluetoothDevice.DEVICE_TYPE_DUAL    -> "DUAL"
            else                                -> "UNKNOWN"
        }
        Log.d(
            TAG,
            "발견: ${name ?: "(이름없음)"} | ${device.address} | rssi=${rssi ?: "-"} | " +
                "type=$type | bonded=$bonded | target=$isTarget"
        )
        listener?.onDeviceFound(device, name, rssi, isTarget, bonded)
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        // Android 13+ 는 exported 플래그가 필수
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
    }

    private fun unregisterReceiver() {
        if (!receiverRegistered) return
        runCatching { context.unregisterReceiver(receiver) }
        receiverRegistered = false
    }

    @Suppress("DEPRECATION")
    private fun Intent.extraDevice(): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
}
