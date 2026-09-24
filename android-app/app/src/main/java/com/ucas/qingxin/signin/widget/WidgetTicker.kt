package com.ucas.qingxin.signin.widget

import android.content.Context
import com.ucas.qingxin.signin.attendance.PowerProfiles
import com.ucas.qingxin.signin.attendance.ResolvedPowerProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 进程内定时器：每分钟做一次**纯本地重绘**，让「当前课 / 下一节 / 可签到」
 * 这些随时间自然变化的内容及时跟手。
 *
 * 为什么需要它：小部件的显示内容是**时间的函数**——19:59 与 20:01 可能分别是
 * 「下一节」和「当前可签到」。而系统只在特定事件回调 provider，闹钟最细也只能到
 * 分钟级且会被 Doze 推迟。只要应用进程还活着，一次本地 tick 就能让显示跟上时间。
 *
 * ## 运行策略（避免常驻耗电）
 * - 应用在**前台** → 持续运行。此时屏幕亮着，这点本地重绘的代价被屏幕功耗淹没；
 * - 应用退到后台 → 按档位保留一段宽限（[ResolvedPowerProfile.widgetGraceMs]）再停；
 *   **省电档的宽限是 0，即立刻停**；
 * - **不再由闹钟 / WorkManager 唤起时启动**（1.2.1 起）：唤起方自己已经重绘过一遍，
 *   再撑 3 分钟 ticker 只会平白多出几分钟的进程内唤醒，而这段时间里没有任何新边界要跨。
 * - 协程**不会**阻止进程被回收。进程被回收后由 [WidgetRefreshScheduler] 的
 *   边界闹钟与 WorkManager 接手。
 */
internal object WidgetTicker {

    private const val TICK_MS = 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var autoStopJob: Job? = null

    @Volatile
    private var foreground = false

    fun onAppForeground(context: Context) {
        foreground = true
        autoStopJob?.cancel()
        startIfNeeded(context)
    }

    fun onAppBackground(context: Context) {
        foreground = false
        val appContext = context.applicationContext
        if (WidgetHostCompat.widgetCount(appContext) == 0) {
            stop()
            return
        }
        scheduleAutoStop(appContext)
    }

    /**
     * 档位变化时调用：宽限时长可能从「60 秒」变成「0（不使用）」，需要重新评估。
     *
     * 省电档下若此刻在后台，就立刻停掉 —— 用户点了「省电模式」之后
     * 还让 ticker 再跑一分钟，与这个开关的承诺不符。
     */
    fun onProfileChanged(context: Context) {
        if (foreground) return
        val appContext = context.applicationContext
        if (WidgetHostCompat.widgetCount(appContext) == 0) {
            stop()
            return
        }
        scheduleAutoStop(appContext)
    }

    fun startIfNeeded(context: Context) {
        val appContext = context.applicationContext
        if (WidgetHostCompat.widgetCount(appContext) == 0) {
            stop()
            return
        }
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                // 单次 tick 的异常绝不能终止循环，否则小部件会在剩余进程生命周期内
                // 彻底不再随时间更新。
                runCatching { WidgetRefreshScheduler.tick(appContext) }
                delay(TICK_MS)
            }
        }
    }

    fun stop() {
        autoStopJob?.cancel()
        autoStopJob = null
        job?.cancel()
        job = null
    }

    /** 按档位安排自动停止；宽限为 0 表示「这个档位不使用后台 ticker」，立刻停。 */
    private fun scheduleAutoStop(appContext: Context) {
        autoStopJob?.cancel()
        val graceMs = PowerProfiles.forContext(appContext).widgetGraceMs
        if (graceMs <= 0L) {
            stop()
            return
        }
        autoStopJob = scope.launch {
            delay(graceMs)
            if (!foreground) stop()
        }
    }
}
