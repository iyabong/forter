package com.iyabong.forter.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID

class SppConnection(
    private val context: Context,
    private val address: String,
) {
    private var socket: BluetoothSocket? = null

    @SuppressLint("MissingPermission") // 호출 전에 CONNECT 권한 있는 상태
    suspend fun connect() = withContext(Dispatchers.IO) {
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        val device = adapter.getRemoteDevice(address)
        val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
        s.connect() // 블로킹: 연결되거나 실패할 때까지 대기
        socket = s
    }

    suspend fun send(command: String, timeoutMs: Long = 3000): String =
        withContext(Dispatchers.IO) {
            val s = socket ?: error("Not Connected")

            s.outputStream.write("$command\r".toByteArray())
            s.outputStream.flush()

            val input = s.inputStream
            val buffer = StringBuilder()

            withTimeout(timeoutMs) {
                while (true) {
                    if (input.available() > 0) {
                        val c = input.read().toChar()
                        if (c == '>') break // ELM327 프롬프트 = 응답 끝
                        buffer.append(c)
                    } else {
                        delay(10)
                    }
                }
            }

            buffer.toString().replace("\r", "\n").trim()
        }

    fun close() {
        runCatching { socket?.close() }
        socket = null
    }

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}