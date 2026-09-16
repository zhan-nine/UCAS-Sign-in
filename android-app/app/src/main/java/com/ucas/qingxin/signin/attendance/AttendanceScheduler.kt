package com.ucas.qingxin.signin.attendance

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ucas.qingxin.signin.QingxinApp
import com.ucas.qingxin.signin.data.KeepAliveState
import com.ucas.qingxin.signin.data.UserSettings
import com.ucas.qingxin.signin.widget.WidgetRefreshScheduler
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * 自动签到的调度中枢。
 *
 * ## 唤醒预算（一等约束）
 *
 * 目标：**每节课 1 次唤醒**，无课/已签完的日子只留 1 次「跨天翻页」。
 * 4 节课的一天 ≈ 5 次唤醒。对比 1.1.11 里小部件每 10 分钟一条的闹钟链
 * （约 144 次/天）是**净下降**。
 *
 * ## 两条互斥的调度路径
 *
 * | 条件 | 闹钟 | WorkManager 备份 |
 * | --- | --- | --- |
 * | 已取得电池豁免 **且** 允许精确闹钟 | `setExactAndAllowWhileIdle`（精确） | **不排**（避免重复唤醒） |
 * | 其他情况 | `setAndAllowWhileIdle` | 排一个 `OneTimeWorkRequest`（交由 JobScheduler 与其他任务批量合并，比 wakeup 闹钟省电） |
 *
 * ## 刻意不做的三件事
 *
 * 1. **不再使用 15 分钟 `PeriodicWorkRequest`**：那会在一天内反复唤醒并抓取网络；
 * 2. **重试不排新闹钟**：Doze 下 `setAndAllowWhileIdle` 最快也要约 15 分钟才放行一次，
 *    用它做秒级重试是错的，重试只跑在进程内（见 [AutoSignWindowRunner]）；
 * 3. **不为「签到前 2 分钟提醒」单排闹钟**：提醒写在常驻通知里，
 *    并在守护服务的进程内定时器上顺带发出（零额外唤醒）。
 */
