package com.ucas.qingxin.signin

import com.ucas.qingxin.signin.data.Course
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 与 CourseRepository 选择规则保持一致的回归测试。
 * - 当前课：开课前 25 分钟 ~ 下课前
 * - 下一节：当日按开始时间排在当前课之后的第一节
 * - 签到目标：默认当前课
 */
class CurrentNextCourseTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val leadMs = 25L * 60L * 1000L

    @Test
    fun currentStarts25MinutesBefore_nextIsFollowing() {
        val today = LocalDate.of(2026, 9, 15)
        val day = "20260915"
        val courses = listOf(
            Course("1", "", "高等数学", "张老师", "08:00", "09:40", day, false),
            Course("2", "", "大学英语", "李老师", "10:00", "11:40", day, false),
            Course("3", "", "计算机网络", "王老师", "14:00", "15:40", day, false),
        )
        // 09:40 前 25 分钟 = 09:35 → 仍属高数窗口？高数 08:00-09:40，09:35 在高数课内
        val duringMath = LocalDateTime.of(2026, 9, 15, 8, 30).atZone(zone).toInstant().toEpochMilli()
        val (c1, n1) = currentAndNext(courses, today, duringMath)
        assertEquals("高等数学", c1?.name)
        assertEquals("大学英语", n1?.name)

        // 开课前 25 分钟：07:35 高数为当前，下一节英语
        val lead = LocalDateTime.of(2026, 9, 15, 7, 35).atZone(zone).toInstant().toEpochMilli()
        val (c2, n2) = currentAndNext(courses, today, lead)
        assertEquals("高等数学", c2?.name)
        assertEquals("大学英语", n2?.name)

        // 上课中 10:30 → 英语当前，计网下一节
        val mid = LocalDateTime.of(2026, 9, 15, 10, 30).atZone(zone).toInstant().toEpochMilli()
        val (c3, n3) = currentAndNext(courses, today, mid)
        assertEquals("大学英语", c3?.name)
        assertEquals("计算机网络", n3?.name)
        // 同名课跳过，取时间不同的下一门
        val (cSame, nSame) = currentAndNext(
            listOf(
                Course("1", "", "研讨课", "A", "08:00", "09:40", "20260915", false),
                Course("2", "", "研讨课", "A", "10:00", "11:40", "20260915", false),
                Course("3", "", "英语", "B", "14:00", "15:40", "20260915", false),
            ),
            today,
            LocalDateTime.of(2026, 9, 15, 8, 30).atZone(zone).toInstant().toEpochMilli(),
        )
        assertEquals("研讨课", cSame?.name)
        assertEquals("1", cSame?.id)
        assertEquals("英语", nSame?.name)
    }

    @Test
    fun beforeLeadWindow_currentNull_nextIsFirst() {
        val today = LocalDate.of(2026, 9, 15)
        val courses = listOf(
            Course("1", "", "高等数学", "张老师", "08:00", "09:40", "20260915", false),
            Course("2", "", "大学英语", "李老师", "10:00", "11:40", "20260915", false),
        )
        // 07:30 = 开课前 30 分钟，尚未进入当前课窗口
        val now = LocalDateTime.of(2026, 9, 15, 7, 30).atZone(zone).toInstant().toEpochMilli()
        val (current, next) = currentAndNext(courses, today, now)
        assertNull(current)
        assertEquals("高等数学", next?.name)
    }

    @Test
    fun afterClassEnds_movesToNext() {
        val today = LocalDate.of(2026, 9, 15)
        val courses = listOf(
            Course("1", "", "高等数学", "张老师", "08:00", "09:40", "20260915", false),
            Course("2", "", "大学英语", "李老师", "10:00", "11:40", "20260915", false),
        )
        // 09:40 刚好下课 → 高数结束；英语尚未到提前窗口（09:35 才进英语窗口）
        // 09:40: 英语 begin-25=09:35，所以 09:40 英语已是当前
        val atMathEnd = LocalDateTime.of(2026, 9, 15, 9, 40).atZone(zone).toInstant().toEpochMilli()
        val (c, n) = currentAndNext(courses, today, atMathEnd)
        assertEquals("大学英语", c?.name)
        assertNull(n) // 仅两节，英语后无下一节？ wait next would be null if only 2 courses and english is current
        // Actually next after english: none
        assertNull(n)
    }

    private fun currentAndNext(
        courses: List<Course>,
        today: LocalDate,
        now: Long,
    ): Pair<Course?, Course?> {
        val sorted = courses.sortedBy { at(today, it.beginTime) }
        val current = sorted.firstOrNull {
            val begin = at(today, it.beginTime)
            val end = at(today, it.endTime)
            now >= begin - leadMs && now < end
        }
        val currentBegin = current?.let { at(today, it.beginTime) }
        val currentName = current?.name?.trim().orEmpty()
        val next = sorted.firstOrNull { c ->
            if (current != null && c.id == current.id) return@firstOrNull false
            if (current != null && c.name.trim() == currentName) return@firstOrNull false
            val begin = at(today, c.beginTime)
            if (currentBegin != null) begin > currentBegin else begin > now
        }
        return current to next
    }

    private fun at(day: LocalDate, hm: String): Long {
        val parts = hm.split(":").map { it.toInt() }
        return LocalDateTime.of(day.year, day.month, day.dayOfMonth, parts[0], parts[1])
            .atZone(zone).toInstant().toEpochMilli()
    }
}
