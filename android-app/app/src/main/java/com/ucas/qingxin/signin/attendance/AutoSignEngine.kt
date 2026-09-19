package com.ucas.qingxin.signin.attendance

import android.content.Context
import android.os.PowerManager
import com.ucas.qingxin.signin.QingxinApp
import com.ucas.qingxin.signin.R
import com.ucas.qingxin.signin.data.Course
import com.ucas.qingxin.signin.data.SignOutcome
import com.ucas.qingxin.signin.network.ApiException
import com.ucas.qingxin.signin.widget.TodayCourseWidgetReceiver
import com.ucas.qingxin.signin.widget.WidgetRefreshScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/** 自动签到单轮执行结果。 */
internal enum class EngineOutcome {
    /** 今日无课 / 都已签到 / 还没到随机时刻 —— 无事可做。 */
    IDLE,

    /** 签到成功（含「核对后发现已被签到」）。 */
    SIGNED,

    /** 非终态失败，窗口内还可以重试。 */
    RETRY,

    /** 终态失败（超过 deadline 或用尽预算），已发强制结果通知。 */
    FAILED,

    /** 用户正在手动签到，自动路径主动让路（不发通知）。 */
    YIELDED,

    /** 未开启自动签到 / 应用未就绪。 */
    DISABLED,

    /** 未登录。 */
    NEED_LOGIN,
}

/** 一节待签到的课及其冻结的随机时刻。 */
internal data class DueCourse(
    val course: Course,
    val beginMs: Long,
    val targetMs: Long,
) {
    val deadlineMs: Long get() = AutoSignRandomizer.deadlineMs(beginMs)
}

/**
 * 自动签到执行引擎 —— 闹钟、守护服务、WorkManager 三条路径共用的**唯一**执行体。
 *
 * 省电约束：
 * - **取数闸门**：本地课程数据 10 分钟内新鲜就零网络，直接复用内存 / 本地缓存；
 * - **校时复用**：优先用 [com.ucas.qingxin.signin.qr.QrTimelineManager] 的 30 秒 TTL 样本，
 *   仅在其为 null 时才真正请求学校时间（对比 1.1.11 每次唤醒必发 2 个 HTTP）；
 * - **重试预算按课次计数**（[attempts]），不论由哪条路径驱动，一节课的请求次数都有上限；
 * - **重试不排新闹钟**，只发生在当次唤醒的电量豁免窗口与守护进程内。
 */
internal object AutoSignEngine {

    /** 本地课程数据在这个时长内视为新鲜，直接复用、不发网络。 */
    private const val CACHE_FRESH_MS = 10L * 60L * 1000L

    /** 每个课次允许的签到请求总次数上限。 */
    const val MAX_ATTEMPTS = 8

    /** 系统省电模式下收敛预算，避免做无用功。 */
    private const val MAX_ATTEMPTS_POWER_SAVE = 3

    /** 前置提醒的提前量：随机时刻前 2 分钟。 */
    const val UPCOMING_LEAD_MS = 2L * 60L * 1000L

    /**
     * 未取得电池豁免时，Doze 会把 `setAndAllowWhileIdle` 推迟最多约 15 分钟。
     * 与其死等随机时刻，不如「进程既然醒着、且已进入容差范围就先签」——
     * 既不越出签到窗口，也不会因系统推迟而错过。
     */
    const val DOZE_SLACK_MS = 5L * 60L * 1000L

    /** `课次@日期` → 已发过终态通知（成功 / 失败），保证每节课只推一条。 */
    private val notified = ConcurrentHashMap<String, Boolean>()

    /** `课次@日期` → 已消耗的签到请求次数。 */
    private val attempts = ConcurrentHashMap<String, Int>()

    /**
     * 串行化 [runOnce]：闹钟、守护服务、WorkManager 可能在同一瞬间醒来，
     * 用 `tryLock` 让后来者直接返回，避免对后端发出重复签到请求。
     */
    private val runMutex = Mutex()

    /** 用户关闭自动签到 / 退出登录时清空运行态。 */
    fun resetRuntime() {
        notified.clear()
        attempts.clear()
        AutoSignRandomizer.clearAll()
    }

