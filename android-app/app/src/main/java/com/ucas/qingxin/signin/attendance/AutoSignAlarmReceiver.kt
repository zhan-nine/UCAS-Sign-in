package com.ucas.qingxin.signin.attendance

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ucas.qingxin.signin.QingxinApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 自动签到闹钟接收器（每节课**仅一个**闹钟，见 [AttendanceScheduler]）。
 *
 * 职责很窄：把进程叫醒，然后把活儿交给「守护服务 + 执行引擎」。
 * 之所以不在这里做重试，是因为 `goAsync()` 只适合几秒级的短任务
 * （`setExactAndAllowWhileIdle` 给的 partial wakelock 也只有 10 秒量级），
 * 而窗口内的重试需要跨 60–90 秒的间隔。
 *
 * 低耗电模式下没有守护服务，因此只能消耗本次唤醒的时间片做一次尝试 —— 这是
 * 用户主动选择的取舍（该模式明确放弃秒级精度与重试）。
 */
class AutoSignAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_AUTO_SIGN_ALARM) return
        val appContext = context.applicationContext
        val app = appContext as? QingxinApp ?: return
        if (!app.isReady) return
        val settings = app.attendanceScheduler.getSettings()
        if (!settings.autoSignEnabled) return

        val pendingResult = goAsync()
        scope.launch {
            try {
                if (!settings.lowPowerMode) {
                    // 拉起守护服务：由它在进程内精确定时并负责窗口内的重试。
                    AttendanceDaemonService.start(appContext)
                }
                withTimeoutOrNull(WATCHDOG_MS) {
                    AutoSignWindowRunner.run(appContext, maxRounds = Int.MAX_VALUE)
                }
            } catch (_: Throwable) {
                // 闹钟回调绝不允许抛出：否则会被系统记为「糟糕的 App」。
            } finally {
                runCatching { AttendanceScheduler.ensureSchedule(appContext) }
                runCatching { pendingResult.finish() }
            }
        }
    }

    companion object {
        const val ACTION_AUTO_SIGN_ALARM = "com.ucas.qingxin.signin.action.AUTO_SIGN_ALARM"

        /** 硬超时：`goAsync()` 不能长时间占用，超时后立刻归还控制权。 */
        private const val WATCHDOG_MS = 18_000L

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
