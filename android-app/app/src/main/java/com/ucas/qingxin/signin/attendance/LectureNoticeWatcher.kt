package com.ucas.qingxin.signin.attendance

import android.content.Context
import android.content.SharedPreferences
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ucas.qingxin.signin.QingxinApp
import com.ucas.qingxin.signin.lecture.LectureNotice
import com.ucas.qingxin.signin.lecture.LectureSentinel
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * 「新讲座消息」的通知中枢。
 *
 * ## 为什么不再是「签到调度器」
 * 上一版为「讲座开始前提醒」维护了一套闹钟 + 落盘队列（[LectureNotice] 里
 * 根本没有讲座开始时间，那套东西已无处可用）。现在人文讲座通知**只有发布日期**，
 * 无法预知讲座何时开始，因此唯一有意义的提醒是「**出现了一条新消息**」。
 *
 * 于是本对象只做一件很简单的事：比对本次抓到的通知与「已见集合」，
 * 把新增的、还没讲完的那些推成一条通知。
 *
 * ## 抓取由谁触发（两条路径，缺一不可）
 * 1. **用户刷新讲座页**（[LectureViewModel] 在每次成功抓取后调用 [sync]）；
 * 2. **后台周期检查**（[LectureNoticeWorker] + [ensurePeriodicWork]）。
 *
 * 第 2 条不是锦上添花：只靠第 1 条的话，用户看到通知的时候**已经在看列表了**，
 * 通知就永远没有存在意义。两条路径共用同一份「已见集合」，因此不会重复推送。
 *
 * ## 为何必须区分「首次运行」
 * 安装后第一次抓取会一次性看到 10 条历史通知。若不做区分，用户装上应用就会被
 * 推 10 条「新消息」。因此首次只记录、不推送（见 [K_INITIALIZED]）。
 *
 * ## 已见集合的存储
 * 存成一个按时间追加的换行分隔字符串（而不是 `StringSet`）：需要「按新旧截断」
 * 才能让体积有上界，而 `Set` 是无序的，无法实现。
 * 两条截断规则同时生效（见 [prune]）：按日期丢弃半年前的、再按条数硬截断。
 */
object LectureNoticeWatcher {

    /**
     * 沿用上一版的 prefs 名与开关键。
     *
     * 刻意不改名：用户在旧版本里打开过的「讲座提醒」开关会原样保留，
     * 不会因为升级而悄悄被重置成关闭。
     */
    private const val PREFS_NAME = "lecture_reminder"
    private const val K_ENABLED = "enabled"
    private const val K_SEEN = "seen"
    private const val K_INITIALIZED = "initialized"

    /** 已见集合的条数上界。10 条/栏目的量级下，500 条约等于一年多的历史。 */
    private const val MAX_SEEN = 500

    /** 已见集合的日期保留窗口：超过这个天数的条目会被丢弃。 */
    private const val RETENTION_DAYS = 180L

    private val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

    // ------------------------------------------------------------------ 开关

