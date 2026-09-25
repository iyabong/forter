package com.iyabong.forter.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.iyabong.forter.bluetooth.BondedDevice
import com.iyabong.forter.bluetooth.BondedResult
import com.iyabong.forter.bluetooth.typeLabel

@Composable
fun DeviceSelectScreen(
    result: BondedResult,
    onRequestPermission: () -> Unit,
    onRefresh: () -> Unit,
    onSelect: (BondedDevice) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        when (result) {
            BondedResult.NoPermission -> {
                Text("블루투스 연결 권한이 필요합니다.")
                Spacer(Modifier.height(8.dp))
                Button(onClick = onRequestPermission) { Text("권한 요청") }
            }

            BondedResult.BluetoothOff -> {
                Text("블루투스가 꺼져 있습니다.")
                Spacer(Modifier.height(8.dp))
                Button(onClick = onRefresh) { Text("새로고침") }
            }

            is BondedResult.Devices -> {
                Button(onClick = onRefresh) { Text("새로고침") }
                Spacer(Modifier.height(8.dp))

                if (result.list.isEmpty()) {
                    Text("페어링된 기기가 없습니다.")
                } else {
                    LazyColumn() {
                        items(result.list, key = { it.address }) { device ->
                            ListItem(
                                headlineContent = { Text(device.name) },
                                supportingContent = {
                                    Text("${device.address} :  ${typeLabel(device.type)}")
                                },
                                modifier = Modifier.clickable { onSelect(device) }
                            )
                            HorizontalDivider()
                        }
                    }
                }

            }
        }
    }
}