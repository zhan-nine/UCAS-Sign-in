package com.ucas.qingxin.signin

import com.ucas.qingxin.signin.attendance.AutoSignEngine
import com.ucas.qingxin.signin.attendance.AutoSignRandomizer
import com.ucas.qingxin.signin.attendance.AutoSignSelector
import com.ucas.qingxin.signin.data.Course
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoSignDaemonPolicyTest {
    private val minute = 60_000L
    private val begin = 1_700_000_000_000L
    private val target = begin - 5 * minute
    private val lookahead = 10 * minute
    private val course = Course("1234567", "", "Course", "Teacher", "08:00", "09:40", "20260916", false)

    private fun needed(now: Long, courses: List<Course> = listOf(course)): Boolean =
        AutoSignSelector.hasDaemonWork(courses, now, { begin }, { _, _ -> target }, lookahead)

    @Test
    fun `last course keeps daemon alive when its alarm becomes due`() {
        assertTrue(needed(target - 1))
        assertTrue(needed(target))
        assertTrue(needed(target + minute))
        // Alarm scheduling must still avoid re-enqueuing this past target.
        assertNull(AutoSignSelector.selectNext(listOf(course), target, { begin }, { _, _ -> target }))
    }

    @Test
    fun `retry window includes deadline but ends immediately after it`() {
        val deadline = AutoSignRandomizer.deadlineMs(begin)
        assertTrue(needed(deadline))
        assertFalse(needed(deadline + 1))
    }

    @Test
    fun `future course starts daemon only within reminder lookahead`() {
        val start = AutoSignEngine.remindAtFor(begin, target) - lookahead
        assertFalse(needed(start - 1))
        assertTrue(needed(start))
    }

    @Test
    fun `lookahead respects reminder gap for earliest random target`() {
        val earlyTarget = begin - AutoSignRandomizer.WINDOW_LEAD_MS
        val start = AutoSignEngine.remindAtFor(begin, earlyTarget) - lookahead
        fun neededAt(now: Long) = AutoSignSelector.hasDaemonWork(
            listOf(course), now, { begin }, { _, _ -> earlyTarget }, lookahead,
        )
        assertFalse(neededAt(start - 1))
        assertTrue(neededAt(start))
    }

    @Test
    fun `distant future course does not hide the course needing retries`() {
        val later = course.copy(id = "7654321")
        assertTrue(
            AutoSignSelector.hasDaemonWork(
                listOf(later, course), target + minute,
                { if (it.id == later.id) begin + 4 * 60 * minute else begin },
                { _, start -> start - 5 * minute }, lookahead,
            ),
        )
        assertFalse(
            AutoSignSelector.hasDaemonWork(
                listOf(later), target + minute, { begin + 4 * 60 * minute },
                { _, start -> start - 5 * minute }, lookahead,
            ),
        )
    }

    @Test
    fun `signed absent and unparseable courses cannot keep daemon alive`() {
        assertFalse(needed(target, emptyList()))
        assertFalse(needed(target, listOf(course.copy(signed = true))))
        assertFalse(
            AutoSignSelector.hasDaemonWork(listOf(course), target, { null }, { _, _ -> target }, lookahead),
        )
    }
}