    // ------------------------------------------------------------------ 主流程

    suspend fun runOnce(context: Context, app: QingxinApp): EngineOutcome {
        if (!app.isReady) return EngineOutcome.DISABLED
        if (!app.attendanceScheduler.getSettings().autoSignEnabled) return EngineOutcome.DISABLED
        // 已有一次执行在进行中：直接返回，绝不并发重复请求后端。
        if (!runMutex.tryLock()) return EngineOutcome.IDLE
        return try {
            runLocked(context, app)
        } finally {
            runMutex.unlock()
        }
    }

    private suspend fun runLocked(context: Context, app: QingxinApp): EngineOutcome {
        val today = AutoSignRandomizer.todayKey()
        AutoSignRandomizer.pruneBefore(today)
        pruneRuntime(today)

        if (!runCatching { app.authRepository.isLoggedIn() }.getOrDefault(false)) {
            refreshDaemonStatus(context, app, emptyList())
            return EngineOutcome.NEED_LOGIN
        }

        val courses = loadCourses(context, app) ?: return EngineOutcome.RETRY
        if (courses.isEmpty()) {
            refreshDaemonStatus(context, app, courses)
            return EngineOutcome.IDLE
        }

        val parseBegin: (Course) -> Long? = { c ->
            runCatching { app.courseRepository.parseBeginMs(c) }.getOrNull()
        }
        val targetOf: (Course, Long) -> Long = { c, begin ->
            AutoSignRandomizer.targetMsFor(courseIdOf(c), begin, System.currentTimeMillis(), today)
        }

        val open = pending(courses, today)
        val schoolNow = schoolNowMs(app)
        val due = AutoSignSelector.selectDue(
            open,
            schoolNow,
            parseBegin,
            targetOf,
            toleranceMs = earlyToleranceMs(context),
        )
        if (due == null) {
            expireStale(context, app, open, schoolNow, today, parseBegin)
            refreshDaemonStatus(context, app, courses)
            return EngineOutcome.IDLE
        }

        val begin = parseBegin(due) ?: run {
            refreshDaemonStatus(context, app, courses)
            return EngineOutcome.IDLE
        }
        val key = keyOf(courseIdOf(due), today)
        val targetMs = targetOf(due, begin)
        announceUpcoming(app, due, targetMs)

        val cap = attemptCap(context)
        val used = attempts[key] ?: 0

        // 预算已用尽：先核对最新状态（可能已被手动签掉），再决定发不发失败通知。
        if (used >= cap) {
            if (settleAlreadySigned(context, app, due, key, today)) return EngineOutcome.SIGNED
            finishFailed(context, app, due, key, null)
            return EngineOutcome.FAILED
        }
        attempts[key] = used + 1

        val result = try {
            app.attendanceRepository.trySignOneClick(due)
        } catch (e: ApiException) {
            if (e.code == "LOGIN_EXPIRED" || e.code == "LOGIN_REJECTED") {
                runCatching {
                    app.notifier.notifyAutoSignResult(false, due.id, due.name, e.message ?: e.code)
                    app.notifier.clearUpcoming(due.id)
                }
                runCatching { app.attendanceScheduler.stop() }
                return EngineOutcome.NEED_LOGIN
            }
            return EngineOutcome.RETRY
        } catch (_: Exception) {
            return EngineOutcome.RETRY
        }

        // null = 用户此刻正在手动签到，自动路径让路并保持安静。
        if (result == null) {
            refreshDaemonStatus(context, app, courses)
            return EngineOutcome.YIELDED
        }

        if (result.outcome != SignOutcome.SIGNED) {
            val withinWindow = schoolNowMs(app) <= AutoSignRandomizer.deadlineMs(begin)
            if (withinWindow && (attempts[key] ?: 0) < cap) return EngineOutcome.RETRY
            // 终结之前再核对一次：用户可能刚好手动签到成功，此时绝不能报「自动签到失败」。
            if (settleAlreadySigned(context, app, due, key, today)) return EngineOutcome.SIGNED
            finishFailed(context, app, due, key, result.message)
            return EngineOutcome.FAILED
        }

        return settleSigned(context, app, due, key, today, result.message)
    }

