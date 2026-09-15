package com.ucas.qingxin.signin.util

/**
 * 课程展示用：只保留时刻，去掉日期前缀。
 * 例如 `2026-09-15 08:00:00` → `08:00`，`8:00` → `08:00`。
 */
object CourseTimeDisplay {
    fun hm(raw: String): String {
        val t = raw.trim().replace('：', ':')
        Regex("""(\d{1,2}):(\d{2})""").find(t)?.let { m ->
            return "%02d:%02d".format(m.groupValues[1].toInt(), m.groupValues[2].toInt())
        }
        val digits = t.replace(Regex("\\D"), "")
        return when {
            digits.length >= 4 -> "${digits.take(2)}:${digits.substring(2, 4)}"
            else -> t.ifBlank { "—" }
        }
    }

    fun range(begin: String, end: String): String = "${hm(begin)}–${hm(end)}"
}
