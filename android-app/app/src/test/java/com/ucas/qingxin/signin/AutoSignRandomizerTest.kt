package com.ucas.qingxin.signin

import com.ucas.qingxin.signin.attendance.AutoSignRandomizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 随机签到时刻的核心不变量。
 *
 * 这些断言对应「安全」与「会触发」两条底线：
 * 时刻必须落在 [开课前15分钟, 开课] 内，且**同一进程内重复取值必须完全一致**——
 * 否则闹钟每次重排都会把时刻往后推，最终永不触发。
 */
class AutoSignRandomizerTest {

    private val begin = 1_700_000_000_000L
    private val hour = 60L * 60L * 1000L

    @Before
    fun setUp() {
        AutoSignRandomizer.clearAll()
    }

    @Test
    fun `同一课次重复取值保持不变`() {
        val now = begin - 2 * hour
        val first = AutoSignRandomizer.targetMsFor("course-1", begin, now, "2026-09-16")
        repeat(20) {
            assertEquals(first, AutoSignRandomizer.targetMsFor("course-1", begin, now, "2026-09-16"))
        }
        assertEquals(first, AutoSignRandomizer.peek("course-1", "2026-09-16"))
    }

    @Test
    fun `随机时刻始终落在签到窗口内`() {
        val now = begin - 2 * hour
        repeat(300) { i ->
            AutoSignRandomizer.clearAll()
            val t = AutoSignRandomizer.targetMsFor("c$i", begin, now, "2026-09-16")
            assertTrue(
                "target=$t 应不早于开课前 15 分钟",
                t >= begin - AutoSignRandomizer.WINDOW_LEAD_MS,
            )
            assertTrue("target=$t 应不晚于开课时刻", t <= begin)
        }
    }

    @Test
    fun `临近开课时退化为立刻签到且不越界`() {
        val now = begin - 10_000L
        val t = AutoSignRandomizer.targetMsFor("late", begin, now, "2026-09-16")
        assertEquals(begin, t)
    }

    @Test
    fun `窗口刚开始时不会取到过去的时刻`() {
        val now = begin - AutoSignRandomizer.WINDOW_LEAD_MS
        val t = AutoSignRandomizer.targetMsFor("edge", begin, now, "2026-09-16")
        assertTrue("target=$t 必须留给当前时刻余量", t > now)
        assertTrue(t <= begin)
    }

    @Test
    fun `pruneBefore 只保留指定日期并让新课次重新随机`() {
        val day = "2026-09-16"
        AutoSignRandomizer.targetMsFor("a", begin, begin - hour, day)
        assertNotNull(AutoSignRandomizer.peek("a", day))

        AutoSignRandomizer.pruneBefore("2026-09-17")
        assertNull("跨天后旧课次的随机值必须被丢弃", AutoSignRandomizer.peek("a", day))

        val fresh = AutoSignRandomizer.targetMsFor("a", begin, begin - hour, "2026-09-17")
        assertTrue(fresh >= begin - AutoSignRandomizer.WINDOW_LEAD_MS)
        assertTrue(fresh <= begin)
    }

    @Test
    fun `forget 之后重新随机`() {
        val day = "2026-09-16"
        AutoSignRandomizer.targetMsFor("b", begin, begin - hour, day)
        AutoSignRandomizer.forget("b", day)
        assertNull(AutoSignRandomizer.peek("b", day))
    }

    @Test
    fun `deadline 为开课后 20 分钟`() {
        assertEquals(begin + 20 * 60 * 1000L, AutoSignRandomizer.deadlineMs(begin))
    }

    @Test
    fun `不同课次互相独立`() {
        val now = begin - 2 * hour
        val a = AutoSignRandomizer.targetMsFor("a", begin, now, "2026-09-16")
        val b = AutoSignRandomizer.targetMsFor("b", begin + 2 * hour, now, "2026-09-16")
        assertTrue(a <= begin)
        assertTrue(b <= begin + 2 * hour)
        assertEquals(a, AutoSignRandomizer.peek("a", "2026-09-16"))
        assertEquals(b, AutoSignRandomizer.peek("b", "2026-09-16"))
    }
}