    // ------------------------------------------------------------------ 查询 / 状态

    /** 此刻「已经到达随机时刻、可以立刻尝试」的课（不产生任何网络请求）。 */
    fun dueNow(context: Context, app: QingxinApp, courses: List<Course>? = null): Course? {
        if (!app.isReady) return null
        val list = effectiveCourses(app, courses) ?: return null
        if (list.isEmpty()) return null
        val today = AutoSignRandomizer.todayKey()
        val now = System.currentTimeMillis()
        val parseBegin: (Course) -> Long? = { c ->
            runCatching { app.courseRepository.parseBeginMs(c) }.getOrNull()
        }
        val cap = attemptCap(context)
        val candidates = pending(list, today)
            .filter { (attempts[keyOf(courseIdOf(it), today)] ?: 0) < cap }
        return AutoSignSelector.selectDue(
            candidates,
            now,
            parseBegin,
            { c, begin -> AutoSignRandomizer.targetMsFor(c.id, begin, now, today) },
            toleranceMs = earlyToleranceMs(context),
        )
    }

    /** 今日下一个待签课次（含已冻结的随机时刻）。用于排闹钟与守护服务计时。 */
    fun nextDue(app: QingxinApp, courses: List<Course>? = null): DueCourse? {
        if (!app.isReady) return null
        val list = effectiveCourses(app, courses) ?: return null
        if (list.isEmpty()) return null
        val today = AutoSignRandomizer.todayKey()
        val now = System.currentTimeMillis()
        val parseBegin: (Course) -> Long? = { c ->
            runCatching { app.courseRepository.parseBeginMs(c) }.getOrNull()
        }
        val picked = AutoSignSelector.selectNext(list, now, parseBegin) { c, begin ->
            AutoSignRandomizer.targetMsFor(courseIdOf(c), begin, now, today)
        } ?: return null
        val begin = parseBegin(picked.first) ?: return null
        return DueCourse(picked.first, begin, picked.second)
    }

    /** 常驻通知文案。唤醒成本为零，只在进程本来就醒着时调用。 */
    fun daemonStatusText(context: Context, app: QingxinApp, courses: List<Course>? = null): String {
        if (!runCatching { app.authRepository.isLoggedIn() }.getOrDefault(false)) {
            return context.getString(R.string.auto_sign_status_need_login)
        }
        val list = effectiveCourses(app, courses)
            ?: return context.getString(R.string.auto_sign_status_waiting)
        if (list.isEmpty()) return context.getString(R.string.auto_sign_status_no_course)
        val next = nextDue(app, list)
            ?: return context.getString(R.string.auto_sign_status_all_done)
        return context.getString(
            R.string.auto_sign_status_next,
            AttendanceNotifier.formatClock(next.targetMs),
            next.course.name,
        )
    }

    fun refreshDaemonStatus(context: Context, app: QingxinApp, courses: List<Course>?) {
        val text = runCatching { daemonStatusText(context, app, courses) }.getOrNull() ?: return
        runCatching { app.notifier.updateDaemonStatus(text) }
    }

    // ------------------------------------------------------------------ 内部

    private fun keyOf(courseId: String, today: String): String =
        AutoSignRandomizer.key(courseId, today)

    /** 课程标识：`id` 为空时退回 `uuid`，避免不同课次共用同一个 key。 */
    private fun courseIdOf(course: Course): String = course.id.ifBlank { course.uuid }

    private fun upcomingKeyOf(courseId: String, today: String): String =
        "upcoming:" + AutoSignRandomizer.key(courseId, today)

    private fun pruneRuntime(today: String) {
        notified.keys.filterNot { it.endsWith("@$today") }.forEach { notified.remove(it) }
        attempts.keys.filterNot { it.endsWith("@$today") }.forEach { attempts.remove(it) }
    }

    /** 还没出终态结果、也没签到的课次。 */
    private fun pending(courses: List<Course>, today: String): List<Course> =
        courses.filterNot { it.signed || notified.containsKey(keyOf(courseIdOf(it), today)) }

    /** 返回 true 表示本次是第一次标记（调用方据此决定是否发通知）。 */
    private fun markNotified(key: String): Boolean = notified.putIfAbsent(key, true) == null

