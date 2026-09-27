package com.iyabong.forter.obd

object Pid {
    fun parseRpm(response: String): Int? {
        val data = dataBytes(response, "410C") ?: return null
        if (data.size < 2) return null
        return (data[0] * 256 + data[1]) / 4
    }

    fun parseSpeed(response: String): Int? {
        val data = dataBytes(response,"410D") ?: return null
        return data.firstOrNull()
    }

    // "410C1AF8" + prefix 떼고 두 글자씩 16진수 → [0x1A, 0xF8]
    private fun dataBytes(response: String, prefix: String): List<Int>? {
        val line = response.lines()
            .map { it.trim()}
            .firstOrNull { it.startsWith(prefix)} ?: return  null
        return line.removePrefix(prefix).chunked(2).mapNotNull { it.toIntOrNull(16)}
    }
}