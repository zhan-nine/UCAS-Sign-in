package com.ucas.qingxin.signin

import com.ucas.qingxin.signin.attendance.AutoSignRandomizer
import com.ucas.qingxin.signin.attendance.AutoSignSelector
import com.ucas.qingxin.signin.data.Course
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「该不该现在签」的纯逻辑取舍。
 *
 * 覆盖四种输入：窗口内 / 窗口外 / 已签到 / 时间不可解析。
 */
class AutoSignSelectorTest {

    private val begin = 1_700_000_000_000L
    private val minute = 60L * 1000L

    private fun course(id: String, signed: Boolean = false) = Course(
        id = id,
        uuid = id,
        name = "课程$id",
        teacher = "教师",
        beginTime = "08:00:00",
        endTime = "09:40:00",
        day = "20260916",
        signed = signed,
    )

    private val parseBegin: (Course) -> Long? = { begin }
    private val targetAt: (Long) -> ((Course, Long) -> Long) = { t -> { _, _ -> t } }

    @Test
    fun `随机时刻已到则选出该课`() {
        val c = course("A")
        val now = begin - 5 * minute
        assertEquals(c, AutoSignSelector.selectDue(listOf(c), now, parseBegin, targetAt(begin - 10 * minute)))
    }

    @Test
    fun `随机时刻未到则不签`() {
        val c = course("A")
        val now = begin - 5 * minute
        assertNull(AutoSignSelector.selectDue(listOf(c), now, parseBegin, targetAt(begin - minute)))
    }

    @Test
    fun `已签到的课不参与`() {
        val c = course("A", signed = true)
        val now = begin - 5 * minute
        assertNull(AutoSignSelector.selectDue(listOf(c), now, parseBegin, targetAt(begin - 10 * minute)))
    }

    @Test
    fun `开始时间不可解析的课被跳过`() {
        val c = course("A")
        val now = begin - 5 * minute
        assertNull(
            AutoSignSelector.selectDue(listOf(c), now, { null }, targetAt(begin - 10 * minute)),
        )
    }

    @Test
    fun `超过 deadline 之后不再签`() {
        val c = course("A")
        val now = begin + AutoSignRandomizer.GRACE_MS + minute
        assertNull(AutoSignSelector.selectDue(listOf(c), now, parseBegin, targetAt(begin)))
    }

    @Test
    fun `多节同时可签时取随机时刻最早的一节`() {
        val a = course("A")
        val b = course("B")
        val now = begin - 5 * minute
        val result = AutoSignSelector.selectDue(
            listOf(a, b),
            now,
            parseBegin,
            { c, _ -> if (c.id == "A") begin - 12 * minute else begin - 6 * minute },
        )
        assertEquals("A", result?.id)
    }

    @Test
    fun `容差范围内允许提前签到`() {
        val c = course("A")
        val now = begin - 20 * minute
        val target = now + 2 * minute
        assertNull(AutoSignSelector.selectDue(listOf(c), now, parseBegin, targetAt(target)))
        assertEquals(
            c,
            AutoSignSelector.selectDue(
                listOf(c),
                now,
                parseBegin,
                targetAt(target),
                toleranceMs = 5 * minute,
            ),
        )
    }

    @Test
    fun `selectNext 只挑随机时刻仍在未来的课`() {
        val a = course("A")
        val b = course("B")
        val now = begin - 5 * minute
        val picked = AutoSignSelector.selectNext(
            listOf(a, b),
            now,
            parseBegin,
            { c, _ -> if (c.id == "A") begin - 10 * minute else begin - minute },
        )
        assertEquals("B", picked?.first?.id)
        assertEquals(begin - minute, picked?.second)
    }

    @Test
    fun `selectNext 跳过已签到的课`() {
        val a = course("A")
        val b = course("B", signed = true)
        val now = begin - 5 * minute
        val picked = AutoSignSelector.selectNext(listOf(a, b), now, parseBegin, targetAt(begin - minute))
        assertEquals("A", picked?.first?.id)
    }
}
