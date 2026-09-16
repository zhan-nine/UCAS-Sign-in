package com.ucas.qingxin.signin.attendance

import android.app.Notification
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ucas.qingxin.signin.QingxinApp
import com.ucas.qingxin.signin.R
import com.ucas.qingxin.signin.widget.WidgetRefreshScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 自动签到守护服务（前台服务，类型 `specialUse`）。
 *
 * 为什么是 `specialUse` 而不是 `dataSync`：
 * - Android 15 起 `dataSync` 前台服务有 **24 小时内共 6 小时**配额，超时即被系统停止，
 *   撑不住整天的常驻通知；
 * - Android 15 起 `BOOT_COMPLETED` 接收器**禁止**启动 `dataSync` 类型前台服务，
 *   而 `specialUse` 不在禁止名单内（否则开机自启必然抛异常）。
 *
 * 省电设计（关键）：
 * - **零轮询**：不做固定间隔循环。协程 `delay` 不持 wakelock、不唤醒 CPU，
 *   因此「睡到下一个随机签到时刻」在设备休眠期间的真实功耗约为 0；
 *   精确唤醒由 [AutoSignAlarmReceiver] 的闹钟负责（每节课仅 1 次）。
 * - 长 `delay` 只用来做「前置提醒」与「到点签到」两件事，且提醒**不占额外唤醒**。
 * - `onDestroy` 会把 [WidgetRefreshScheduler.setDaemonActive] 复位，让兜底闹钟
 *   恢复到正常频率。
 */
class AttendanceDaemonService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AttendanceNotifier.ensureChannels(this)
        val app = application as? QingxinApp
        if (app == null || !app.isReady || !app.attendanceScheduler.getSettings().autoSignEnabled) {
            // 未开启自动签到 / 依赖不可用：不留常驻服务。
            // 必须先 startForeground 再停 —— 若本次是由 startForegroundService 拉起，
            // 直接返回会在约 5 秒后触发「did not call startForeground」崩溃。
            val text = getString(R.string.auto_sign_status_waiting)
            runCatching { startForegroundCompat(fallbackNotification(text)) }
            runCatching { stopForeground(Service.STOP_FOREGROUND_REMOVE) }
            stopSelf()
            return START_NOT_STICKY
        }

        val text = runCatching { AutoSignEngine.daemonStatusText(this, app) }
            .getOrDefault(getString(R.string.auto_sign_status_waiting))
        val notification = runCatching { app.notifier.daemonNotification(text) }
            .getOrElse { fallbackNotification(text) }

        try {
            startForegroundCompat(notification)
        } catch (t: Throwable) {
            // 系统拒绝（前台服务限制 / 配额 / 权限）：降级为「纯闹钟 + WorkManager」模式，
            // 绝不因此让进程崩溃 —— 小部件宿主也会冷启动本进程。
            app.attendanceScheduler.markDaemonBlocked(true)
            stopSelf()
            return START_NOT_STICKY
        }

        app.attendanceScheduler.markDaemonBlocked(false)
        WidgetRefreshScheduler.setDaemonActive(this, true)
        arm()
        return START_STICKY
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                AttendanceNotifier.ID_DAEMON,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(AttendanceNotifier.ID_DAEMON, notification)
        }
    }

    override fun onDestroy() {
        loopJob?.cancel()
        scope.cancel()
        WidgetRefreshScheduler.setDaemonActive(this, false)
        (application as? QingxinApp)?.notifier?.clearDaemonStatus()
        super.onDestroy()
    }

    /**
     * 排下一次「提醒 + 签到」。
     *
     * 两条分支，**都不是轮询**：
     * 1. 已有到期未处理的课次 → 立刻尝试一次，失败则按 60–90 秒节奏再来（预算由引擎计数）；
     * 2. 否则 `delay` 睡到下一个随机时刻（`delay` 不持 wakelock，休眠期间真实功耗 ≈ 0）。
     *
     * 无事可做（今日已签完 / 无课）时直接退出循环，把后续交给闹钟与 WorkManager。
     */
    private fun arm() {
        loopJob?.cancel()
        val app = application as? QingxinApp ?: return
        loopJob = scope.launch {
            while (isActive) {
                if (!app.attendanceScheduler.getSettings().autoSignEnabled) break
                runCatching { AutoSignEngine.refreshDaemonStatus(applicationContext, app, null) }

                // 1) 到期未处理 → 尝试（含窗口内重试的节奏控制）
                val due = runCatching { AutoSignEngine.dueNow(applicationContext, app) }.getOrNull()
                if (due != null) {
                    runCatching { AutoSignEngine.runOnce(applicationContext, app) }
                    runCatching { AttendanceScheduler.ensureSchedule(applicationContext) }
                    delay(AutoSignWindowRunner.retryDelayMs())
                    continue
                }

                // 2) 睡到下一个随机时刻
                val next = runCatching { AutoSignEngine.nextDue(app) }.getOrNull() ?: break
                val remindAt = maxOf(
                    next.targetMs - AutoSignEngine.UPCOMING_LEAD_MS,
                    next.beginMs - AutoSignRandomizer.WINDOW_LEAD_MS,
                )
                if (remindAt > System.currentTimeMillis()) {
                    delay(remindAt - System.currentTimeMillis())
                    if (!isActive) break
                    runCatching {
                        app.notifier.notifyAutoSignUpcoming(
                            next.course.id,
                            next.course.name,
                            next.targetMs,
                        )
                    }
                }

                val wait = next.targetMs - System.currentTimeMillis()
                if (wait > 0) delay(wait)
                // 到点：立即尝试一次，随后回到循环开头由「到期」分支接管重试。
                runCatching { AutoSignEngine.runOnce(applicationContext, app) }
                runCatching { AttendanceScheduler.ensureSchedule(applicationContext) }
            }
        }
    }

    private fun fallbackNotification(text: String): Notification =
        NotificationCompat.Builder(this, AttendanceNotifier.CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.auto_sign_daemon_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()

    companion object {
        /** 启动守护服务。任何启动失败都被吞掉：自动签到的正确性由闹钟兜底。 */
        fun start(context: Context) {
            val appContext = context.applicationContext
            val app = appContext as? QingxinApp
            val ok = runCatching {
                val intent = Intent(appContext, AttendanceDaemonService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appContext.startForegroundService(intent)
                } else {
                    appContext.startService(intent)
                }
            }.isSuccess
            if (!ok) {
                // 前台服务被系统拒绝（后台启动限制 / 厂商策略）：UI 据此给出保活引导。
                runCatching {
                    app?.takeIf { it.isReady }?.attendanceScheduler?.markDaemonBlocked(true)
                }
                app?.let { WidgetRefreshScheduler.setDaemonActive(appContext, false) }
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.applicationContext.stopService(
                    Intent(context.applicationContext, AttendanceDaemonService::class.java),
                )
            }
        }
    }
}

/**
 * 开机自启。
 *
 * 注意：Android 15 起 `BOOT_COMPLETED` 接收器**不允许**启动 `dataSync` 等类型的前台服务，
 * 本服务使用 `specialUse` 因此不受该限制；但仍然整体包在 `runCatching` 里，
 * 保证任何机型上的失败都不会导致「开机崩溃」。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as? QingxinApp ?: return
        if (!app.isReady) return
        // 开机广播能收到 = 自启动确实被允许（Android 没有公开查询 API，用这个信号代替）。
        runCatching { app.attendanceScheduler.markBootSeen() }
        if (app.attendanceScheduler.getSettings().autoSignEnabled) {
            runCatching { app.attendanceScheduler.start() }
        }
    }
}
