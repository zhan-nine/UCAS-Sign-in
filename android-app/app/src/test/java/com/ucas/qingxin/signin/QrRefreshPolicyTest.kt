package com.ucas.qingxin.signin

import com.ucas.qingxin.signin.ui.QrRefreshPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 二维码取码策略。
 *
 * 1.2.0 的行为是「每 5 秒一次、与前后台无关」，一天约 2880 次请求。
 * 这组用例把新规则的边界钉住：**只有「主页可见 + 在签到窗口内」才允许发网络**，
 * 窗口之外一次都不许发。
 */
class QrRefreshPolicyTest {

    private val hour = 60L * 60_000L
    private val begin = 1_700_000_000_000L
    private val lead = 25L * 60_000L
    private val end = begin + 45L * 60_000L

    private fun window(startMs: Long? = begin - lead, endMs: Long? = end) =
        QrRefreshPolicy.Window(startMs, endMs)

    private fun decide(
        nowMs: Long,
        visible: Boolean = true,
        windows: List<QrRefreshPolicy.Window> = listOf(window()),
        qrExpiresAtMs: Long? = null,
    ) = QrRefreshPolicy.decide(nowMs, visible, windows, qrExpiresAtMs)

    @Test
    fun `心跳间隔 有码 1 秒 无码 30 秒`() {
        assertEquals(1_000L, QrRefreshPolicy.tickMs(qrPresent = true))
        assertEquals(30_000L, QrRefreshPolicy.tickMs(qrPresent = false))
    }

    @Test
    fun `今天没有可签的课 直接 Idle 不启动循环`() {
        assertEquals(QrRefreshPolicy.Decision.Idle, decide(nowMs = begin, windows = emptyList()))
    }

    @Test
    fun `不可见时不取码，只低频回探`() {
        val decision = decide(nowMs = begin, visible = false)
        assertEquals(QrRefreshPolicy.Decision.Wait(QrRefreshPolicy.IDLE_TICK_MS), decision)
    }

    @Test
    fun `窗口内没有码 立刻取一次`() {
        assertEquals(QrRefreshPolicy.Decision.Refresh, decide(nowMs = begin))
    }

    @Test
    fun `窗口内码快过期 等到过期那一刻再取`() {
        val decision = decide(nowMs = begin, qrExpiresAtMs = begin + 3_200L)
        assertEquals(QrRefreshPolicy.Decision.Wait(3_200L), decision)
    }

    @Test
    fun `窗口内码还很新 仍然按 5 秒上限重取`() {
        // 后端二维码是 5 秒一帧，等太久只会让用户扫到废码，所以上限锁在 5 秒。
        val decision = decide(nowMs = begin, qrExpiresAtMs = begin + 60_000L)
        assertEquals(QrRefreshPolicy.Decision.Wait(QrRefreshPolicy.ACTIVE_REFRESH_CAP_MS), decision)
    }

    @Test
    fun `窗口内码刚好在现在过期 立刻重取`() {
        assertEquals(QrRefreshPolicy.Decision.Refresh, decide(nowMs = begin, qrExpiresAtMs = begin))
    }

    @Test
    fun `窗口未开始 一觉睡到窗口开启 且不发网络`() {
        val now = begin - 2 * hour
        assertEquals(QrRefreshPolicy.Decision.Wait(2 * hour - lead), decide(nowMs = now))
    }

    @Test
    fun `窗口起点是闭区间 到点就该取码`() {
        assertEquals(QrRefreshPolicy.Decision.Refresh, decide(nowMs = begin - lead))
    }

    @Test
    fun `窗口终点是闭区间 下课后一秒不再取码`() {
        assertEquals(QrRefreshPolicy.Decision.Refresh, decide(nowMs = end))
        assertEquals(QrRefreshPolicy.Decision.Idle, decide(nowMs = end + 1L))
    }

    @Test
    fun `今天所有窗口都已过去 Idle`() {
        assertEquals(QrRefreshPolicy.Decision.Idle, decide(nowMs = end + hour))
    }

    @Test
    fun `只看还没开始的那个窗口 取最近的`() {
        val windows = listOf(
            window(startMs = begin + 5 * hour, endMs = begin + 6 * hour),
            window(startMs = begin + 2 * hour, endMs = begin + 3 * hour),
        )
        // 当前在第一个窗口结束之后、两个未来窗口之前。
        val now = begin + hour
        assertEquals(QrRefreshPolicy.Decision.Wait(hour), decide(nowMs = now, windows = windows))
    }

    @Test
    fun `任一门课在窗口内就取码 即使另一门课还没开始`() {
        val windows = listOf(
            window(startMs = begin - lead, endMs = end),
            window(startMs = begin + 5 * hour, endMs = begin + 6 * hour),
        )
        assertEquals(QrRefreshPolicy.Decision.Refresh, decide(nowMs = begin, windows = windows))
    }

    @Test
    fun `课程时间解析不出来时按随时可取处理`() {
        // 脏数据不该导致「永远不出码」：这类课和 1.2.0 一样直接取。
        val undated = listOf(QrRefreshPolicy.Window(startMs = null, endMs = null))
        assertEquals(QrRefreshPolicy.Decision.Refresh, decide(nowMs = begin, windows = undated))
    }

    @Test
    fun `任何 Wait 都不会小于 200 毫秒`() {
        // 防忙等：即便窗口起点就在 1 毫秒后，也要给一个最小等待。
        val justBefore = begin - lead - 1L
        val decision = decide(nowMs = justBefore)
        assertTrue(decision is QrRefreshPolicy.Decision.Wait)
        assertTrue((decision as QrRefreshPolicy.Decision.Wait).ms >= 200L)
    }
}
