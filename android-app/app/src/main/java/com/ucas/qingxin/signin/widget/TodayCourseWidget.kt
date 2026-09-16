package com.ucas.qingxin.signin.widget

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import android.widget.Toast
import com.ucas.qingxin.signin.QingxinApp
import com.ucas.qingxin.signin.R
import com.ucas.qingxin.signin.data.AttendanceUiStatus
import com.ucas.qingxin.signin.data.Course
import com.ucas.qingxin.signin.ui.MainActivity
import com.ucas.qingxin.signin.util.CourseTimeDisplay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 三种标准规格：2×2 / 4×2 / 4×4（桌面与负一屏共用同一套体系）。 */
enum class WidgetSize(val key: String) {
    COMPACT_2X2("compact"),
    WIDE_4X2("wide"),
    TALL_4X4("tall"),
}

/**
 * 所有规格共用的 Provider 基类。
 *
 * 集中处理「宿主差异」，各规格子类只声明自己的尺寸：
 * - `onUpdate` / `onAppWidgetOptionsChanged` 一律重绘：ColorOS / OriginOS
 *   在调整尺寸或拖动到负一屏时未必会走标准回调；
 * - `onEnabled` / `onDisabled` / `onDeleted` 维护兜底刷新任务的生命周期；
 * - `onReceive` 同时拦截小米与原生两种刷新广播，避免宿主只发其中一种。
 */
abstract class BaseCourseWidgetProvider(
    private val size: WidgetSize,
) : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        WidgetUpdater.updateAll(context, appWidgetManager, appWidgetIds, size)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?,
    ) {
        WidgetUpdater.update(context, appWidgetManager, appWidgetId, size)
    }

    override fun onEnabled(context: Context) {
        // 小部件刚被添加：对齐后台任务、边界闹钟与进程内定时器，并立刻抓一次真实数据。
        runCatching { WidgetRefreshScheduler.sync(context) }
        runCatching { WidgetRefreshScheduler.refreshData(context, force = true) }
    }

    override fun onDisabled(context: Context) {
        runCatching { WidgetRefreshScheduler.sync(context) }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        runCatching { WidgetRefreshScheduler.sync(context) }
    }

    override fun onRestored(
        context: Context,
        oldWidgetIds: IntArray,
        newWidgetIds: IntArray,
    ) {
        super.onRestored(context, oldWidgetIds, newWidgetIds)
        runCatching { WidgetRefreshScheduler.sync(context) }
        runCatching { WidgetUpdater.requestUpdate(context) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (WidgetUpdater.dispatchHostUpdate(this, context, intent)) return
        super.onReceive(context, intent)
        WidgetUpdater.handleReceive(context, intent)
    }
}

/** 4×2 主卡。 */
class TodayCourseWidgetReceiver : BaseCourseWidgetProvider(WidgetSize.WIDE_4X2) {
    companion object {
        fun requestPin(activity: Activity): Boolean =
            WidgetPinHelper.requestPin(activity, WidgetPinHelper.Spec.WIDE)

        fun requestUpdate(context: Context) = WidgetUpdater.requestUpdate(context)

        fun openAppDetailsForShortcutPermission(activity: Activity) =
            WidgetPinHelper.openAppDetails(activity)
    }
}

/** 2×2 快捷卡。 */
class TodayCourseCompactWidgetReceiver : BaseCourseWidgetProvider(WidgetSize.COMPACT_2X2) {
    companion object {
        fun requestPin(activity: Activity): Boolean =
            WidgetPinHelper.requestPin(activity, WidgetPinHelper.Spec.COMPACT)

        fun requestUpdate(context: Context) = WidgetUpdater.requestUpdate(context)
    }
}

/** 4×4 完整课表。 */
class TodayCourseTallWidgetReceiver : BaseCourseWidgetProvider(WidgetSize.TALL_4X4) {
    companion object {
        fun requestPin(activity: Activity): Boolean =
            WidgetPinHelper.requestPin(activity, WidgetPinHelper.Spec.TALL)

        fun requestUpdate(context: Context) = WidgetUpdater.requestUpdate(context)
    }
}

internal object WidgetUpdater {
    const val ACTION_SIGN_NOW = "com.ucas.qingxin.signin.action.WIDGET_SIGN_NOW"
    const val ACTION_REFRESH = "com.ucas.qingxin.signin.action.WIDGET_REFRESH"