    /**
     * 打开 / 关闭「新讲座消息」通知。
     *
     * 开关与后台检查任务是**同生共死**的：关掉时立刻撤销周期任务，
     * 否则系统还会按原计划把进程拉起来、白白发一次网络请求
     * （[LectureNoticeWorker] 里虽然有开关判断，但那已经是唤醒之后的事了）。
     */
    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(K_ENABLED, enabled).apply()
        syncPeriodicWork(context)
    }

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(K_ENABLED, false)

    // ------------------------------------------------------------------ 后台检查

    /**
     * 依据**两个开关**决定登记还是撤销周期任务。
     *
     * ## 为什么两个开关共用一个任务
     * 「新讲座消息」与「哨兵」都需要周期性醒来抓数据。各自登记一个 `PeriodicWorkRequest`
     * 意味着**两次唤醒、两次进程拉起**，而它们的节奏需求完全相同。
     * 共用一次唤醒、在里面各跑各的，是同一件事的两种问法，代价却减半。
     *
     * 因此**只要有一个开关是开的**就保留任务，两个都关才撤销 ——
     * 谁最后被关掉就由谁负责撤销。
     */
    fun syncPeriodicWork(context: Context) {
        val anyEnabled = isEnabled(context) || LectureSentinel.isEnabled(context)
        if (anyEnabled) ensurePeriodicWork(context) else cancelPeriodicWork(context)
    }

    /**
     * 登记（或修正）周期检查任务。
     *
     * 幂等：用 [ExistingPeriodicWorkPolicy.KEEP] 保证重复调用不会重置已排好的节奏，
     * 因此在应用启动时无条件调用一次是安全的（也能自愈被系统清掉的任务）。
     */
    fun ensurePeriodicWork(context: Context) {
        if (!isEnabled(context) && !LectureSentinel.isEnabled(context)) return
        runCatching {
            val request = PeriodicWorkRequestBuilder<LectureNoticeWorker>(CHECK_INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }

    /** 撤销周期检查任务。见 [setEnabled]。 */
    fun cancelPeriodicWork(context: Context) {
        runCatching {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
        }
    }

    // ------------------------------------------------------------------ 比对

    /**
     * 记录本次抓到的通知，并在**开关打开、且不是首次运行**时推送新增预告。
     *
     * 无论开关是否打开都会更新已见集合：
     * 否则「关着开关期间累积的预告」会在用户下次开启的瞬间被全部当成新的推出去。
     *
     * @return 本次判定为「新」的预告（推送了的那批），便于单测断言。
     */
    fun sync(
        context: Context,
        notices: List<LectureNotice>,
        today: LocalDate = LocalDate.now(ZONE),
    ): List<LectureNotice> {
        if (notices.isEmpty()) return emptyList()
        val store = prefs(context)
        val known = loadSeen(store)
        val knownSet = known.toHashSet()
        val fresh = notices.filterNot { it.identityKey in knownSet }

        val initialized = store.getBoolean(K_INITIALIZED, false)
        val merged = prune(known + fresh.map { sanitize(it.identityKey) }, today)
        store.edit()
            .putString(K_SEEN, merged.joinToString(SEPARATOR.toString()))
            .putBoolean(K_INITIALIZED, true)
            .apply()

        // 首次运行只记录：此刻的每一条通知都是「新的」，推出去等于装了个骚扰器。
        if (!initialized) return emptyList()
        if (!store.getBoolean(K_ENABLED, false)) return emptyList()

        // 只推**未讲完**的场次（即排除明确的「报道 / 纪要 / 综述」）：
        // 报道说的是已经讲完的事，对用户没有可行动性，推它只会稀释
        // 「有新讲座可以预约了」这条信号。
        val toNotify = fresh.filter { it.actionable }
        if (toNotify.isEmpty()) return emptyList()
        runCatching { AttendanceNotifier(context).notifyNewLectureNotice(toNotify) }
        return toNotify
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 两条截断规则：先按日期丢弃过期的，再按条数保留最新的 [MAX_SEEN] 条。
     *
     * 日期解析不出来的条目**保留**（由条数上限兜底）：与其因为一条格式异常就
     * 丢掉去重记录（那会让它之后被反复当成新预告），不如留着占一个位置。
     */
    private fun prune(keys: List<String>, today: LocalDate): List<String> {
        val cutoff = today.minusDays(RETENTION_DAYS).toString()
        val alive = keys.distinct().filter { key ->
            val date = key.substringBefore(SEPARATOR_OF_KEY, "")
            date.length != DATE_LENGTH || date >= cutoff
        }
        return alive.takeLast(MAX_SEEN)
    }

    /**
     * 去掉可能破坏「换行分隔」结构的字符。
     *
     * 标题里出现换行的概率极低，但一旦出现就会把一条 key 劈成两条，
     * 让去重表悄悄损坏 —— 这里付出一次替换的代价把它排除掉。
     */
    private fun sanitize(key: String): String = key
        .replace(SEPARATOR, ' ')
        .replace('\r', ' ')

    private fun loadSeen(store: SharedPreferences): List<String> =
        store.getString(K_SEEN, null)
            ?.split(SEPARATOR)
            ?.filter { it.isNotBlank() }
            ?: emptyList()

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 记录分隔符（换行）。见 [sanitize]。 */
    private const val SEPARATOR = '\n'

    /** `identityKey` 里日期字段与其余部分的分隔符，与 `LectureNotice.identityKey` 保持一致。 */
    private const val SEPARATOR_OF_KEY = '|'

    private const val DATE_LENGTH = 10

    /** 后台检查的间隔：人文学院两个栏目一周只发几条，一天查两次足够及时。 */
    private const val CHECK_INTERVAL_HOURS = 12L

    /** 周期任务的唯一名（`WorkManager` 用它去重）。 */
    private const val WORK_NAME = "lecture_notice_check"
}

/**
 * 后台周期性抓取讲座通知 + 跑哨兵。
 *
 * 与 [LectureNoticeWatcher.sync] 的分工：本类只负责「把数据取回来」，
 * 比对与推送全部交给 [LectureNoticeWatcher]，于是前台刷新与后台检查
 * 走的是同一套判定，不会出现「前台推过、后台又推一遍」。
 *
 * 哨兵（[LectureSentinel]）也挂在这里：两者都需要周期性醒来，共用一次唤醒
 * 意味着只拉起一次进程、只走一次系统调度（见 [LectureNoticeWatcher.syncPeriodicWork]）。
 * 两者的开关各自独立判断 —— 只开一个时另一个完全不跑，不会有隐藏的额外开销。
 *
 * **永远返回 `success`**（除非系统另有安排）：两个数据源都是（半）公开只读接口，
 * 失败就让下一次周期触发自然重试即可；返回 `retry` 只会让 WorkManager
 * 带着退避策略在电量紧张时反复唤醒，收益与代价不成比例。
 */
internal class LectureNoticeWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? QingxinApp ?: return Result.success()
        if (!app.isReady) return Result.success()
        if (LectureNoticeWatcher.isEnabled(applicationContext)) runNoticeCheck(app)
        if (LectureSentinel.isEnabled(applicationContext)) {
            runCatching { LectureSentinel.run(applicationContext) }
        }
        return Result.success()
    }

    private suspend fun runNoticeCheck(app: QingxinApp) {
        val notices = runCatching { app.lectureRepository.loadNotices() }
            .getOrNull()
            ?.notices
            .orEmpty()
        if (notices.isEmpty()) return
        runCatching { LectureNoticeWatcher.sync(applicationContext, notices) }
    }
}
