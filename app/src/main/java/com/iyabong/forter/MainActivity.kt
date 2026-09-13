package com.iyabong.forter

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iyabong.forter.ui.theme.ForterTheme

enum class ScanSource { BLE, CLASSIC }

data class ScannedDevice(
    val name: String,
    val address: String,
    val rssi: Int?,
    val isTarget: Boolean,
    val source: ScanSource,
    val bonded: Boolean = false
)

class MainActivity : ComponentActivity() {

    private lateinit var bleScanner: BleScanner
    private lateinit var classicScanner: ClassicBtScanner
    private val obdSpp = ObdSppConnection()

    private val devices = mutableStateListOf<ScannedDevice>()
    private val logs = mutableStateListOf<String>()
    private val isScanning = mutableStateOf(false)
    private val activeMode = mutableStateOf<ScanSource?>(null)
    private val statusText = mutableStateOf("스캔을 시작하세요")
    private val intervalMs = mutableStateOf(1000L)   // 화면에서 바꿀 수 있는 수집 주기

    private var pendingMode: ScanSource? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val mode = pendingMode
        pendingMode = null
        if (permissions.all { it.value }) {
            when (mode) {
                ScanSource.BLE -> startBleScan()
                ScanSource.CLASSIC -> startClassicScan()
                null -> Unit
            }
        } else {
            statusText.value = "❌ 권한 거부됨 - 설정에서 허용 필요"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        bleScanner = BleScanner(this)
        classicScanner = ClassicBtScanner(this)

        setContent {
            ForterTheme {
                MainScreen(
                    devices = devices,
                    logs = logs,
                    isScanning = isScanning.value,
                    activeMode = activeMode.value,
                    statusText = statusText.value,
                    intervalMs = intervalMs.value,
                    onScanClick = { mode -> onScanButton(mode) },
                    onDeviceClick = { d -> connectTo(d) },
                    onIntervalChange = { intervalMs.value = it },
                    onBack = {
                        obdSpp.stop()
                        logs.clear()
                        statusText.value = "스캔을 시작하세요"
                    }
                )
            }
        }
    }

