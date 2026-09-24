package com.ucas.qingxin.signin.ui

/**
 * 主页二维码刷新策略（纯逻辑，便于单测）。
 *
 * ## 它在解决什么
 *
 * 1.2.0 里主页的二维码循环是 `while (isActive)` + 「睡到二维码过期」，
 * 也就是**每 5 秒一个状态写入、每 30 秒一次 HTTP**，而且**与前后台无关**：
 * 用户锁屏后它照样跑，一天约 2880 次请求。这是耗电报告里最大的单项之一。
 *
 * 关键观察是：**二维码只在签到窗口内才有意义**。窗口之外，后端本来就会拒绝签到
 * （见 `AttendanceRepository.uiStatus` 的 `NOT_STARTED` / `WAITING`），
 * 取回来的码扫了也没用。所以真正需要的是「把取码时刻对齐到签到窗口」，
 * 而不是「把 5 秒放宽成 30 秒」。
 *
 * ## 为什么两档用同一条规则
 *
 * 后端二维码是 **5 秒一帧**，把间隔放宽只会让用户扫到废码。
 * 因此省电不靠「窗口内少取」，而靠「窗口外一次都不取」——
 * 这条规则对普通档与省电档同样适用，所以它不参与 `PowerProfile`。
 */
internal object QrRefreshPolicy {

    /** 屏幕上有二维码时的心跳：只用来走倒计时环，不发网络。 */
    const val QR_TICK_MS = 1_000L

    /** 屏幕上没有二维码时的心跳：课程标签是分钟粒度，30 秒足够。 */
    const val IDLE_TICK_MS = 30_000L

    /** 窗口内的取码上限：与 `QrTimelineManager` 的 5 秒帧一致。 */
    const val ACTIVE_REFRESH_CAP_MS = 5_000L

    /** 任何 `delay` 都不小于这个值，避免忙等。 */
    private const val MIN_WAIT_MS = 200L

    /**
     * 一门课的签到窗口。
     *
     * @param startMs 窗口起点（本地毫秒）；`null` = 课程时间解析不出来，
     *   此时按「随时可以取码」处理，行为与 1.2.0 保持一致（这类课极少，但存在）。
     * @param endMs 窗口终点（本地毫秒）。
     */
    data class Window(val startMs: Long?, val endMs: Long?)

    sealed interface Decision {
        /** 立刻取一次二维码。 */
        data object Refresh : Decision

        /** 等 [ms] 毫秒再判定；**期间不发任何网络请求**。 */
        data class Wait(val ms: Long) : Decision

        /** 今天已经没有可签到的课：不启动循环。 */
        data object Idle : Decision
    }

    /** 心跳间隔：有二维码时 1 Hz（倒计时环需要），没有时 30 秒。 */
    fun tickMs(qrPresent: Boolean): Long = if (qrPresent) QR_TICK_MS else IDLE_TICK_MS

    /**
     * 决定「现在该取码、还是该等」。
     *
     * @param windows 所有**还没签到、且窗口尚未结束**的课（含时间解析不出来的）。
     *   已签到的课不该出现在这里：它们不需要新码（界面上仍会冻结显示最后一张）。
     * @param qrExpiresAtMs 当前这张二维码的失效时刻；没有码时为 `null`。
     */
    fun decide(
        nowMs: Long,
        visible: Boolean,
        windows: List<Window>,
        qrExpiresAtMs: Long?,
    ): Decision {
        if (windows.isEmpty()) return Decision.Idle
        // 不可见时不取码，只做一次低频回探 —— 上层通常已经直接 cancel 掉循环，
        // 这里兜底是为了避免「可见性标志没来得及更新」时变成忙循环。
        if (!visible) return Decision.Wait(IDLE_TICK_MS)

        val undated = windows.any { it.startMs == null || it.endMs == null }
        val inWindow = undated || windows.any { w ->
            val start = w.startMs ?: return@any false
            val end = w.endMs ?: return@any false
            nowMs >= start && nowMs <= end
        }
        if (inWindow) {
            val remaining = qrExpiresAtMs?.minus(nowMs) ?: 0L
            return if (remaining > 0L) {
                Decision.Wait(remaining.coerceIn(MIN_WAIT_MS, ACTIVE_REFRESH_CAP_MS))
            } else {
                Decision.Refresh
            }
        }

        // 窗口都还没开始：一觉睡到最近那次窗口开启，期间零网络。
        val nextStart = windows.asSequence()
            .mapNotNull { it.startMs }
            .filter { it > nowMs }
            .minOrNull()
            ?: return Decision.Idle // 有窗口但全都在过去 —— 今天没得签了
        return Decision.Wait((nextStart - nowMs).coerceAtLeast(MIN_WAIT_MS))
    }
}