class AttendanceScheduler(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("user_settings", Context.MODE_PRIVATE)
    private val state = appContext.getSharedPreferences("auto_sign_state", Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ 设置

    fun getSettings(): UserSettings = UserSettings(
        autoSignEnabled = prefs.getBoolean(KEY_AUTO, false),
        notifyEnabled = prefs.getBoolean(KEY_NOTIFY, true),
        lowPowerMode = prefs.getBoolean(KEY_LOW_POWER, false),
    )

    fun saveSettings(settings: UserSettings) {
        prefs.edit()
            .putBoolean(KEY_AUTO, settings.autoSignEnabled)
            .putBoolean(KEY_NOTIFY, settings.notifyEnabled)
            .putBoolean(KEY_LOW_POWER, settings.lowPowerMode)
            .apply()
        if (settings.autoSignEnabled) start() else stop()
    }

    // ------------------------------------------------------------------ 生命周期

    /** 开启自动签到：排闹钟 + （按需）拉起守护服务。 */
    fun start() {
        val settings = getSettings()
        if (!settings.autoSignEnabled) {
            stop()
            return
        }
        AttendanceNotifier.ensureChannels(appContext)
        ensureSchedule()
        if (settings.lowPowerMode) {
            // 低耗电模式：放弃常驻通知与秒级精度，只保留每课 1 个闹钟。
            AttendanceDaemonService.stop(appContext)
            WidgetRefreshScheduler.setDaemonActive(appContext, false)
        } else {
            AttendanceDaemonService.start(appContext)
        }
    }

    /** 关闭自动签到：清空闹钟、任务、服务、内存态随机值与常驻通知。 */
    fun stop() {
        cancelAlarm()
        cancelWork()
        AttendanceDaemonService.stop(appContext)
        WidgetRefreshScheduler.setDaemonActive(appContext, false)
        AutoSignRandomizer.clearAll()
        AutoSignEngine.resetRuntime()
        runCatching {
            (appContext as? QingxinApp)?.takeIf { it.isReady }?.notifier?.clearDaemonStatus()
        }
    }

    // ------------------------------------------------------------------ 排程

    /**
     * 重新计算并对齐「下一次该唤醒的时刻」。
     *
     * 幂等：随机时刻一旦冻结（见 [AutoSignRandomizer]），重复调用得到同一个目标，
     * 闹钟不会被反复顺延。
     */
    fun ensureSchedule() {
        val app = appContext as? QingxinApp ?: return
        if (!app.isReady) return
        if (!getSettings().autoSignEnabled) {
            cancelAlarm()
            cancelWork()
            return
        }

        val due = runCatching { AutoSignEngine.nextDue(app) }.getOrNull()
        if (due == null) {
            // 今日已无待签课程：只留一个跨天翻页闹钟，空闲日唤醒 1 次。
            cancelWork()
            scheduleAlarm(dayRolloverAt(), exact = false)
            return
        }

        val exact = KeepAliveHelper.isBatteryExempt(appContext) &&
            KeepAliveHelper.canScheduleExactAlarms(appContext)
        scheduleAlarm(due.targetMs, exact)
        // 两条路径互斥：能精确唤醒时不再排 WorkManager 备份，避免同一次签到被唤醒两遍。
        if (exact) cancelWork() else scheduleWork(due.targetMs)
    }

    private fun dayRolloverAt(): Long =
        LocalDate.now(ZONE).plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli() + 2 * 60_000L

    private fun scheduleAlarm(target: Long, exact: Boolean) {
        val fireAt = target.coerceAtLeast(System.currentTimeMillis() + 5_000L)
        if (state.getLong(K_ALARM_AT, 0L) == fireAt &&
            state.getBoolean(K_ALARM_EXACT, false) == exact
        ) {
            return
        }
        val manager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pending = alarmIntent()
        val exactOk = exact && runCatching {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pending)
        }.isSuccess
        if (exactOk) {
            state.edit().putLong(K_ALARM_AT, fireAt).putBoolean(K_ALARM_EXACT, true).apply()
            return
        }
        // 精确闹钟被系统拒绝（用户撤销权限 / 厂商限制）→ 降级为非精确，绝不静默丢失唤醒。
        runCatching { manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pending) }
        state.edit().putLong(K_ALARM_AT, fireAt).putBoolean(K_ALARM_EXACT, false).apply()
    }

    fun cancelAlarm() {
        runCatching {
            val manager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            manager?.cancel(alarmIntent())
        }
        state.edit().putLong(K_ALARM_AT, 0L).putBoolean(K_ALARM_EXACT, false).apply()
    }

    fun alarmAt(): Long = state.getLong(K_ALARM_AT, 0L)

    private fun alarmIntent(): PendingIntent = PendingIntent.getBroadcast(
        appContext,
        REQ_CODE_ALARM,
        Intent(appContext, AutoSignAlarmReceiver::class.java)
            .setAction(AutoSignAlarmReceiver.ACTION_AUTO_SIGN_ALARM),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun scheduleWork(atMs: Long) {
        runCatching {
            val delayMs = (atMs - System.currentTimeMillis()).coerceAtLeast(0L)
            val request = OneTimeWorkRequestBuilder<AutoSignWorker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            // KEEP：已在排队/执行中的同一任务不重复入队，避免「任务自己把自己 REPLACE 掉」。
            WorkManager.getInstance(appContext).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }

    fun cancelWork() {
        runCatching { WorkManager.getInstance(appContext).cancelUniqueWork(WORK_NAME) }
    }

    // ------------------------------------------------------------------ 保活状态

    fun markDaemonBlocked(blocked: Boolean) {
        state.edit().putBoolean(K_DAEMON_BLOCKED, blocked).apply()
    }

    fun isDaemonBlocked(): Boolean = state.getBoolean(K_DAEMON_BLOCKED, false)

    /** `BOOT_COMPLETED` 收到过一次即证明自启动已允许（Android 无公开查询 API）。 */
    fun markBootSeen() {
        state.edit().putBoolean(K_BOOT_SEEN, true).apply()
    }

    fun isBootSeen(): Boolean = state.getBoolean(K_BOOT_SEEN, false)

    fun setLockConfirmed(confirmed: Boolean) {
        state.edit().putBoolean(K_LOCK_CONFIRMED, confirmed).apply()
    }

    fun isLockConfirmed(): Boolean = state.getBoolean(K_LOCK_CONFIRMED, false)

    fun readKeepAliveState(): KeepAliveState {
        val settings = getSettings()
        return KeepAliveState(
            autoSignEnabled = settings.autoSignEnabled,
            lowPowerMode = settings.lowPowerMode,
            batteryExempt = KeepAliveHelper.isBatteryExempt(appContext),
            exactAlarmAllowed = KeepAliveHelper.canScheduleExactAlarms(appContext),
            notificationsAllowed = KeepAliveHelper.notificationsEnabled(appContext),
            daemonRunning = !settings.lowPowerMode && !isDaemonBlocked(),
            autoStartConfirmed = isBootSeen(),
            lockConfirmed = isLockConfirmed(),
            daemonBlocked = isDaemonBlocked(),
            estimatedWakeupsToday = estimateWakeupsToday(),
        )
    }

    /** 「今日预计唤醒次数」= 剩余待签课次 + 1 次跨天翻页。用于把省电设计展示给用户。 */
    private fun estimateWakeupsToday(): Int {
        val app = appContext as? QingxinApp ?: return 0
        if (!app.isReady) return 0
        val courses = runCatching { app.courseRepository.courses.value?.courses }.getOrNull()
            ?: return 0
        val remaining = courses.count { !it.signed }
        return remaining + 1
    }

    companion object {
        private const val KEY_AUTO = "auto_sign"
        private const val KEY_NOTIFY = "notify"
        private const val KEY_LOW_POWER = "low_power_mode"

        private const val K_ALARM_AT = "alarm_at"
        private const val K_ALARM_EXACT = "alarm_exact"
        private const val K_DAEMON_BLOCKED = "daemon_blocked"
        private const val K_BOOT_SEEN = "boot_seen"
        private const val K_LOCK_CONFIRMED = "lock_confirmed"

        private const val REQ_CODE_ALARM = 9101

        const val WORK_NAME = "auto_sign_once"

        private val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

        /** 供接收器 / 守护服务等非 Activity 位置调用的静态入口。 */
        fun ensureSchedule(context: Context) {
            runCatching {
                val app = context.applicationContext as? QingxinApp ?: return
                if (!app.isReady) return
                app.attendanceScheduler.ensureSchedule()
            }
        }
    }
}

/**
 * 自动签到的 WorkManager 执行体（**一次性**任务，不是周期任务）。
 *
 * 定位是「闹钟被 ROM 掐掉 / Doze 深度休眠时的兜底」，因此只在未取得电池豁免时排程。
 * `AutoSignWindowRunner` 与闹钟、守护服务共用同一个 [AutoSignEngine]，
 * 行为完全一致（含取数闸门与校时复用）。
 */
class AutoSignWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? QingxinApp ?: return Result.success()
        if (!app.isReady) return Result.success()
        if (!app.attendanceScheduler.getSettings().autoSignEnabled) return Result.success()

        // WorkManager 的协程有约 10 分钟上限，因此这里收敛重试轮数，剩余交给闹钟。
        runCatching { AutoSignWindowRunner.run(applicationContext, maxRounds = 3) }

        // 只重排**闹钟**：WorkManager 的 KEEP 语义会让「任务内部自我重排」静默失效，
        // 所以下一次的一次性任务由闹钟触发时再入队。
        runCatching { app.attendanceScheduler.ensureSchedule() }
        return Result.success()
    }
}