    private fun attemptCap(context: Context): Int =
        if (isPowerSaveMode(context)) MAX_ATTEMPTS_POWER_SAVE else MAX_ATTEMPTS

    private fun isPowerSaveMode(context: Context): Boolean = runCatching {
        (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true
    }.getOrDefault(false)

    /** 未取得电池豁免时允许「提前一点签」，以对冲 Doze 对非精确闹钟的推迟。 */
    private fun earlyToleranceMs(context: Context): Long =
        if (KeepAliveHelper.isBatteryExempt(context)) 0L else DOZE_SLACK_MS

    /** 签到成功后的统一收尾：清随机值、发强制成功通知、刷新课表与常驻通知。 */
    private suspend fun settleSigned(
        context: Context,
        app: QingxinApp,
        course: Course,
        key: String,
        today: String,
        detail: String,
    ): EngineOutcome {
        attempts.remove(key)
        markNotified(key)
        AutoSignRandomizer.forget(courseIdOf(course), today)
        runCatching {
            app.notifier.clearUpcoming(course.id)
            app.notifier.notifyAutoSignResult(true, course.id, course.name, detail)
        }
        runCatching { app.courseRepository.loadToday() }
        runCatching { WidgetRefreshScheduler.markDataFetched(context) }
        runCatching { TodayCourseWidgetReceiver.requestUpdate(context) }
        runCatching { AttendanceScheduler.ensureSchedule(context) }
        refreshDaemonStatus(context, app, null)
        return EngineOutcome.SIGNED
    }

    /**
     * 终结失败之前核对一次服务端的真实状态。
     *
     * 典型场景：用户在我们重试的空档自己手动点了「一键签到」——
     * 这时**绝不能**再推一条「自动签到失败」，否则会让用户以为签到没成功。
     *
     * @return true 表示该课其实已签到（已按成功收尾，调用方不要再发失败通知）。
     */
    private suspend fun settleAlreadySigned(
        context: Context,
        app: QingxinApp,
        course: Course,
        key: String,
        today: String,
    ): Boolean {
        val refreshed = runCatching { app.courseRepository.loadToday().courses }.getOrNull()
            ?: return false
        if (refreshed.firstOrNull { courseIdOf(it) == courseIdOf(course) }?.signed != true) return false
        attempts.remove(key)
        markNotified(key)
        AutoSignRandomizer.forget(courseIdOf(course), today)
        runCatching { app.notifier.clearUpcoming(course.id) }
        runCatching { TodayCourseWidgetReceiver.requestUpdate(context) }
        refreshDaemonStatus(context, app, refreshed)
        return true
    }

    private fun finishFailed(
        context: Context,
        app: QingxinApp,
        course: Course,
        key: String,
        detail: String?,
    ) {
        if (markNotified(key)) {
            runCatching {
                app.notifier.notifyAutoSignResult(
                    false,
                    course.id,
                    course.name,
                    detail ?: context.getString(R.string.auto_sign_fail_generic),
                )
                app.notifier.clearUpcoming(course.id)
            }
        }
        AutoSignRandomizer.forget(courseIdOf(course), AutoSignRandomizer.todayKey())
        refreshDaemonStatus(context, app, null)
    }

    /** 签到前提醒：仅在进程本就醒着时顺带发出，**绝不排专用闹钟**。 */
    private fun announceUpcoming(app: QingxinApp, course: Course, targetMs: Long) {
        val remain = targetMs - System.currentTimeMillis()
        if (remain !in 1..UPCOMING_LEAD_MS) return
        if (!markNotified(upcomingKeyOf(courseIdOf(course), AutoSignRandomizer.todayKey()))) return
        runCatching { app.notifier.notifyAutoSignUpcoming(course.id, course.name, targetMs) }
    }

    /**
     * 窗口已过、却既没签到成功也没通知过的课次 → 补一条终态失败通知。
     * 覆盖「设备长时间 Doze / 被省电策略掐掉，我们根本没醒过」的情形。
     *
     * 同样会先核对服务端状态，避免对「其实已经签到」的课次误报失败。
     */
    private suspend fun expireStale(
        context: Context,
        app: QingxinApp,
        courses: List<Course>,
        nowMs: Long,
        today: String,
        parseBegin: (Course) -> Long?,
    ) {
        val stale = courses.asSequence()
            .filter { !it.signed }
            .mapNotNull { c -> parseBegin(c)?.let { b -> c to b } }
            .filter { (_, begin) -> nowMs > AutoSignRandomizer.deadlineMs(begin) }
            .firstOrNull() ?: return
        val course = stale.first
        val key = keyOf(courseIdOf(course), today)
        if (notified.containsKey(key)) return
        if (settleAlreadySigned(context, app, course, key, today)) return
        if (!markNotified(key)) return
        runCatching {
            app.notifier.notifyAutoSignResult(
                false,
                course.id,
                course.name,
                context.getString(R.string.auto_sign_fail_expired),
            )
            app.notifier.clearUpcoming(course.id)
        }
        AutoSignRandomizer.forget(courseIdOf(course), today)
    }

    /**
     * 取数闸门：本地数据够新就零网络。
     * @return null 表示「取不到数据」（网络失败且无缓存），调用方应重试。
     *
     * 三条返回路径都必须过 [exclude]：这是**唯一**同时覆盖实签（`runLocked`）
     * 与闹钟排程（`nextDue`）的注入点。
     */
    private suspend fun loadCourses(context: Context, app: QingxinApp): List<Course>? {
        val today = AutoSignRandomizer.todayKey()
        val fresh = WidgetRefreshScheduler.lastDataDay(context) == today &&
            System.currentTimeMillis() - WidgetRefreshScheduler.lastDataAt(context) < CACHE_FRESH_MS
        if (fresh) {
            app.courseRepository.courses.value?.let { return exclude(app, it.courses) }
        }
        val studentNo = runCatching { app.authRepository.session.value?.studentNo }.getOrNull()
        app.courseRepository.cachedToday(studentNo.orEmpty())?.let { return exclude(app, it.courses) }
        return runCatching { app.courseRepository.loadToday().courses }.getOrNull()?.let {
            exclude(app, it)
        }
    }

    /**
     * 同步版本的只读读取（内存 → 本地缓存），绝不发网络。
     *
     * **刻意不过 [exclude]**：这是「原始课程」的取数函数，唯一的调用方是
     * [effectiveCourses]，由后者统一过滤。若将来直接调用它，请自己补上排除判定，
     * 否则被用户排除的课会重新出现在结果里。
     */
    private fun loadCoursesSync(app: QingxinApp): List<Course>? {
        app.courseRepository.courses.value?.let { return it.courses }
        val studentNo = runCatching { app.authRepository.session.value?.studentNo }.getOrNull()
        return app.courseRepository.cachedToday(studentNo.orEmpty())?.courses
    }

    /**
     * 解析课程来源并统一剔除「不打卡」的课程。
     *
     * 之所以把它单独抽成一个入口，而不是只在 [loadCourses] / [loadCoursesSync] 里过滤：
     * `dueNow` / `nextDue` / `daemonStatusText` 都允许调用方**直接传入**一个课程列表
     * （守护服务与设置页就是这么做的），那条路径会绕过取数函数。
     * 过滤放在这里，三条查询入口与两条执行入口就都覆盖到了。
     *
     * 过滤失败（尚未初始化、prefs 异常）时返回**原始列表**：
     * 宁可多签一节，也不能因为读排除表失败就整个自动签到罢工。
     */
    private fun effectiveCourses(app: QingxinApp, courses: List<Course>?): List<Course>? =
        (courses ?: loadCoursesSync(app))?.let { exclude(app, it) }

    /** 剔除用户标记为「今天不打卡 / 长期不打卡」的课程。见 [AutoSignExclusions]。 */
    private fun exclude(app: QingxinApp, courses: List<Course>): List<Course> {
        if (courses.isEmpty()) return courses
        return runCatching {
            val store = app.autoSignExclusionStore
            AutoSignExclusions.filter(
                courses = courses,
                permanent = store.permanentKeys(),
                todayOnly = store.todayKeys(AutoSignRandomizer.todayKey()),
            )
        }.getOrDefault(courses)
    }

    /** 学校时间：优先复用 30 秒 TTL 的校时样本，其次才真正请求。 */
    private suspend fun schoolNowMs(app: QingxinApp): Long =
        app.qrTimeline.currentSchoolTimeOrNull()
            ?: runCatching { app.qrTimeline.syncClock().schoolNowMs }
                .getOrDefault(System.currentTimeMillis())
}

/**
 * 「该不该现在签到」的纯逻辑，抽出来便于单元测试。
 */
internal object AutoSignSelector {

