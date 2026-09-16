package com.ucas.qingxin.signin.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ucas.qingxin.signin.QingxinApp
import com.ucas.qingxin.signin.network.QingxinApiService
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * 小部件刷新调度（多层，互为兜底）。
 *
 * 只靠 WorkManager 的周期任务做不到「及时」：间隔最短 15 分钟，且设备进入
 * Doze / 被厂商省电策略限制时会推迟到维护窗口，可能滞后几十分钟。
 * 因此这里按代价从低到高叠四层：
 *
 * 1. **进程内定时器**（[WidgetTicker]，60s）：只做**本地重绘**（零网络、零 IPC），
 *    让「当前课 / 签到窗口 / 下一节」随时间自然切换。应用在前台时最及时，
 *    退出前台后保留几分钟宽限再停，避免常驻耗电。
 * 2. **边界闹钟**（[AlarmManager]）：在每节课的「开课前 25 分钟 / 上课 / 下课」
 *    以及「次日零点」各触发一次，使内容在**语义发生变化的那一刻**更新，
 *    而不是等下一个固定周期。
 * 3. **数据兜底闹钟**：若近期没有课时边界，最多 10 分钟抓一次数据，保证
 *    「今天临时加了课」「别人替你签到了」这类变化能被带进来。
 *    自动签到守护进程存活时，该间隔自动放宽到 45 分钟
 *    （见 [setDaemonActive]）—— 守护进程本身就是精确唤醒通道，不需要小部件再兜底。
 * 4. **WorkManager 周期任务**（15 分钟）：进程被杀、闹钟被 ROM 掐掉后的最终兜底。
 *
 * 另外 provider 里的 `updatePeriodMillis` 也保留着，让宿主（桌面/负一屏）
 * 定期再推一次刷新——这是唯一不依赖本应用进程存活的标准刷新通道。
 */
internal object WidgetRefreshScheduler {

    private const val WORK_NAME = "widget_refresh_work"
    private const val NOW_NAME = "widget_refresh_now"

    private const val PREFS = "widget_refresh_state"
    private const val K_LAST_DATA_AT = "last_data_at"
    private const val K_LAST_DATA_DAY = "last_data_day"
    private const val K_ALARM_AT = "alarm_at"

    /** 两次网络抓取之间的最小间隔，避免闹钟/广播/定时器叠加造成请求风暴。 */
    private const val MIN_DATA_INTERVAL_MS = 2L * 60L * 1000L

    /** [sync] 时若数据超过这么久没更新，就顺带抓一次（例如冷启动渲染小部件时）。 */
    private const val SYNC_DATA_INTERVAL_MS = 5L * 60L * 1000L

    /** 数据兜底：近期没有课时边界时，最多隔这么久抓一次。 */
    private const val DATA_FALLBACK_MS = 10L * 60L * 1000L

    /**
     * 自动签到守护进程存活时的数据兜底间隔。
     *
     * 守护进程本身就是一条「随时能精确唤醒」的通道，因此小部件不需要再靠
     * 每 10 分钟一次的 `RTC_WAKEUP` 兜底 —— 这一条单独就省掉约 100+ 次/天唤醒。
     * 内容仍然由课时边界闹钟（开课前 25 分钟 / 上课 / 下课 / 次日 00:02）
     * 与守护进程事件驱动刷新，**不会变旧**。
     */
    private const val DAEMON_FALLBACK_MS = 45L * 60L * 1000L

    /** 进程内定时器触发数据抓取的间隔。 */
    private const val TICKER_DATA_INTERVAL_MS = 5L * 60L * 1000L

    private const val REQ_CODE_ALARM = 9001

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    /**
     * 自动签到守护进程是否存活（由 `AttendanceDaemonService` 维护）。
     * 存活时把数据兜底闹钟从 10 分钟抬到 45 分钟，显著降低唤醒次数。
     */
    @Volatile
    private var daemonActive = false

    /**
     * 由 `AttendanceDaemonService` 在 onStartCommand / onDestroy 时调用。
     * 状态变化后立刻重排闹钟，让新的兜底间隔马上生效。
     */
    fun setDaemonActive(context: Context, active: Boolean) {
        if (daemonActive == active) return
        daemonActive = active
        runCatching { rescheduleAlarm(context.applicationContext) }
    }

    fun isDaemonActive(): Boolean = daemonActive

    // ------------------------------------------------------------------ 生命周期

