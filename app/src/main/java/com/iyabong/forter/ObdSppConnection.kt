package com.iyabong.forter

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresPermission
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * 클래식 SPP(RFCOMM)로 ELM327 어댑터에 연결한다.
 *
 * 1단계 목적은 데이터 저장이 아니라 실측이다.
 *  - PID 1회 왕복 ms
 *  - 설정한 주기 안에 사이클을 다 못 돈 횟수
 *  - 실패/타임아웃 비율
 *
 * 이 숫자가 나와야 PID를 몇 개까지 늘릴지 계산으로 정할 수 있다.
 */
class ObdSppConnection {

    companion object {
        private const val TAG = "ObdSpp"
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val READ_TIMEOUT_MS = 2000L
    }

    interface Listener {
        fun onLog(line: String)
        /** 매 사이클 결과 */
        fun onSample(speedKmh: Int?, rpm: Int?, cycleMs: Long)
        /** 누적 통계 */
        fun onStats(cycles: Int, avgCycleMs: Long, overruns: Int, errors: Int)
        fun onStopped(reason: String)
    }

    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var worker: Thread? = null
    @Volatile private var running = false

    private val main = Handler(Looper.getMainLooper())
    private var listener: Listener? = null

    private fun log(line: String) {
        Log.i(TAG, line)
        main.post { listener?.onLog(line) }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun start(device: BluetoothDevice, intervalMs: Long, l: Listener) {
        if (running) return
        listener = l
        running = true

        worker = Thread { session(device, intervalMs) }.also { it.start() }
    }

    fun stop() {
        running = false
        worker?.interrupt()
        closeQuietly()
    }

    fun isRunning() = running

    // ── 세션 전체 ──────────────────────────────────────────────────────
    @SuppressLint("MissingPermission")
    private fun session(device: BluetoothDevice, intervalMs: Long) {
        try {
            // 탐색 중이면 연결이 매우 느려지거나 실패한다
            BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery()

            log("RFCOMM 연결 시도: ${device.address}")
            val s = try {
                device.createRfcommSocketToServiceRecord(SPP_UUID).also { it.connect() }
            } catch (e: IOException) {
                // 클론 어댑터가 SDP 조회에 실패할 때 쓰는 우회로 (채널 1 직접)
                log("표준 연결 실패, 채널 1로 재시도: ${e.message}")
                val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                (m.invoke(device, 1) as BluetoothSocket).also { it.connect() }
            }

            socket = s
            input = s.inputStream
            output = s.outputStream
            log("✅ 소켓 연결됨")

            initElm()
            pollLoop(intervalMs)

        } catch (e: Exception) {
            log("❌ ${e.javaClass.simpleName}: ${e.message}")
            main.post { listener?.onStopped(e.message ?: "오류") }
        } finally {
            running = false
            closeQuietly()
        }
    }

    // ── ELM327 초기화 ──────────────────────────────────────────────────
    private fun initElm() {
        // ATZ: 리셋 (부팅에 시간이 걸려 타임아웃을 길게)
        send("ATZ", 3000)
        send("ATE0", 1000)   // 에코 끄기 — 안 끄면 응답에 명령이 섞여 온다
        send("ATL0", 1000)   // 줄바꿈 끄기
        send("ATS0", 1000)   // 공백 끄기 → 파싱이 단순해진다
        send("ATSP0", 3000)  // 프로토콜 자동 감지
        log("── 초기화 완료 ──")
    }

    // ── 1초 루프 ───────────────────────────────────────────────────────
    private fun pollLoop(intervalMs: Long) {
        var cycles = 0
        var overruns = 0
        var errors = 0
        var totalMs = 0L

        log("── 측정 시작 (주기 ${intervalMs}ms) ──")

        while (running && !Thread.currentThread().isInterrupted) {
            val started = System.currentTimeMillis()

            var speed: Int? = null
            var rpm: Int? = null

            try {
                speed = parseSpeed(send("010D", READ_TIMEOUT_MS))
                rpm = parseRpm(send("010C", READ_TIMEOUT_MS))
            } catch (e: Exception) {
                errors++
                log("⚠ ${e.message}")
                if (!running) break
            }

            val cycleMs = System.currentTimeMillis() - started
            cycles++
            totalMs += cycleMs
            if (cycleMs > intervalMs) overruns++

            main.post { listener?.onSample(speed, rpm, cycleMs) }

            if (cycles % 5 == 0) {
                val avg = totalMs / cycles
                val c = cycles; val o = overruns; val er = errors
                main.post { listener?.onStats(c, avg, o, er) }
            }

            val remain = intervalMs - cycleMs
            if (remain > 0) {
                try { Thread.sleep(remain) } catch (e: InterruptedException) { break }
            }
        }

        val avg = if (cycles > 0) totalMs / cycles else 0
        log("── 종료 · $cycles 사이클 · 평균 ${avg}ms · 초과 $overruns · 실패 $errors ──")
        main.post { listener?.onStopped("정상 종료") }
    }

    // ── 송수신 ─────────────────────────────────────────────────────────
    private fun send(cmd: String, timeoutMs: Long): String {
        val out = output ?: throw IOException("소켓 없음")
        // 남은 찌꺼기 비우기
        input?.let { while (it.available() > 0) it.read(ByteArray(it.available())) }

        out.write("$cmd\r".toByteArray())
        out.flush()

        val res = readUntilPrompt(timeoutMs)
        if (cmd.startsWith("AT")) log("$cmd → $res")
        return res
    }

    /** '>' 프롬프트가 올 때까지 읽는다. ELM327은 응답 끝에 항상 이걸 보낸다. */
    private fun readUntilPrompt(timeoutMs: Long): String {
        val ins = input ?: throw IOException("소켓 없음")
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs

        while (System.currentTimeMillis() < deadline) {
            val avail = ins.available()
            if (avail > 0) {
                val buf = ByteArray(avail)
                val n = ins.read(buf)
                for (i in 0 until n) {
                    val c = buf[i].toInt().toChar()
                    if (c == '>') return sb.toString().trim()
                    if (c != '\r' && c != '\n') sb.append(c)
                }
            } else {
                Thread.sleep(4)
            }
        }
        throw IOException("타임아웃 (받은 것: ${sb.toString().trim()})")
    }

    // ── 파싱 ───────────────────────────────────────────────────────────
    // ATS0 으로 공백을 껐으므로 "410D32" 형태로 온다
    private fun parseSpeed(raw: String): Int? {
        val hex = raw.replace(" ", "").uppercase()
        val i = hex.indexOf("410D")
        if (i < 0 || hex.length < i + 6) return null
        return hex.substring(i + 4, i + 6).toIntOrNull(16)
    }

    private fun parseRpm(raw: String): Int? {
        val hex = raw.replace(" ", "").uppercase()
        val i = hex.indexOf("410C")
        if (i < 0 || hex.length < i + 8) return null
        val a = hex.substring(i + 4, i + 6).toIntOrNull(16) ?: return null
        val b = hex.substring(i + 6, i + 8).toIntOrNull(16) ?: return null
        return (a * 256 + b) / 4
    }

    private fun closeQuietly() {
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { socket?.close() }
        input = null; output = null; socket = null
    }
}