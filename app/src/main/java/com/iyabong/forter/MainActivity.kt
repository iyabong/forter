package com.iyabong.forter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.iyabong.forter.bluetooth.SppConnection
import com.iyabong.forter.bluetooth.loadBondedDevices
import com.iyabong.forter.ui.DeviceSelectScreen
import com.iyabong.forter.ui.theme.ForterTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            ForterTheme {
                val context = LocalContext.current
                val scope = rememberCoroutineScope()

                var result by remember { mutableStateOf(loadBondedDevices(context)) }
                var status by remember { mutableStateOf("기기를 선택하세요") }

                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) {
                    result = loadBondedDevices(context) // 허용/거부 후 다시 조회
                }

                // 앱 시작시 빠진 권한 있으면 바로 요청
                LaunchedEffect(Unit) {
                    if (Permissions.missing(context).isNotEmpty()) {
                        permissionLauncher.launch(Permissions.required)
                    }
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(Modifier.padding(innerPadding)) {
                        Text(
                            text = "Forter v${BuildConfig.VERSION_NAME}",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                        Text(
                            text = status,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                        DeviceSelectScreen(
                            result = result,
                            onRequestPermission = { permissionLauncher.launch(Permissions.required) },
                            onRefresh = { result = loadBondedDevices(context) },
                            onSelect = { device ->
                                scope.launch {
                                    val conn = SppConnection(context, device.address)
                                    try {
                                        status = "연결 중: ${device.name}"
                                        conn.connect()
                                        status = "ATZ 전송..."
                                        val response = conn.send("ATZ")
                                        status = "응답:\n$response"
                                    } catch (e: Exception) {
                                        status = "실패: ${e.message}"
                                    } finally {
                                        conn.close()
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}