    // ── 스캔 버튼 ──────────────────────────────────────────────────────
    @SuppressLint("MissingPermission")
    private fun onScanButton(mode: ScanSource) {
        if (obdSpp.isRunning()) obdSpp.stop()
        if (isScanning.value) {
            stopScanners()
            if (activeMode.value == mode) return
        }
        pendingMode = mode
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        )
    }

    // ── 기기 탭 → SPP 세션 ─────────────────────────────────────────────
    @SuppressLint("MissingPermission")
    private fun connectTo(entry: ScannedDevice) {
        if (entry.source == ScanSource.BLE) {
            statusText.value = "⚠ BLE는 보류 - 클래식 목록에서 Android-Vlink 선택"
            return
        }

        stopScanners()
        logs.clear()

        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val device = manager.adapter.getRemoteDevice(entry.address)
        statusText.value = "🔌 ${entry.name} 연결 중..."

        obdSpp.start(device, intervalMs.value, object : ObdSppConnection.Listener {
            override fun onLog(line: String) {
                logs.add(line)
            }

            override fun onSample(speedKmh: Int?, rpm: Int?, cycleMs: Long) {
                statusText.value =
                    "${speedKmh ?: "-"} km/h · ${rpm ?: "-"} rpm · ${cycleMs}ms"
            }

            override fun onStats(cycles: Int, avgCycleMs: Long, overruns: Int, errors: Int) {
                logs.add("📊 ${cycles}사이클 · 평균 ${avgCycleMs}ms · 초과 $overruns · 실패 $errors")
            }

            override fun onStopped(reason: String) {
                statusText.value = "⛔ $reason"
            }
        })
    }

    // ── 스캐너 ─────────────────────────────────────────────────────────
    @SuppressLint("MissingPermission")
    private fun startBleScan() {
        devices.clear()
        activeMode.value = ScanSource.BLE
        statusText.value = "🔍 BLE 스캔 중..."

        bleScanner.startScan(object : BleScanner.ScanListener {
            override fun onDeviceFound(device: BluetoothDevice, name: String?, rssi: Int, isTarget: Boolean) {
                upsert(ScannedDevice(name ?: "(이름 없음)", device.address, rssi, isTarget, ScanSource.BLE))
            }
            override fun onScanStarted() {
                isScanning.value = true
                statusText.value = "🔍 BLE 스캔 중..."
            }
            override fun onScanStopped() {
                isScanning.value = false
                statusText.value = "⏹ BLE 스캔 종료 · ${devices.size}대"
            }
            override fun onScanFailed(errorCode: Int) {
                isScanning.value = false
                statusText.value = "❌ BLE 스캔 실패 (코드: $errorCode)"
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun startClassicScan() {
        devices.clear()
        activeMode.value = ScanSource.CLASSIC
        statusText.value = "🔍 클래식 탐색 중..."

        classicScanner.startScan(object : ClassicBtScanner.ScanListener {
            override fun onDeviceFound(
                device: BluetoothDevice, name: String?, rssi: Int?, isTarget: Boolean, bonded: Boolean
            ) {
                upsert(ScannedDevice(name ?: "(이름 없음)", device.address, rssi, isTarget, ScanSource.CLASSIC, bonded))
            }
            override fun onScanStarted() {
                isScanning.value = true
                statusText.value = "🔍 클래식 탐색 중... (약 12초)"
            }
            override fun onScanStopped() {
                isScanning.value = false
                statusText.value = "⏹ 탐색 종료 · ${devices.size}대 · 탭하면 연결"
            }
            override fun onScanFailed(reason: String) {
                isScanning.value = false
                statusText.value = "❌ $reason"
            }
        })
    }

    private fun upsert(entry: ScannedDevice) {
        val idx = devices.indexOfFirst { it.address == entry.address && it.source == entry.source }
        if (idx >= 0) devices[idx] = entry else devices.add(entry)
        devices.sortWith(
            compareByDescending<ScannedDevice> { it.isTarget }
                .thenByDescending { it.bonded }
                .thenByDescending { it.rssi ?: Int.MIN_VALUE }
        )
    }

    @SuppressLint("MissingPermission")
    private fun stopScanners() {
        if (bleScanner.isScanning()) bleScanner.stopScan()
        if (classicScanner.isScanning()) classicScanner.stopScan()
        isScanning.value = false
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        super.onDestroy()
        stopScanners()
        obdSpp.stop()
    }
}

// ── Compose UI ─────────────────────────────────────────────────────────────
@Composable
fun MainScreen(
    devices: List<ScannedDevice>,
    logs: List<String>,
    isScanning: Boolean,
    activeMode: ScanSource?,
    statusText: String,
    intervalMs: Long,
    onScanClick: (ScanSource) -> Unit,
    onDeviceClick: (ScannedDevice) -> Unit,
    onIntervalChange: (Long) -> Unit,
    onBack: () -> Unit
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
                text = "Forter Scanner",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = { onScanClick(ScanSource.BLE) }, modifier = Modifier.weight(1f)) {
                    Text(if (isScanning && activeMode == ScanSource.BLE) "⏹ 중지" else "🔍 BLE")
                }
                Button(onClick = { onScanClick(ScanSource.CLASSIC) }, modifier = Modifier.weight(1f)) {
                    Text(if (isScanning && activeMode == ScanSource.CLASSIC) "⏹ 중지" else "🔍 클래식")
                }
            }

            // 수집 주기 — 재빌드 없이 차에서 바꿔가며 실측하기 위한 토글
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(500L, 1000L, 2000L).forEach { ms ->
                    FilterChip(
                        selected = intervalMs == ms,
                        onClick = { onIntervalChange(ms) },
                        label = { Text("${ms}ms") },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Text(
                text = statusText,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 12.dp)
            )

            if (logs.isNotEmpty()) {
                LogPanel(logs = logs, onBack = onBack)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(devices) { device ->
                        DeviceRow(device, onClick = { onDeviceClick(device) })
                    }
                }
            }
        }
    }
}

@Composable
private fun LogPanel(logs: List<String>, onBack: () -> Unit) {
    val state = rememberLazyListState()
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) state.animateScrollToItem(logs.size - 1)
    }
    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("⏹ 중지하고 기기 목록으로")
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(
            state = state,
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(10.dp)
        ) {
            items(logs) { line ->
                Text(
                    text = line,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }
        }
    }
}

@Composable
fun DeviceRow(device: ScannedDevice, onClick: () -> Unit) {
    val bg = if (device.isTarget)
        MaterialTheme.colorScheme.primaryContainer
    else
        MaterialTheme.colorScheme.surfaceVariant

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable { onClick() }
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
                text = device.rssi?.let { "$it dBm" } ?: "-",
                style = MaterialTheme.typography.bodySmall
            )
        }
        val tags = buildString {
            append(if (device.source == ScanSource.BLE) "BLE" else "CLASSIC")
            if (device.bonded) append(" · 페어링됨")
        }
        Text("${device.address}  [$tags]", style = MaterialTheme.typography.bodySmall)
    }
}