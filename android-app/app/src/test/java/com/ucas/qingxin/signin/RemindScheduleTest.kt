package com.ucas.qingxin.signin

import com.ucas.qingxin.signin.attendance.AutoSignEngine
import com.ucas.qingxin.signin.attendance.AutoSignRandomizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「上课前 15 分钟」提醒的时刻计算。
 *
 * 这组用例锁住两件事：
 * 1. 提醒**固定相对上课时刻**（用户可预知），不再相对随机签到时刻；
 * 2. 提醒永远不会晚于签到时刻（宁可提早，不可迟到）——
 *    因为随机签到时刻的下界正好也是「上课前 15 分钟」。
 */
class RemindScheduleTest {

    private val minute = 60_000L
    private val begin = 1_700_000_000_000L

    private fun remindAt(targetMs: Long) = AutoSignEngine.remindAtFor(begin, targetMs)

    @Test
    fun `提醒时刻是上课前 15 分钟`() {
        assertEquals(15L * minute, AutoSignEngine.UPCOMING_LEAD_MS)
        // 随机签到时刻落在窗口正中：提醒就严格是上课前 15 分钟。
        val target = begin - 8L * minute
        assertEquals(begin - 15L * minute, remindAt(target))
    }

    @Test
    fun `签到时刻越晚 提醒依然是上课前 15 分钟`() {
        // 随机上界是「上课前 30 秒」。
        assertEquals(begin - 15L * minute, remindAt(begin - 30_000L))
    }

    @Test
    fun `签到时刻正好等于上课前 15 分钟时 提醒提前到不撞车`() {
        // AutoSignRandomizer 的下界就是 begin - 15 分钟，这种撞车是真实可达的。
        val target = begin - 15L * minute
        val remindAt = remindAt(target)
        assertTrue("提醒必须早于签到", remindAt < target)
        assertEquals(target - minute, remindAt)
    }

    @Test
    fun `提醒永远落在签到窗口之内`() {
        // 签到窗口从上课前 25 分钟开启（QingxinApiService.SIGN_WINDOW_LEAD_MS）；
        // 提醒若掉到窗口之外，用户收到通知却签不了，那是错的。
        val windowOpen = begin - 25L * minute
        // 下界情形：随机时刻被推到最早。
        assertTrue(remindAt(begin - 15L * minute) >= windowOpen)
        // 上界情形：随机时刻接近上课。
        assertTrue(remindAt(begin - 30_000L) >= windowOpen)
    }

    @Test
    fun `提醒不会晚于签到时刻`() {
        // 遍历整个随机区间，任何取值都必须满足这一条。
        var target = begin - AutoSignRandomizer.WINDOW_LEAD_MS
        val upper = begin - 30_000L
        while (target <= upper) {
            assertTrue("target=$target", remindAt(target) < target)
            target += minute
        }
    }

    @Test
    fun `提醒窗口上界是签到时刻而不是固定 15 分钟`() {
        // 这条曾经是个陷阱：若把「有效提醒区间」写成固定 15 分钟，
        // 当随机签到时刻比提醒点晚十几分钟（最晚上课前 30 秒）时，
        // 提醒会被判定越界而整体丢失。
        val target = begin - 30_000L
        val remindAt = remindAt(target)
        assertTrue("提醒点比签到早 14.5 分钟以上", target - remindAt > 14L * minute)
    }
}
