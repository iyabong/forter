package com.iyabong.forter

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.iyabong.forter.ui.theme.ForterTheme

// 발견된 BLE 기기 한 대를 표현하는 데이터
data class ScannedDevice(
    val name: String,
    val address: String,
    val rssi: Int,
    val isTarget: Boolean
)

class MainActivity : ComponentActivity() {

    private lateinit var bleScanner: BleScanner

    // ── UI 상태 ────────────────────────────────────────────────────────
    private val devices = mutableStateListOf<ScannedDevice>()   // 발견된 기기 목록
    private var isScanning = mutableStateOf(false)
    private var statusText = mutableStateOf("스캔을 시작하세요")

    // ── 권한 요청 런처 ─────────────────────────────────────────────────
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            startBleScan()
        } else {
            statusText.value = "❌ 권한 거부됨 - 설정에서 허용 필요"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        bleScanner = BleScanner(this)

        setContent {
            ForterTheme {
                ScanScreen(
                    devices = devices,
                    isScanning = isScanning.value,
                    statusText = statusText.value,
                    onScanClick = {
                        if (isScanning.value) {
                            stopBleScan()
                        } else {
                            requestPermissionsAndScan()
                        }
                    }
                )
            }
        }
    }

    // ── BLE 권한 요청 ──────────────────────────────────────────────────
    private fun requestPermissionsAndScan() {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        )
    }

    // ── 스캔 시작 ──────────────────────────────────────────────────────
    @SuppressLint("MissingPermission")
    private fun startBleScan() {
        devices.clear()
        statusText.value = "🔍 스캔 중..."

        bleScanner.startScan(object : BleScanner.ScanListener {
            override fun onDeviceFound(device: BluetoothDevice, name: String?, rssi: Int, isTarget: Boolean) {
                val displayName = name ?: "(이름 없음)"
                val idx = devices.indexOfFirst { it.address == device.address }
                val entry = ScannedDevice(displayName, device.address, rssi, isTarget)
                if (idx >= 0) {
                    devices[idx] = entry
                } else {
                    devices.add(entry)
                }
                // 타겟(OBD) 먼저, 그다음 신호 센 순
                devices.sortWith(
                    compareByDescending<ScannedDevice> { it.isTarget }
                        .thenByDescending { it.rssi }
                )
            }

            override fun onScanStarted() {
                isScanning.value = true
                statusText.value = "🔍 스캔 중..."
            }

            override fun onScanStopped() {
                isScanning.value = false
                statusText.value = "⏹ 스캔 종료 · ${devices.size}대 발견"
            }

            override fun onScanFailed(errorCode: Int) {
                isScanning.value = false
                statusText.value = "❌ 스캔 실패 (코드: $errorCode)"
            }
        })
    }

    // ── 스캔 중지 ──────────────────────────────────────────────────────
    @SuppressLint("MissingPermission")
    private fun stopBleScan() {
        bleScanner.stopScan()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isScanning.value) {
            @SuppressLint("MissingPermission")
            bleScanner.stopScan()
        }
    }
}

// ── Compose UI ─────────────────────────────────────────────────────────────
@Composable
fun ScanScreen(
    devices: List<ScannedDevice>,
    isScanning: Boolean,
    statusText: String,
    onScanClick: () -> Unit
) {
    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Forter BLE Scanner",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            Button(
                onClick = onScanClick,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isScanning) "⏹ 스캔 중지" else "🔍 스캔 시작")
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 8.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(devices) { device ->
                    DeviceRow(device)
                }
            }
        }
    }
}

@Composable
fun DeviceRow(device: ScannedDevice) {
    val bg = if (device.isTarget)
        MaterialTheme.colorScheme.primaryContainer
    else
        MaterialTheme.colorScheme.surfaceVariant

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = (if (device.isTarget) "⭐ " else "") + device.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (device.isTarget) FontWeight.Bold else FontWeight.Normal
            )
            Text(
                text = "${device.rssi} dBm",
                style = MaterialTheme.typography.bodySmall
            )
        }
        Text(
            text = device.address,
            style = MaterialTheme.typography.bodySmall
        )
    }
}