    private val zone = ZoneId.of("Asia/Shanghai")
    private val dayFmt = DateTimeFormatter.ofPattern("M月d日")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 渲染状态：可能来自实时数据，也可能来自快照。
     * [priorityEvent] 供小米负一屏排序使用（notice1 > progress1 > other）。
     */
    private data class State(
        val loggedIn: Boolean,
        val dateText: String,
        val coursesText: String,
        val focusText: String,
        val statusText: String,
        val canSign: Boolean,
        val signed: Boolean,
        val priorityEvent: String,
    ) {
        fun toSnapshot() = WidgetSnapshotStore.Snapshot(
            loggedIn = loggedIn,
            dateText = dateText,
            coursesText = coursesText,
            focusText = focusText,
            statusText = statusText,
            canSign = canSign,
            signed = signed,
        )
    }

    fun updateAll(
        context: Context,
        manager: AppWidgetManager,
        appWidgetIds: IntArray,
        size: WidgetSize,
    ) {
        appWidgetIds.forEach { update(context, manager, it, size) }
    }

    /**
     * 单实例重绘。
     * 无论内部发生什么异常，都必须给宿主一个可显示的结果——
     * 否则宿主会显示「无法加载小部件」，用户只会认为小部件坏了。
     */
    fun update(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        size: WidgetSize,
    ) {
        val rendered = runCatching { buildAdaptiveViews(context, manager, appWidgetId, size) }
        val views = rendered.getOrNull()?.first ?: fallbackViews(context, size, appWidgetId)
        val event = rendered.getOrNull()?.second ?: "other"
        publishMiuiPriority(manager, appWidgetId, event)
        runCatching { manager.updateAppWidget(appWidgetId, views) }
    }

