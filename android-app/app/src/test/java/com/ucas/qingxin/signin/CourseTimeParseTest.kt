package com.ucas.qingxin.signin

import com.ucas.qingxin.signin.course.CourseRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** 对齐原版 np0.a 的课程时间解析回归。 */
class CourseTimeParseTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val day = "20260915"

    @Test
    fun parsesPlainHmAndHms() {
        val a = CourseRepository.parseCourseInstant(day, "08:00")
        val b = CourseRepository.parseCourseInstant(day, "08:00:00")
        val c = CourseRepository.parseCourseInstant(day, "8:00")
        val expected = LocalDateTime.of(2026, 9, 15, 8, 0)
            .atZone(zone).toInstant().toEpochMilli()
        assertEquals(expected, a)
        assertEquals(expected, b)
        assertEquals(expected, c)
    }

    @Test
    fun parsesDatePrefixedLikeOriginalNp0() {
        val spaced = CourseRepository.parseCourseInstant(day, "2026-09-15 08:00:00")
        val iso = CourseRepository.parseCourseInstant(day, "2026-09-15T08:00:00")
        val frac = CourseRepository.parseCourseInstant(day, "2026-09-15 08:00:00.000")
        val expected = LocalDateTime.of(2026, 9, 15, 8, 0)
            .atZone(zone).toInstant().toEpochMilli()
        assertEquals(expected, spaced)
        assertEquals(expected, iso)
        assertEquals(expected, frac)
    }

    @Test
    fun parsesChineseColonAndDashedDay() {
        val t = CourseRepository.parseCourseInstant("2026-09-15", "08：30")
        assertNotNull(t)
        val expected = LocalDateTime.of(2026, 9, 15, 8, 30)
            .atZone(zone).toInstant().toEpochMilli()
        assertEquals(expected, t)
    }

    @Test
    fun blankReturnsNull() {
        assertNull(CourseRepository.parseCourseInstant(day, ""))
        assertNull(CourseRepository.parseCourseInstant(day, "   "))
    }
}
