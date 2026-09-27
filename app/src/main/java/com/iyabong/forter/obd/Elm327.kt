package com.iyabong.forter.obd

import com.iyabong.forter.bluetooth.SppConnection

class Elm327(private val conn: SppConnection) {

    suspend fun init() {
        conn.send("ATZ", timeoutMs = 5000)  // 리셋: 모든 설정을 기본값으로
        for (cmd in listOf(
            "ATE0",     // 에코 끄기: 보낸 명령어 응답에 섞이지 않게
            "ATL0",     // 줄바꿈 끄기: \r만 사용
            "ATS0",     // 공백 끄기: "41 0C 1A F8" → "410C1AF8"
            "ATH0",     // 헤더 끄기: ECU 주소 없이 데이터만
            "ATSP0",    // 프로토콜 자동 탐색
        )) {
            val r = conn.send(cmd)
            check(r.contains("OK")) { "$cmd 실패: $r" }
        }
    }

    suspend fun raw(command: String, timeoutMs: Long = 3000): String =
        conn.send(command, timeoutMs)

    suspend fun rpm(): Int? = Pid.parseRpm(conn.send("010C"))

    suspend fun speed(): Int? = Pid.parseSpeed(conn.send("010D"))
}