    /**
     * 纯本地重绘：不发起任何网络请求，只按当前缓存/快照重新渲染。
     *
     * 这是刷新链路里最便宜的一环，由进程内定时器（[WidgetTicker]）每分钟调用，
     * 目的是让「当前课 / 下一节 / 可签到」这类**随时间自然变化**的显示及时跟手。
     */
    fun refreshLocal(context: Context) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        runCatching {
            manager.getAppWidgetIds(ComponentName(context, TodayCourseCompactWidgetReceiver::class.java))
                .forEach { update(context, manager, it, WidgetSize.COMPACT_2X2) }
            manager.getAppWidgetIds(ComponentName(context, TodayCourseWidgetReceiver::class.java))
                .forEach { update(context, manager, it, WidgetSize.WIDE_4X2) }
            manager.getAppWidgetIds(ComponentName(context, TodayCourseTallWidgetReceiver::class.java))
                .forEach { update(context, manager, it, WidgetSize.TALL_4X4) }
        }
        // 宿主刷新后也确认一次边界闹钟：课表可能刚变过（例如今天新增了一节课）。
        runCatching { WidgetRefreshScheduler.ensureAlarm(context) }
    }

    /** 供应用内事件（登录成功 / 签到成功 / 手动刷新）调用。 */
    fun requestUpdate(context: Context) = refreshLocal(context)

    /** 处理原生 / 小米 Host 的刷新广播；返回 true 表示已处理。 */
    fun dispatchHostUpdate(
        provider: AppWidgetProvider,
        context: Context,
        intent: Intent,
    ): Boolean {
        val action = intent.action ?: return false
        if (action != WidgetHostCompat.ACTION_MIUI_APPWIDGET_UPDATE &&
            action != AppWidgetManager.ACTION_APPWIDGET_UPDATE
        ) {
            return false
        }
        runCatching {
            val manager = AppWidgetManager.getInstance(context)
            val ids = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)
                ?: manager.getAppWidgetIds(ComponentName(context, provider.javaClass))
            provider.onUpdate(context, manager, ids)
        }
        return true
    }

    fun handleReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_SIGN_NOW -> scope.launch {
                val message = runCatching { performSign(context) }.getOrNull()
                requestUpdate(context)
                message?.let {
                    launch(Dispatchers.Main) {
                        runCatching {
                            Toast.makeText(context.applicationContext, it, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            // 点标题区域 = 手动刷新。
            ACTION_REFRESH -> WidgetRefreshScheduler.refreshData(context, force = true)
        }
    }

    // ------------------------------------------------------------------ 渲染

    private fun buildAdaptiveViews(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        size: WidgetSize,
    ): Pair<RemoteViews, String> {
        // 行数取决于宿主实际给的高度，而每种尺寸/方向的高度不同；
        // 因此每个尺寸各自解析一次状态（都是内存/本地读取，无网络开销）。
        var firstState: State? = null
        val views = WidgetSizeCompat.initializeRemoteViews(manager, appWidgetId) { dpSize ->
            val state = resolveState(context, size, manager, appWidgetId, dpSize.heightDp)
            if (firstState == null) firstState = state
            buildViews(context, size, appWidgetId, state)
        }
        val state = firstState
            ?: resolveState(context, size, manager, appWidgetId, DEFAULT_ROWS_HEIGHT_DP)
        runCatching { WidgetSnapshotStore.of(context).write(size.key, state.toSnapshot()) }
        return views to state.priorityEvent
    }

    /** 优先使用实时数据；不可用时退回快照，最后退回「请登录 / 正在同步」提示。 */
    private fun resolveState(
        context: Context,
        size: WidgetSize,
        manager: AppWidgetManager,
        appWidgetId: Int,
        heightDp: Int,
    ): State {
        val rows = rowsFor(size, heightDp)
        val app = context.applicationContext as? QingxinApp

        val loggedIn = app != null && app.isReady &&
            runCatching { app.authRepository.isLoggedIn() }.getOrDefault(false)

        if (app != null && loggedIn) {
            runCatching { liveState(context, size, app, rows) }.getOrNull()?.let { return it }
        }

        val snapshot = WidgetSnapshotStore.of(context).read(size.key)

        if (!loggedIn) {
            // 未登录时**完全忽略快照**：既避免展示过期课表，也避免退出登录后
            // 残留上一位使用者的课程名称。
            return State(
                loggedIn = false,
                dateText = formatTodayLabel(),
                coursesText = context.getString(R.string.widget_need_login),
                focusText = context.getString(R.string.widget_need_login),
                statusText = "",
                canSign = false,
                signed = false,
                priorityEvent = "other",
            )
        }

        // 已登录但实时数据不可用（冷启动内存缓存为空，或加密存储异常）：
        // 用快照保证界面连续；签到按钮保持可点，点击时会自行拉取课表。
        if (snapshot != null && snapshot.loggedIn) {
            val today = formatTodayLabel()
            // 快照里的日期标签是「课程所属日期」；与今天不一致说明数据已跨天过期，
            // 此时宁可显示「正在同步」，也不能继续展示昨天的课表。
            // 注意：这里**不能**触发抓取——本函数会被 refreshData→refreshLocal→update
            // 递归调用，在此入队会形成无限递归。跨天补偿由
            // WidgetRefreshScheduler.tick / sync / 边界闹钟负责。
            val staleDay = snapshot.dateText.isNotBlank() && snapshot.dateText != today
            if (!staleDay) {
                return State(
                    loggedIn = true,
                    dateText = snapshot.dateText.ifBlank { today },
                    coursesText = snapshot.coursesText.ifBlank { context.getString(R.string.widget_no_course) },
                    focusText = snapshot.focusText,
                    statusText = snapshot.statusText,
                    canSign = snapshot.canSign,
                    signed = snapshot.signed,
                    priorityEvent = if (snapshot.canSign) "progress1" else "other",
                )
            }
        }

        return State(
            loggedIn = true,
            dateText = formatTodayLabel(),
            coursesText = context.getString(R.string.widget_syncing),
            focusText = context.getString(R.string.widget_syncing),
            statusText = "",
            canSign = false,
            signed = false,
            priorityEvent = "other",
        )
    }

    private fun liveState(
        context: Context,
        size: WidgetSize,
        app: QingxinApp,
        rows: Int,
    ): State? {
        // 冷启动（进程刚被宿主拉起）时内存缓存还是空的，此时**不能**当成
        // 「今天没有课」——那会写成「今日暂无课程 / 今日课程已结束」并覆盖快照。
        // 返回 null 让调用方回退到快照 / 「正在同步」。
        val cached = app.courseRepository.courses.value ?: return null
        val list = cached.courses
        val now = System.currentTimeMillis()
        val (current, next) = app.courseRepository.currentAndNext(list, now)
        val sorted = list.sortedBy { app.courseRepository.parseBeginMs(it) ?: Long.MAX_VALUE }

        var coursesText = ""
        var focusText = ""
        var statusText = ""
        var readyToSign = false
        val dateLabel = formatDateLabel(sorted.firstOrNull()?.day) ?: formatTodayLabel()

        when (size) {
            WidgetSize.COMPACT_2X2 -> {
                focusText = buildString {
                    if (current == null) {
                        append(context.getString(R.string.widget_no_current))
                        if (next != null) {
                            append('\n').append(context.getString(R.string.widget_next))
                                .append(' ').append(shortName(next.name))
                        }
                    } else {
                        append(shortName(current.name) ?: current.name)
                        append('\n')
                        append(CourseTimeDisplay.range(current.beginTime, current.endTime))
                        if (current.signed) append(" ·已签")
                    }
                }
            }
            WidgetSize.WIDE_4X2, WidgetSize.TALL_4X4 -> {
                // 列表必须包含「当前课」：去重时保留当前实例，窗口随当前课滑动
                val displayCourses = coursesForWidgetDisplay(sorted, current, rows)
                coursesText = formatCourseLines(displayCourses, current, app, now)
                    .ifBlank {
                        context.getString(
                            if (cached.fromCache) R.string.widget_no_course_cached
                            else R.string.widget_no_course,
                        )
                    }
                focusText = when {
                    next != null -> context.getString(R.string.widget_next) + " " +
                        CourseTimeDisplay.hm(next.beginTime) + " " +
                        (shortName(next.name) ?: next.name)
                    current != null -> context.getString(R.string.widget_current) + " " +
                        (shortName(current.name) ?: current.name)
                    else -> context.getString(R.string.widget_course_done)
                }
                if (size == WidgetSize.TALL_4X4) {
                    val status = app.attendanceRepository.uiStatus(current, now)
                    statusText = when {
                        current == null -> context.getString(R.string.widget_not_started)
                        current.signed -> context.getString(R.string.widget_signed)
                        status == AttendanceUiStatus.READY -> context.getString(R.string.widget_can_sign)
                        else -> context.getString(R.string.widget_waiting_sign)
                    }
                }
            }
        }

        val canSign = current != null && !current.signed
        if (canSign) {
            readyToSign = app.attendanceRepository.uiStatus(current, now) == AttendanceUiStatus.READY
        }
        return State(
            loggedIn = true,
            dateText = dateLabel,
            coursesText = coursesText,
            focusText = focusText,
            statusText = statusText,
            canSign = canSign,
            signed = current?.signed == true,
            priorityEvent = when {
                readyToSign -> "notice1"
                canSign -> "progress1"
                else -> "other"
            },
        )
    }

    private fun buildViews(
        context: Context,
        size: WidgetSize,
        appWidgetId: Int,
        state: State,
    ): RemoteViews {
        val layout = when (size) {
            WidgetSize.WIDE_4X2 -> R.layout.widget_today_course
            WidgetSize.COMPACT_2X2 -> R.layout.widget_today_compact
            WidgetSize.TALL_4X4 -> R.layout.widget_today_tall
        }
        val views = RemoteViews(context.packageName, layout)
        val openApp = openAppIntent(context, appWidgetId)
        views.setOnClickPendingIntent(android.R.id.background, openApp)

        views.setTextViewText(
            R.id.widget_title,
            context.getString(
                if (size == WidgetSize.COMPACT_2X2) R.string.widget_title_quick
                else R.string.widget_title_today,
            ),
        )

        when (size) {
            WidgetSize.WIDE_4X2, WidgetSize.TALL_4X4 -> {
                views.setTextViewText(R.id.widget_date, state.dateText)
                views.setTextViewText(R.id.widget_courses, state.coursesText)
                views.setTextViewText(R.id.widget_focus, state.focusText)
                if (size == WidgetSize.TALL_4X4) {
                    views.setTextViewText(R.id.widget_status, state.statusText)
                }
            }
            WidgetSize.COMPACT_2X2 -> views.setTextViewText(R.id.widget_focus, state.focusText)
        }

        if (!state.loggedIn) {
            views.setViewVisibility(R.id.widget_sign, View.GONE)
            views.setViewVisibility(R.id.widget_open, View.VISIBLE)
            views.setOnClickPendingIntent(R.id.widget_open, openApp)
            return views
        }

        views.setViewVisibility(R.id.widget_open, View.GONE)
        views.setViewVisibility(R.id.widget_sign, View.VISIBLE)

        if (state.canSign) {
            views.setInt(R.id.widget_sign, "setBackgroundResource", R.drawable.widget_btn)
            views.setTextViewText(
                R.id.widget_sign,
                context.getString(
                    if (size == WidgetSize.TALL_4X4) R.string.widget_sign else R.string.widget_sign_short,
                ),
            )
            val receiverClass = receiverOf(size)
            val signIntent = Intent(context, receiverClass).setAction(ACTION_SIGN_NOW)
            val signPending = PendingIntent.getBroadcast(
                context,
                appWidgetId + 2000 + size.ordinal,
                signIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_sign, signPending)
        } else {
            views.setInt(R.id.widget_sign, "setBackgroundResource", R.drawable.widget_btn_disabled)
            views.setTextViewText(
                R.id.widget_sign,
                context.getString(
                    if (state.signed) R.string.widget_signed_short else R.string.widget_sign_unavailable,
                ),
            )
            views.setOnClickPendingIntent(R.id.widget_sign, openApp)
        }
        return views
    }

    /** 极端异常下的兜底布局：永远是「可点开 App」的最小可用形态。 */
    private fun fallbackViews(context: Context, size: WidgetSize, appWidgetId: Int): RemoteViews {
        val layout = when (size) {
            WidgetSize.WIDE_4X2 -> R.layout.widget_today_course
            WidgetSize.COMPACT_2X2 -> R.layout.widget_today_compact
            WidgetSize.TALL_4X4 -> R.layout.widget_today_tall
        }
        return runCatching {
            val views = RemoteViews(context.packageName, layout)
            val openApp = openAppIntent(context, appWidgetId)
            views.setOnClickPendingIntent(android.R.id.background, openApp)
            views.setOnClickPendingIntent(R.id.widget_open, openApp)
            views.setTextViewText(
                R.id.widget_title,
                context.getString(
                    if (size == WidgetSize.COMPACT_2X2) R.string.widget_title_quick
                    else R.string.widget_title_today,
                ),
            )
            val hint = context.getString(R.string.widget_open_app_hint)
            when (size) {
                WidgetSize.COMPACT_2X2 -> views.setTextViewText(R.id.widget_focus, hint)
                WidgetSize.WIDE_4X2, WidgetSize.TALL_4X4 -> {
                    views.setTextViewText(R.id.widget_date, formatTodayLabel())
                    views.setTextViewText(R.id.widget_courses, hint)
                    views.setTextViewText(R.id.widget_focus, "")
                    if (size == WidgetSize.TALL_4X4) {
                        views.setTextViewText(R.id.widget_status, "")
                    }
                }
            }
            views.setViewVisibility(R.id.widget_sign, View.GONE)
            views.setViewVisibility(R.id.widget_open, View.VISIBLE)
            views
        }.getOrElse { RemoteViews(context.packageName, R.layout.widget_loading) }
    }

    private fun receiverOf(size: WidgetSize): Class<out AppWidgetProvider> = when (size) {
        WidgetSize.WIDE_4X2 -> TodayCourseWidgetReceiver::class.java
        WidgetSize.COMPACT_2X2 -> TodayCourseCompactWidgetReceiver::class.java
        WidgetSize.TALL_4X4 -> TodayCourseTallWidgetReceiver::class.java
    }

    private fun openAppIntent(context: Context, appWidgetId: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            appWidgetId,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** 负一屏优先级：可签到 → notice1；进行中未签 → progress1；其余 → other。 */
    private fun publishMiuiPriority(manager: AppWidgetManager, appWidgetId: Int, eventCode: String) {
        runCatching {
            val options = manager.getAppWidgetOptions(appWidgetId) ?: Bundle()
            options.putString("miuiWidgetEventCode", eventCode)
            options.putString("miuiWidgetTimestamp", System.currentTimeMillis().toString())
            manager.updateAppWidgetOptions(appWidgetId, options)
        }
    }

    // ------------------------------------------------------------- 文本与行数

    /** 依据宿主给的**实际**高度决定可见课程行数，避免拉伸后文字被裁切。 */
    private fun rowsFor(size: WidgetSize, heightDp: Int): Int = when (size) {
        WidgetSize.COMPACT_2X2 -> 3
        WidgetSize.WIDE_4X2 -> ((heightDp - 78) / 18).coerceIn(1, 9)
        WidgetSize.TALL_4X4 -> ((heightDp - 134) / 18).coerceIn(1, 12)
    }

    /** 宿主未提供任何尺寸信息时的兜底高度（4×2 的标准高度）。 */
    private const val DEFAULT_ROWS_HEIGHT_DP = 110

    /**
     * 小部件今日课表：
     * 1) 同名去重时优先保留「当前课」那一条；
     * 2) 截断时滑动窗口，保证当前课一定出现在可见列表中。
     */
    private fun coursesForWidgetDisplay(
        sorted: List<Course>,
        current: Course?,
        take: Int,
    ): List<Course> {
        val deduped = ArrayList<Course>()
        val seen = LinkedHashSet<String>()
        for (c in sorted) {
            val key = c.name.trim()
            val isCurrent = current != null && c.id == current.id
            if (key.isEmpty()) {
                deduped.add(c)
                continue
            }
            if (isCurrent) {
                val prev = deduped.indexOfFirst { it.name.trim() == key }
                if (prev >= 0) deduped[prev] = c else {
                    seen.add(key)
                    deduped.add(c)
                }
                continue
            }
            if (seen.add(key)) deduped.add(c)
        }
        if (current != null && deduped.none { it.id == current.id }) {
            val insertAt = deduped.indexOfFirst {
                it.beginTime.trim() > current.beginTime.trim()
            }.let { if (it < 0) deduped.size else it }
            deduped.add(insertAt, current)
        }
        if (deduped.size <= take) return deduped
        if (current == null) return deduped.take(take)
        val idx = deduped.indexOfFirst { it.id == current.id }
            .takeIf { it >= 0 }
            ?: deduped.indexOfFirst { it.name.trim() == current.name.trim() }
        if (idx < 0) return deduped.take(take)
        if (idx < take) return deduped.take(take)
        val end = (idx + 1).coerceAtMost(deduped.size)
        val start = (end - take).coerceAtLeast(0)
        return deduped.subList(start, end)
    }

    private fun formatCourseLines(
        courses: List<Course>,
        current: Course?,
        app: QingxinApp,
        now: Long,
    ): String {
        return courses.joinToString("\n") { c ->
            val hm = CourseTimeDisplay.hm(c.beginTime)
            val name = shortName(c.name) ?: c.name
            val mark = when {
                c.signed -> "✓已签"
                current != null && (current.id == c.id || current.name.trim() == c.name.trim()) -> {
                    when (app.attendanceRepository.uiStatus(c, now)) {
                        AttendanceUiStatus.READY -> "●可签"
                        else -> "●当前"
                    }
                }
                else -> "○待签"
            }
            "$hm  $name  $mark"
        }
    }

    private fun shortName(name: String?): String? {
        if (name.isNullOrBlank()) return null
        return if (name.length > 10) name.take(9) + "…" else name
    }

    private fun formatDateLabel(day: String?): String? {
        if (day.isNullOrBlank()) return null
        val key = day.trim().replace("-", "").replace("/", "")
        if (key.length < 8 || !key.take(8).all { it.isDigit() }) return null
        return try {
            LocalDate.parse(key.take(8), DateTimeFormatter.BASIC_ISO_DATE).format(dayFmt)
        } catch (_: Exception) {
            null
        }
    }

    private fun formatTodayLabel(): String = LocalDate.now(zone).format(dayFmt)

    // ------------------------------------------------------------------ 签到

    private suspend fun performSign(context: Context): String {
        val app = context.applicationContext as? QingxinApp ?: return "签到失败：应用未就绪"
        if (!app.isReady) return "签到失败：应用未就绪"
        val query = app.courseRepository.courses.value
            ?: runCatching { app.courseRepository.loadToday() }.getOrNull()
        val now = System.currentTimeMillis()
        val (current, _) = app.courseRepository.currentAndNext(query?.courses.orEmpty(), now)
        if (current == null) return "当前没有可签到课程"
        if (current.signed) return "「${current.name}」已签到"
        val result = runCatching { app.attendanceRepository.signOneClick(current) }.getOrNull()
        runCatching { app.courseRepository.loadToday() }
        return result?.message ?: "签到请求已发送"
    }
}
