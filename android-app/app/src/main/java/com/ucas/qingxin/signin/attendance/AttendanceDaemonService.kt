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
 * ## 按需存活（1.2.1 起，这一条是耗电治理的核心）
 *
 * 本服务**绝大多数时间不应该在跑**。它只在「临近签到窗口」时由
 * [AttendanceScheduler.syncDaemon] 或签到槽闹钟拉起，签完（或当天已无待签课）
 * 就 `stopSelf`。原因很硬：它是 `START_STICKY` 的前台服务，一旦常驻，
 * 系统就**既不冻结也不回收**这个进程 —— 与前台无关的循环（倒计时、二维码轮询、
 * 小部件重绘）于是能整夜满速运行。这正是 1.2.0 里「权限给得越全、反而越耗电、
 * 而开发机复现不到」的直接原因。
 *
 * ## 省电设计（关键）
 * - **零轮询**：不做固定间隔循环。协程 `delay` 不持 wakelock、不唤醒 CPU，
 *   因此「睡到下一个随机签到时刻」在设备休眠期间的真实功耗约为 0；
 *   精确唤醒由 [AutoSignAlarmReceiver] 的闹钟负责。
 * - 提醒由**独立的提醒槽闹钟**发出，不再依赖本服务是否存活
 *   （省电档没有本服务，1.2.0 的写法会让省电档完全收不到提醒）。
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
        if (app == null || !app.isReady || !AutoSignEngine.shouldRunDaemon(app)) {
            // 不该常驻：未开启自动签到、依赖不可用、省电档、或今天已经没有待签课程。
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
     * 无事可做（今日已签完 / 无课）时**主动 `stopSelf()`**，而不是只退出循环：
     * 本服务是前台服务，`START_STICKY` 下只要不 `stopSelf`，系统就会一直保着这个进程
     * 不被冻结 —— 那正是 1.2.0 里整夜耗电的根源（见类注释）。
     */
    private fun arm() {
        loopJob?.cancel()
        val app = application as? QingxinApp ?: return
        loopJob = scope.launch {
            try {
                while (isActive) {
                    if (!AutoSignEngine.shouldRunDaemon(app)) break
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
                    val remindAt = AutoSignEngine.remindAtFor(next)
                    val now = System.currentTimeMillis()
                    // 提醒改用引擎的去重表，因此与提醒槽闹钟共用同一条「只发一次」的判定。
                    // 条件写成「已到提醒时刻且还没到签到时刻」而不是「提醒时刻在未来」：
                    // 闹钟恰好在 remindAt 那一刻把服务拉起时，后者会因为相等而漏掉提醒。
                    if (now >= remindAt && now < next.targetMs) {
                        runCatching { AutoSignEngine.notifyUpcoming(app, next.course, next.beginMs, next.targetMs) }
                    } else if (remindAt > now) {
                        delay(remindAt - now)
                        if (!isActive) break
                        runCatching { AutoSignEngine.notifyUpcoming(app, next.course, next.beginMs, next.targetMs) }
                    }

                    val wait = next.targetMs - System.currentTimeMillis()
                    if (wait > 0) delay(wait)
                    // 到点：立即尝试一次，随后回到循环开头由「到期」分支接管重试。
                    runCatching { AutoSignEngine.runOnce(applicationContext, app) }
                    runCatching { AttendanceScheduler.ensureSchedule(applicationContext) }
                }
            } finally {
                // 循环退出 = 今天没有值得常驻的事了：主动退场，把后续交给闹钟与 WorkManager。
                runCatching { stopForeground(Service.STOP_FOREGROUND_REMOVE) }
                runCatching { WidgetRefreshScheduler.setDaemonActive(applicationContext, false) }
                runCatching { (application as? QingxinApp)?.notifier?.clearDaemonStatus() }
                stopSelf()
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