    /**
     * 挑出此刻应当自动签到的课。
     *
     * 条件：未签到、开始时间可解析、随机时刻已到（允许 [toleranceMs] 的提前量）、
     * 且未超过 deadline。多个同时满足时取随机时刻最早的一节（只签一节，避免并发请求）。
     */
    fun selectDue(
        courses: List<Course>,
        nowMs: Long,
        parseBegin: (Course) -> Long?,
        targetOf: (Course, Long) -> Long,
        toleranceMs: Long = 0L,
    ): Course? = courses.asSequence()
        .filter { !it.signed }
        .mapNotNull { c -> parseBegin(c)?.let { b -> Triple(c, b, targetOf(c, b)) } }
        .filter { (_, begin, target) ->
            nowMs >= target - toleranceMs && nowMs <= AutoSignRandomizer.deadlineMs(begin)
        }
        .minByOrNull { it.third }
        ?.first

    /**
     * 今日下一个「将要签到」的课次：随机时刻**还没到**、且窗口尚未彻底结束。
     *
     * 刻意要求 `target > nowMs`：随机时刻已过的课次说明已经由当次唤醒处理过，
     * 不应再为它排新的闹钟。
     */
    fun selectNext(
        courses: List<Course>,
        nowMs: Long,
        parseBegin: (Course) -> Long?,
        targetOf: (Course, Long) -> Long,
    ): Pair<Course, Long>? = courses.asSequence()
        .filter { !it.signed }
        .mapNotNull { c -> parseBegin(c)?.let { b -> Triple(c, b, targetOf(c, b)) } }
        .filter { (_, begin, target) ->
            target > nowMs && nowMs <= AutoSignRandomizer.deadlineMs(begin)
        }
        .minByOrNull { it.third }
        ?.let { it.first to it.third }
}

/**
 * 窗口内的有预算重试。
 *
 * 关键省电约定：重试**不排任何新闹钟**，只在「本次唤醒」已经拿到的电量豁免窗口
 * （约 10 秒）以及守护服务进程内进行；进程被销毁则自然停止，
 * 由下一次唤醒（闹钟 / WorkManager / 守护服务计时）接着判断。
 * 请求次数上限由 [AutoSignEngine] 按课次统一计数，因此多路径叠加也不会放大请求量。
 */
internal object AutoSignWindowRunner {

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 重试节奏：60–90 秒，避免形成可识别的固定间隔。 */
    fun retryDelayMs(): Long = Random.nextLong(60_000L, 90_000L)

    /** 进入重试循环并**等待它结束**（供守护服务这类有长生命周期协程的位置使用）。 */
    suspend fun run(context: Context, maxRounds: Int = AutoSignEngine.MAX_ATTEMPTS) {
        if (!mutex.tryLock()) return
        try {
            var round = 0
            while (round < maxRounds) {
                val app = context as? QingxinApp ?: return
                when (AutoSignEngine.runOnce(context, app)) {
                    EngineOutcome.RETRY -> {
                        round++
                        if (round >= maxRounds) return
                        delay(retryDelayMs())
                    }
                    else -> return
                }
            }
        } finally {
            mutex.unlock()
        }
    }

    /**
     * 在独立作用域里跑，并设**硬超时**。
     * 供 `BroadcastReceiver` 等无法长时间停留的位置使用：绝不超过 goAsync 的预算。
     */
    fun launch(context: Context, timeBudgetMs: Long = 20_000L) {
        val appContext = context.applicationContext
        scope.launch {
            withTimeoutOrNull(timeBudgetMs) {
                run(appContext, maxRounds = Int.MAX_VALUE)
            }
        }
    }
}
