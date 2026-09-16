package com.ucas.qingxin.signin.widget

import android.content.Context
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
 * 运行策略（避免常驻耗电）：
 * - 应用在前台 → 持续运行；
 * - 退到后台 / 由闹钟或 WorkManager 唤起 → 只运行一段宽限期（[GRACE_MS]）后自动停止；
 * - 协程**不会**阻止进程被回收。进程被回收后由 [WidgetRefreshScheduler] 的
 *   边界闹钟与 WorkManager 接手。
 */
internal object WidgetTicker {

    private const val TICK_MS = 60_000L

    /** 后台/被唤起后保持精确更新的宽限时长。 */
    private const val GRACE_MS = 3L * 60L * 1000L

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
        if (WidgetHostCompat.widgetCount(context.applicationContext) == 0) {
            stop()
        } else {
            scheduleAutoStop(GRACE_MS)
        }
    }

    /** 被闹钟 / WorkManager 唤起时调用：跑一段宽限期后自动停。 */
    fun startForGrace(context: Context) {
        startIfNeeded(context)
        if (!foreground) scheduleAutoStop(GRACE_MS)
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

    private fun scheduleAutoStop(graceMs: Long) {
        autoStopJob?.cancel()
        autoStopJob = scope.launch {
            delay(graceMs)
            if (!foreground) stop()
        }
    }
}