    /**
     * 小部件增删 / 开机 / 升级 / 冷启动渲染时调用：对齐后台任务与边界闹钟，
     * 并在数据明显过期时立刻补一次。
     */
    fun sync(context: Context) {
        val appContext = context.applicationContext
        if (WidgetHostCompat.widgetCount(appContext) == 0) {
            cancelAll(appContext)
            return
        }
        ensurePeriodicWork(appContext)
        ensureAlarm(appContext)
        // 跨天后必须重抓，否则小部件仍会显示昨天的课表。
        val crossedDay = lastDataDay(appContext) != todayKey()
        val stale = System.currentTimeMillis() - lastDataAt(appContext) >= SYNC_DATA_INTERVAL_MS
        if (crossedDay || stale) refreshData(appContext)
    }

    /** WorkManager 任务开始执行时调用（**不要**在此再次入队，避免自我取消）。 */
    fun onWorkerStart(context: Context) {
        ensurePeriodicWork(context)
        ensureAlarm(context)
        WidgetTicker.startForGrace(context)
    }

    fun cancelAll(context: Context) {
        cancelPeriodicWork(context)
        cancelAlarm(context)
        WidgetTicker.stop()
    }

    fun ensurePeriodicWork(context: Context) {
        runCatching {
            val request = PeriodicWorkRequestBuilder<WidgetRefreshWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }

    fun cancelPeriodicWork(context: Context) {
        runCatching {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
        }
    }

    // ------------------------------------------------------------------ 数据抓取

    /**
     * 触发一次「抓数据 + 重绘」。
     *
     * 走 WorkManager 的一次性任务，因此即使应用进程不在，也能被拉起执行。
     * 用 [ExistingWorkPolicy.KEEP]：已有一个在排队/执行中的抓取时不再重复入队，
     * 也就不会把正在跑的任务 REPLACE 掉（那会导致它永远跑不完）。
     */
    fun refreshData(context: Context, force: Boolean = false) {
        val appContext = context.applicationContext
        val now = System.currentTimeMillis()
        if (!force && now - lastDataAt(appContext) < MIN_DATA_INTERVAL_MS) return
        runCatching {
            val request = OneTimeWorkRequestBuilder<WidgetRefreshWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(appContext).enqueueUniqueWork(
                NOW_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
        // 无论后台任务何时真正执行，先把当前缓存画出来（跨天时会切到「正在同步」）。
        WidgetUpdater.refreshLocal(appContext)
    }

    fun markDataFetched(context: Context) {
        runCatching {
            prefs(context).edit()
                .putLong(K_LAST_DATA_AT, System.currentTimeMillis())
                .putString(K_LAST_DATA_DAY, todayKey())
                .apply()
        }
    }

    fun lastDataAt(context: Context): Long =
        runCatching { prefs(context).getLong(K_LAST_DATA_AT, 0L) }.getOrDefault(0L)

    fun lastDataDay(context: Context): String =
        runCatching { prefs(context).getString(K_LAST_DATA_DAY, null) }.getOrNull().orEmpty()

    fun todayKey(): String = LocalDate.now(zone).toString()

    /** 进程内定时器每分钟调用一次：总是本地重绘，必要时补一次网络数据。 */
    fun tick(context: Context) {
        val appContext = context.applicationContext
        if (WidgetHostCompat.widgetCount(appContext) == 0) {
            cancelAll(appContext)
            return
        }
        WidgetUpdater.refreshLocal(appContext)
        val crossedDay = lastDataDay(appContext) != todayKey()
        val stale = System.currentTimeMillis() - lastDataAt(appContext) >= TICKER_DATA_INTERVAL_MS
        if (crossedDay || stale) refreshData(appContext, force = crossedDay)
    }

    // ------------------------------------------------------------------ 闹钟

    /**
     * 保证有一个闹钟被排上。
     * 已排过且仍在未来的不重排，避免「每次重排都顺延 to now+X」导致永不触发。
     */
    fun ensureAlarm(context: Context) {
        val appContext = context.applicationContext
        if (WidgetHostCompat.widgetCount(appContext) == 0) {
            cancelAlarm(appContext)
            return
        }
        val now = System.currentTimeMillis()
        if (alarmAt(appContext) > now) return
        scheduleAlarm(appContext, now)
    }

    /** 立刻重排到下一个目标时刻（课表变化后调用，让边界闹钟对齐最新课表）。 */
    fun rescheduleAlarm(context: Context) {
        val appContext = context.applicationContext
        cancelAlarm(appContext)
        if (WidgetHostCompat.widgetCount(appContext) == 0) return
        scheduleAlarm(appContext, System.currentTimeMillis())
    }

    private fun scheduleAlarm(context: Context, now: Long) {
        val target = nextTriggerAt(context, now)
        runCatching {
            val pending = alarmPendingIntent(context)
            val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            // setAndAllowWhileIdle 不需要 SCHEDULE_EXACT_ALARM 权限；
            // 精确闹钟（setExact*）在 Android 12+ 需要该权限，本应用不申请。
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, target, pending)
            prefs(context).edit().putLong(K_ALARM_AT, target).apply()
        }
    }

    fun cancelAlarm(context: Context) {
        runCatching {
            val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            manager?.cancel(alarmPendingIntent(context))
            prefs(context).edit().putLong(K_ALARM_AT, 0L).apply()
        }
    }

    fun alarmAt(context: Context): Long =
        runCatching { prefs(context).getLong(K_ALARM_AT, 0L) }.getOrDefault(0L)

    /** 闹钟触发后清除记录，使 [ensureAlarm] 能续排下一个。 */
    fun clearAlarmMark(context: Context) {
        runCatching { prefs(context).edit().putLong(K_ALARM_AT, 0L).apply() }
    }

    private fun alarmPendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context.applicationContext,
        REQ_CODE_ALARM,
        Intent(context.applicationContext, WidgetAlarmReceiver::class.java)
            .setAction(WidgetAlarmReceiver.ACTION_WIDGET_ALARM),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * 下一个该刷新的时刻 = min(课时边界, 数据兜底上限)。
     *
     * 课时边界包含「开课前 25 分钟」（签到窗口开始）、「上课」、「下课」，
     * 以及次日零点后 2 分钟（跨天换课表）。
     */
    fun nextTriggerAt(context: Context, now: Long): Long {
        val fallback = now + if (daemonActive) DAEMON_FALLBACK_MS else DATA_FALLBACK_MS
        val boundaries = ArrayList<Long>()

        val app = context.applicationContext as? QingxinApp
        if (app != null && app.isReady) {
            val courses = runCatching { app.courseRepository.courses.value?.courses }
                .getOrNull().orEmpty()
            courses.forEach { course ->
                val begin = runCatching { app.courseRepository.parseBeginMs(course) }.getOrNull()
                val end = runCatching { app.courseRepository.parseEndMs(course) }.getOrNull()
                if (begin != null) {
                    boundaries.add(begin - QingxinApiService.SIGN_WINDOW_LEAD_MS)
                    boundaries.add(begin)
                }
                if (end != null) boundaries.add(end)
            }
        }

        runCatching {
            boundaries.add(
                LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() +
                    2 * 60_000L,
            )
        }

        val earliest = boundaries.filter { it > now + 5_000L }.minOrNull()
        return earliest?.coerceAtMost(fallback) ?: fallback
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * 数据刷新执行体。
 * 只负责「取数据 → 重绘 → 重排闹钟」，不阻塞宿主回调（运行在 WorkManager 线程池）。
 */
internal class WidgetRefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // 任务被执行本身就说明进程已被拉起：借这段存活时间开一个宽限定时器，
        // 让接下来的边界切换更精确，随后自动停止。
        WidgetRefreshScheduler.onWorkerStart(applicationContext)

        if (WidgetHostCompat.widgetCount(applicationContext) == 0) {
            WidgetRefreshScheduler.cancelAll(applicationContext)
            return Result.success()
        }

        val app = applicationContext as? QingxinApp
        val loggedIn = app != null && app.isReady &&
            runCatching { app.authRepository.isLoggedIn() }.getOrDefault(false)

        if (loggedIn) {
            val ok = runCatching { app!!.courseRepository.loadToday() }.isSuccess
            // 只在成功时记录时间戳：失败要允许尽快重试。
            if (ok) WidgetRefreshScheduler.markDataFetched(applicationContext)
        } else {
            // 未登录也算「已核对」，否则定时器会每 60s 反复入队。
            WidgetRefreshScheduler.markDataFetched(applicationContext)
        }

        // 无论有没有网络/登录，都重绘一次：至少把时间、签到窗口状态刷成最新。
        runCatching { TodayCourseWidgetReceiver.requestUpdate(applicationContext) }
        // 数据成功就按最新课表重排边界闹钟；失败则保留原心跳频率以便尽快重试。
        if (loggedIn) runCatching { WidgetRefreshScheduler.rescheduleAlarm(applicationContext) }
        return Result.success()
    }
}
