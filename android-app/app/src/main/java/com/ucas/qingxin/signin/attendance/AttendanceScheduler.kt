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
 * 3. **不做轮询**：「提醒」与「签到」各自只排一个闹钟（见 [AlarmSlot]）。
 *
 * ## 两条闹钟槽（1.2.1 起）
 *
 * | 槽 | 时刻 | 做什么 | 代价 |
 * | --- | --- | --- | --- |
 * | [AlarmSlot.REMIND] | **上课前 15 分钟** | 只发一条「即将开始签到」提醒 | 不联网、不启服务 |
 * | [AlarmSlot.SIGN] | 随机签到时刻 | 拉起守护服务（普通档）并执行签到 | 一次网络 |
 *
 * 拆出 REMIND 槽的原因：1.2.0 的提醒是守护服务 `delay` 睡到点时**顺带发出**的，
 * 而省电档没有守护服务 —— 于是省电档**完全收不到提醒**。独立成闹钟后两档都有提醒，
 * 且 REMIND 槽不联网不启服务，唤醒成本接近 0。
 *
 * 提醒时刻定在「上课前 15 分钟」而不是「随机签到时刻前 N 分钟」：随机时刻每节课都变，
 * 以它为基准的提醒让用户无法预知；相对上课时刻则一眼可算（见 [AutoSignEngine.remindAtFor]）。
 */
class AttendanceScheduler(context: Context) {

