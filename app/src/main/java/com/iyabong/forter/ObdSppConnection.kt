package com.iyabong.forter

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresPermission
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 클래식 SPP(RFCOMM)로 ELM327 어댑터에 연결한다.
 *
 * 세션이 끝나면 Downloads/Forter 에 두 개의 파일을 남긴다.
 *  - .csv : 시각, 속도, RPM, 사이클 ms  (분석용)
 *  - .log : 초기화 로그와 에러          (진단용)
 */
class ObdSppConnection(private val context: Context) {

    companion object {
        private const val TAG = "ObdSpp"
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val READ_TIMEOUT_MS = 2000L
    }

    interface Listener {
        fun onLog(line: String)
        fun onSample(speedKmh: Int?, rpm: Int?, cycleMs: Long)
        fun onStats(cycles: Int, avgCycleMs: Long, overruns: Int, errors: Int)
        fun onStopped(reason: String)
        /** 파일 저장 결과 — 실패하면 두 값 모두 null */
        fun onExported(csvName: String?, logName: String?)
    }

    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var worker: Thread? = null
    @Volatile private var running = false

    private val main = Handler(Looper.getMainLooper())
    private var listener: Listener? = null

    // 세션 동안 메모리에 모았다가 끝날 때 한 번에 쓴다
    private val logLines = mutableListOf<String>()
    private val csvRows = mutableListOf<String>()
    private var sessionStamp = ""

    private fun log(line: String) {
        Log.i(TAG, line)
        logLines.add(line)
        main.post { listener?.onLog(line) }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun start(device: BluetoothDevice, intervalMs: Long, l: Listener) {
        if (running) return
        listener = l
        running = true

        logLines.clear()
        csvRows.clear()
        sessionStamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.KOREA).format(Date())

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
            BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery()

            log("RFCOMM 연결 시도: ${device.address}")
            val s = try {
                device.createRfcommSocketToServiceRecord(SPP_UUID).also { it.connect() }
            } catch (e: IOException) {
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
            log("❌ ${e.javaClass.simpleName}: ${e.message ?: "(메시지 없음)"}")
            main.post { listener?.onStopped(e.message ?: "오류") }
        } finally {
            running = false
            closeQuietly()
            exportFiles()
        }
    }

    // ── ELM327 초기화 ──────────────────────────────────────────────────
    private fun initElm() {
        send("ATZ", 3000)
        send("ATE0", 1000)   // 에코 끄기
        send("ATL0", 1000)   // 줄바꿈 끄기
        send("ATS0", 1000)   // 공백 끄기
        send("ATSP0", 3000)  // 프로토콜 자동 감지
        log("── 초기화 완료 ──")
    }

    // ── 폴링 루프 ──────────────────────────────────────────────────────
    private fun pollLoop(intervalMs: Long) {
        var cycles = 0
        var overruns = 0
        var errors = 0
        var totalMs = 0L
        val recent = ArrayDeque<Long>()          // 최근 20 사이클

        val rowTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.KOREA)

        log("── 측정 시작 (주기 ${intervalMs}ms) ──")

        while (running && !Thread.currentThread().isInterrupted) {
            val started = System.currentTimeMillis()

            var speed: Int? = null
            var rpm: Int? = null

            try {
                speed = parseSpeed(send("010D", READ_TIMEOUT_MS))
                rpm = parseRpm(send("010C", READ_TIMEOUT_MS))
            } catch (e: InterruptedException) {
                break                            // 사용자가 중지한 것 — 실패가 아니다
            } catch (e: Exception) {
                errors++
                log("⚠ ${e.javaClass.simpleName}: ${e.message ?: "(메시지 없음)"}")
                if (!running) break
            }

            val cycleMs = System.currentTimeMillis() - started
            cycles++
            totalMs += cycleMs
            if (cycleMs > intervalMs) overruns++

            recent.addLast(cycleMs)
            if (recent.size > 20) recent.removeFirst()

            csvRows.add(
                "$cycles,$started,${rowTime.format(Date(started))}," +
                    "${speed ?: ""},${rpm ?: ""},$cycleMs"
            )

            main.post { listener?.onSample(speed, rpm, cycleMs) }

            if (cycles % 5 == 0) {
                val avg = totalMs / cycles
                val recentAvg = recent.average().toLong()
                val c = cycles; val o = overruns; val er = errors
                main.post { listener?.onStats(c, avg, o, er) }
                logLines.add("최근 ${recentAvg}ms · 누적 ${avg}ms · ${c}사이클 · 초과 $o · 실패 $er")
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

    // ── 파일 저장 ──────────────────────────────────────────────────────
    private fun exportFiles() {
        Thread.interrupted()                     // 인터럽트 플래그를 지워야 파일 IO가 안전하다

        if (csvRows.isEmpty() && logLines.isEmpty()) {
            main.post { listener?.onExported(null, null) }
            return
        }

        val csvName = "forter_$sessionStamp.csv"
        val logName = "forter_$sessionStamp.log"

        val csv = buildString {
            appendLine("cycle,epoch_ms,local_time,speed_kmh,rpm,cycle_ms")
            csvRows.forEach { appendLine(it) }
        }
        val logText = logLines.joinToString("\n")

        val okCsv = writeFile(csvName, "text/csv", csv)
        val okLog = writeFile(logName, "text/plain", logText)

        main.post {
            listener?.onExported(
                if (okCsv) csvName else null,
                if (okLog) logName else null
            )
        }
    }

    private fun writeFile(fileName: String, mime: String, content: String): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Downloads/Forter — 권한 없이 쓸 수 있고 파일 앱에서 바로 보인다
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/Forter"
                )
            }
            val uri = context.contentResolver
                .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri == null) false
            else {
                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(content.toByteArray(Charsets.UTF_8))
                }
                true
            }
        } else {
            // 구버전 대비 — 앱 전용 폴더
            val dir = context.getExternalFilesDir(null) ?: return false
            File(dir, fileName).writeText(content, Charsets.UTF_8)
            true
        }
    } catch (e: Exception) {
        Log.e(TAG, "파일 저장 실패: ${e.message}")
        false
    }

    // ── 송수신 ─────────────────────────────────────────────────────────
    private fun send(cmd: String, timeoutMs: Long): String {
        val out = output ?: throw IOException("소켓 없음")
        input?.let { while (it.available() > 0) it.read(ByteArray(it.available())) }

        out.write("$cmd\r".toByteArray())
        out.flush()

        val res = readUntilPrompt(timeoutMs)
        if (cmd.startsWith("AT")) log("$cmd → $res")
        return res
    }

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

    // ── 파싱 (ATS0 로 공백을 껐으므로 "410D32" 형태) ───────────────────
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