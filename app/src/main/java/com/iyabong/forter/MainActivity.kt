package com.iyabong.forter

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.iyabong.forter.ui.theme.ForterTheme

class MainActivity : ComponentActivity() {

    private lateinit var bleScanner: BleScanner

    // ── 스캔 결과를 UI에 넘겨줄 상태 ──────────────────────────────────
    private val scanLogs = mutableStateListOf<String>()
    private var isScanning = mutableStateOf(false)

    // ── 권한 요청 런처 ─────────────────────────────────────────────────
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            scanLogs.add("✅ 권한 허용됨 - 스캔 시작")
            startBleScan()
        } else {
            scanLogs.add("❌ 권한 거부됨 - 설정에서 허용 필요")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        bleScanner = BleScanner(this)

        setContent {
            ForterTheme {
                ScanScreen(
                    logs = scanLogs,
                    isScanning = isScanning.value,
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
        isScanning.value = true
        scanLogs.add("🔍 스캔 중...")

        bleScanner.startScan(object : BleScanner.ScanListener {
            override fun onDeviceFound(device: BluetoothDevice, rssi: Int) {
                val name = device.name ?: "Unknown"
                scanLogs.add("📡 발견: $name | ${device.address} | RSSI: $rssi")
            }

            override fun onScanStarted() {
                scanLogs.add("▶ 스캔 시작됨")
            }

            override fun onScanStopped() {
                isScanning.value = false
                scanLogs.add("⏹ 스캔 종료")
            }

            override fun onScanFailed(errorCode: Int) {
                isScanning.value = false
                scanLogs.add("❌ 스캔 실패 (코드: $errorCode)")
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
    logs: List<String>,
    isScanning: Boolean,
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

            // 스캔 버튼
            Button(
                onClick = onScanClick,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isScanning) "⏹ 스캔 중지" else "🔍 스캔 시작")
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 로그 출력
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(logs.reversed()) { log ->
                    Text(
                        text = log,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}