    /**
     * 闹钟槽。
     *
     * 每个槽有独立的 `request code`（否则 `PendingIntent` 会互相覆盖）与
     * 独立的状态键（否则 `ensureSchedule` 的「已排过就不重排」会误判）。
     */
    private enum class AlarmSlot(
        val reqCode: Int,
        val action: String,
        val keyAt: String,
        val keyExact: String,
    ) {
        REMIND(
            reqCode = REQ_CODE_REMIND,
            action = AutoSignAlarmReceiver.ACTION_AUTO_SIGN_REMIND,
            keyAt = K_REMIND_AT,
            keyExact = K_REMIND_EXACT,
        ),
        SIGN(
            reqCode = REQ_CODE_ALARM,
            action = AutoSignAlarmReceiver.ACTION_AUTO_SIGN_ALARM,
            keyAt = K_ALARM_AT,
            keyExact = K_ALARM_EXACT,
        ),
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val state = appContext.getSharedPreferences("auto_sign_state", Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ 设置

    fun getSettings(): UserSettings = readSettings(appContext)

    fun saveSettings(settings: UserSettings) {
        prefs.edit()
            .putBoolean(KEY_AUTO, settings.autoSignEnabled)
            .putBoolean(KEY_NOTIFY, settings.notifyEnabled)
            .putBoolean(KEY_LOW_POWER, settings.lowPowerMode)
            .apply()
        if (settings.autoSignEnabled) start() else stop()
    }

    // ------------------------------------------------------------------ 生命周期

    /** 开启自动签到：排闹钟 + 按档位与「是否临近签到」决定守护服务去留。 */
    fun start() {
        val settings = getSettings()
        if (!settings.autoSignEnabled) {
            stop()
            return
        }
        AttendanceNotifier.ensureChannels(appContext)
        ensureSchedule()
        syncDaemon()
    }

    /**
     * 按档位与「是否临近签到」决定守护服务的去留。
     *
     * 两个约束共同作用：
     * 1. 省电档永远不启守护服务（[PowerProfile.daemonEnabled]）；
     * 2. 普通档也**只在临近签到窗口时**才启 —— 否则「打开一次 App」就会留下一个
     *    带常驻通知的前台服务（`START_STICKY`），进程整天整夜不被系统冻结，
     *    与前台无关的循环就能一直跑到天亮。这正是 1.2.0 里「权限给得越全、反而越耗电」
     *    的直接原因。
     */
    fun syncDaemon() {
        val app = appContext as? QingxinApp ?: return
        if (!app.isReady) return
        if (AutoSignEngine.shouldRunDaemon(app)) {
            AttendanceDaemonService.start(appContext)
        } else {
            AttendanceDaemonService.stop(appContext)
        }
    }

    /** 关闭自动签到：清空闹钟、任务、服务、内存态随机值与常驻通知。 */
    fun stop() {
        cancelAlarms()
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
            cancelAlarms()
            cancelWork()
            return
        }

        val due = runCatching { AutoSignEngine.nextDue(app) }.getOrNull()
        if (due == null) {
            // 今日已无待签课程：只留一个跨天翻页闹钟，空闲日唤醒 1 次。
            cancelAlarms()
            cancelWork()
            scheduleAlarm(AlarmSlot.SIGN, dayRolloverAt(), exact = false)
            syncDaemon()
            return
        }

        val exact = KeepAliveHelper.isBatteryExempt(appContext) &&
            KeepAliveHelper.canScheduleExactAlarms(appContext)
        val now = System.currentTimeMillis()
        // 提醒槽：只在「还来得及提前提醒」时才排。已经过了提醒时刻就不再补排，
        // 否则每一次 ensureSchedule（每次闹钟、每次开 App）都会立刻触发一次无用唤醒。
        val remindAt = AutoSignEngine.remindAtFor(due)
        if (remindAt > now + MIN_ALARM_LEAD_MS) {
            scheduleAlarm(AlarmSlot.REMIND, remindAt, exact)
        } else {
            cancelAlarm(AlarmSlot.REMIND)
        }
        scheduleAlarm(AlarmSlot.SIGN, due.targetMs, exact)
        // 两条路径互斥：能精确唤醒时不再排 WorkManager 备份，避免同一次签到被唤醒两遍。
        if (exact) cancelWork() else scheduleWork(due.targetMs)
        syncDaemon()
    }

    private fun dayRolloverAt(): Long =
        LocalDate.now(ZONE).plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli() + 2 * 60_000L

    /**
     * 排一个槽上的闹钟。
     *
     * 幂等：目标时刻与「精确与否」都没变就直接返回，避免每次调用都把闹钟顺延到
     * `now + X`（那会让它永远不触发）。
     */
    private fun scheduleAlarm(slot: AlarmSlot, target: Long, exact: Boolean) {
        val fireAt = target.coerceAtLeast(System.currentTimeMillis() + MIN_ALARM_LEAD_MS)
        if (state.getLong(slot.keyAt, 0L) == fireAt &&
            state.getBoolean(slot.keyExact, false) == exact
        ) {
            return
        }
        val manager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pending = alarmIntent(slot)
        val exactOk = exact && runCatching {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pending)
        }.isSuccess
        if (exactOk) {
            state.edit().putLong(slot.keyAt, fireAt).putBoolean(slot.keyExact, true).apply()
            return
        }
        // 精确闹钟被系统拒绝（用户撤销权限 / 厂商限制）→ 降级为非精确，绝不静默丢失唤醒。
        runCatching { manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pending) }
        state.edit().putLong(slot.keyAt, fireAt).putBoolean(slot.keyExact, false).apply()
    }

    private fun cancelAlarm(slot: AlarmSlot) {
        runCatching {
            val manager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            manager?.cancel(alarmIntent(slot))
        }
        state.edit().putLong(slot.keyAt, 0L).putBoolean(slot.keyExact, false).apply()
    }

    fun cancelAlarms() {
        AlarmSlot.entries.forEach { cancelAlarm(it) }
    }

    /** 已排定的签到槽时刻（0 = 未排）。调试与设置页展示用。 */
    fun alarmAt(): Long = state.getLong(AlarmSlot.SIGN.keyAt, 0L)

    /** 已排定的提醒槽时刻（0 = 未排）。 */
    fun remindAt(): Long = state.getLong(AlarmSlot.REMIND.keyAt, 0L)

    private fun alarmIntent(slot: AlarmSlot): PendingIntent = PendingIntent.getBroadcast(
        appContext,
        slot.reqCode,
        Intent(appContext, AutoSignAlarmReceiver::class.java).setAction(slot.action),
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
        val profile = resolveProfile()
        return KeepAliveState(
            autoSignEnabled = settings.autoSignEnabled,
            lowPowerMode = settings.lowPowerMode,
            batteryExempt = KeepAliveHelper.isBatteryExempt(appContext),
            exactAlarmAllowed = KeepAliveHelper.canScheduleExactAlarms(appContext),
            notificationsAllowed = KeepAliveHelper.notificationsEnabled(appContext),
            // 「后台服务」这一行展示的是**通道是否可用**：普通档可用（按需拉起、
            // 签完即退），省电档按设计不使用。是否「此刻正在跑」在界面上没有意义 ——
            // 现在它绝大多数时间本来就不该在跑。
            daemonRunning = profile.daemonEnabled && !isDaemonBlocked(),
            autoStartConfirmed = isBootSeen(),
            lockConfirmed = isLockConfirmed(),
            daemonBlocked = isDaemonBlocked(),
            estimatedWakeupsToday = estimateWakeupsToday(),
            systemPowerSave = profile.systemPowerSave,
        )
    }

    /** 读取当前生效的档位；应用未就绪时退化为「只读设置 + 系统省电」。 */
    private fun resolveProfile(): ResolvedPowerProfile {
        val app = appContext as? QingxinApp
        return if (app != null && app.isReady) {
            app.powerProfile()
        } else {
            PowerProfiles.resolve(getSettings(), PowerProfiles.isSystemPowerSave(appContext))
        }
    }

    /**
     * 「今日预计唤醒次数」= 每节课 2 次（提醒槽 + 签到槽）+ 1 次跨天翻页。
     *
     * 1.2.0 的估算写的是「每节课 1 次」，但那时提醒是顺带发出的、省电档根本没有提醒；
     * 现在提醒有独立闹钟，所以诚实地算 2 次 —— 让用户在选择档位时看到真实代价，
     * 而不是一个好看的假数字。
     */
    private fun estimateWakeupsToday(): Int {
        val app = appContext as? QingxinApp ?: return 0
        if (!app.isReady) return 0
        val courses = runCatching { app.courseRepository.courses.value?.courses }.getOrNull()
            ?: return 0
        val remaining = courses.count { !it.signed }
        return remaining * WAKEUPS_PER_COURSE + 1
    }

    companion object {
        /** 用户设置所在的 prefs 名。静态读取与小部件宿主冷启动都依赖它。 */
        const val PREFS_NAME = "user_settings"

        private const val KEY_AUTO = "auto_sign"
        private const val KEY_NOTIFY = "notify"
        private const val KEY_LOW_POWER = "low_power_mode"

        private const val K_ALARM_AT = "alarm_at"
        private const val K_ALARM_EXACT = "alarm_exact"
        private const val K_REMIND_AT = "remind_alarm_at"
        private const val K_REMIND_EXACT = "remind_alarm_exact"
        private const val K_DAEMON_BLOCKED = "daemon_blocked"
        private const val K_BOOT_SEEN = "boot_seen"
        private const val K_LOCK_CONFIRMED = "lock_confirmed"

        /** 闹钟最早不能早于「现在 + 这个值」：避免已过时刻的闹钟被立刻触发成一次空唤醒。 */
        private const val MIN_ALARM_LEAD_MS = 5_000L

        /** 每节课的唤醒预算：提醒槽 + 签到槽。 */
        private const val WAKEUPS_PER_COURSE = 2

        private const val REQ_CODE_ALARM = 9101

        /** 提醒槽的 `request code`：必须与签到槽不同，否则两个 `PendingIntent` 会互相覆盖。 */
        private const val REQ_CODE_REMIND = 9102

        const val WORK_NAME = "auto_sign_once"

        private val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

        /**
         * 只读用户设置，**不依赖容器就绪、不依赖实例**。
         *
         * 小部件宿主会冷启动本进程，此时 `QingxinApp.isReady` 可能仍是 false；
         * 耗电档位的解析必须在这种情况下也读得到真实设置，否则省电档用户会在
         * 冷启动那一小段时间里拿到「普通档」的刷新间隔。
         */
        fun readSettings(context: Context): UserSettings {
            val prefs = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return UserSettings(
                autoSignEnabled = prefs.getBoolean(KEY_AUTO, false),
                notifyEnabled = prefs.getBoolean(KEY_NOTIFY, true),
                lowPowerMode = prefs.getBoolean(KEY_LOW_POWER, false),
            )
        }

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
