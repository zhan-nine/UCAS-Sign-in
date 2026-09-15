package com.ucas.qingxin.signin.widget

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
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

/** HyperOS 4×2（300×110）今日课程主卡。 */
class TodayCourseWidgetReceiver : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach {
            WidgetUpdater.update(context, appWidgetManager, it, WidgetUpdater.Size.WIDE_4X2)
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?,
    ) {
        WidgetUpdater.update(context, appWidgetManager, appWidgetId, WidgetUpdater.Size.WIDE_4X2)
    }

    override fun onEnabled(context: Context) {
        WidgetUpdater.requestUpdate(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (WidgetUpdater.dispatchMiuiOrAndroidUpdate(this, context, intent)) return
        super.onReceive(context, intent)
        WidgetUpdater.handleReceive(context, intent)
    }

    companion object {
        fun requestPin(activity: Activity): Boolean = WidgetUpdater.requestPin(activity)
        fun requestUpdate(context: Context) = WidgetUpdater.requestUpdate(context)
    }
}

/** HyperOS 2×2（110×110）。 */
class TodayCourseCompactWidgetReceiver : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach {
            WidgetUpdater.update(context, appWidgetManager, it, WidgetUpdater.Size.COMPACT_2X2)
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?,
    ) {
        WidgetUpdater.update(context, appWidgetManager, appWidgetId, WidgetUpdater.Size.COMPACT_2X2)
    }

    override fun onEnabled(context: Context) {
        WidgetUpdater.requestUpdate(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (WidgetUpdater.dispatchMiuiOrAndroidUpdate(this, context, intent)) return
        super.onReceive(context, intent)
        WidgetUpdater.handleReceive(context, intent)
    }
}

/** HyperOS 4×4（300×250）。 */
class TodayCourseTallWidgetReceiver : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach {
            WidgetUpdater.update(context, appWidgetManager, it, WidgetUpdater.Size.TALL_4X4)
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?,
    ) {
        WidgetUpdater.update(context, appWidgetManager, appWidgetId, WidgetUpdater.Size.TALL_4X4)
    }

    override fun onEnabled(context: Context) {
        WidgetUpdater.requestUpdate(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (WidgetUpdater.dispatchMiuiOrAndroidUpdate(this, context, intent)) return
        super.onReceive(context, intent)
        WidgetUpdater.handleReceive(context, intent)
    }
}

internal object WidgetUpdater {
    const val ACTION_SIGN_NOW = "com.ucas.qingxin.signin.action.WIDGET_SIGN_NOW"
    const val ACTION_REFRESH = "com.ucas.qingxin.signin.action.WIDGET_REFRESH"
    private const val MIUI_UPDATE = "miui.appwidget.action.APPWIDGET_UPDATE"

    enum class Size { WIDE_4X2, COMPACT_2X2, TALL_4X4 }

    private val zone = ZoneId.of("Asia/Shanghai")
    private val dayFmt = DateTimeFormatter.ofPattern("M月d日")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 按小米规范优先处理曝光刷新 / 系统刷新；返回 true 表示已处理。 */
    fun dispatchMiuiOrAndroidUpdate(
        provider: AppWidgetProvider,
        context: Context,
        intent: Intent,
    ): Boolean {
        val action = intent.action ?: return false
        if (action != MIUI_UPDATE && action != AppWidgetManager.ACTION_APPWIDGET_UPDATE) {
            return false
        }
        val mgr = AppWidgetManager.getInstance(context)
        val ids = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)
            ?: mgr.getAppWidgetIds(ComponentName(context, provider.javaClass))
        provider.onUpdate(context, mgr, ids)
        return true
    }

    fun requestPin(activity: Activity): Boolean {
        val mgr = AppWidgetManager.getInstance(activity)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !mgr.isRequestPinAppWidgetSupported) {
            Toast.makeText(activity, R.string.widget_pin_unsupported, Toast.LENGTH_LONG).show()
            return false
        }
        val wide = ComponentName(activity, TodayCourseWidgetReceiver::class.java)
        val ok = mgr.requestPinAppWidget(wide, null, null)
        if (!ok) {
            Toast.makeText(activity, R.string.widget_pin_unsupported, Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(activity, "请在系统弹窗中确认添加到桌面/负一屏", Toast.LENGTH_SHORT).show()
        }
        return ok
    }

    fun requestUpdate(context: Context) {
        val mgr = AppWidgetManager.getInstance(context)
        mgr.getAppWidgetIds(ComponentName(context, TodayCourseWidgetReceiver::class.java))
            .forEach { update(context, mgr, it, Size.WIDE_4X2) }
        mgr.getAppWidgetIds(ComponentName(context, TodayCourseCompactWidgetReceiver::class.java))
            .forEach { update(context, mgr, it, Size.COMPACT_2X2) }
        mgr.getAppWidgetIds(ComponentName(context, TodayCourseTallWidgetReceiver::class.java))
            .forEach { update(context, mgr, it, Size.TALL_4X4) }
    }

    fun handleReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_SIGN_NOW -> {
                scope.launch {
                    val msg = runCatching { performSign(context) }.getOrNull()
                    requestUpdate(context)
                    msg?.let {
                        launch(Dispatchers.Main) {
                            Toast.makeText(context.applicationContext, it, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            ACTION_REFRESH -> requestUpdate(context)
        }
    }

    fun update(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        size: Size,
    ) {
        val layout = when (size) {
            Size.WIDE_4X2 -> R.layout.widget_today_course
            Size.COMPACT_2X2 -> R.layout.widget_today_compact
            Size.TALL_4X4 -> R.layout.widget_today_tall
        }
        val views = RemoteViews(context.packageName, layout)
        val openApp = PendingIntent.getActivity(
            context,
            appWidgetId,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(android.R.id.background, openApp)

        val titleRes = when (size) {
            Size.COMPACT_2X2 -> R.string.widget_title_quick
            else -> R.string.widget_title_today
        }

        val app = context.applicationContext as? QingxinApp
        if (app == null || !app.authRepository.isLoggedIn()) {
            views.setTextViewText(R.id.widget_title, context.getString(titleRes))
            when (size) {
                Size.WIDE_4X2, Size.TALL_4X4 -> {
                    views.setTextViewText(R.id.widget_date, formatTodayLabel())
                    views.setTextViewText(R.id.widget_courses, "请先打开 App 登录")
                    views.setTextViewText(R.id.widget_focus, "")
                    if (size == Size.TALL_4X4) {
                        views.setTextViewText(R.id.widget_status, "")
                    }
                }
                Size.COMPACT_2X2 -> {
                    views.setTextViewText(R.id.widget_focus, "请先登录")
                }
            }
            views.setViewVisibility(R.id.widget_sign, View.GONE)
            views.setViewVisibility(R.id.widget_open, View.VISIBLE)
            views.setOnClickPendingIntent(R.id.widget_open, openApp)
            publishMiuiPriority(manager, appWidgetId, eventCode = "other")
            manager.updateAppWidget(appWidgetId, views)
            return
        }

        val cached = app.courseRepository.courses.value
        val list = cached?.courses.orEmpty()
        val now = System.currentTimeMillis()
        val (current, next) = app.courseRepository.currentAndNext(list, now)
        val sorted = list.sortedBy { app.courseRepository.parseBeginMs(it) ?: Long.MAX_VALUE }

        views.setTextViewText(R.id.widget_title, context.getString(titleRes))

        when (size) {
            Size.WIDE_4X2, Size.TALL_4X4 -> {
                val take = if (size == Size.TALL_4X4) 8 else 3
                val dateLabel = formatDateLabel(sorted.firstOrNull()?.day) ?: formatTodayLabel()
                // 列表必须包含「当前课」：去重时保留当前实例，窗口随当前课滑动
                val displayCourses = coursesForWidgetDisplay(sorted, current, take)
                val courseLines = formatCourseLines(displayCourses, current, app, now)
                    .ifBlank {
                        if (cached?.fromCache == true) "（缓存）暂无课程" else "今日暂无课程"
                    }
                views.setTextViewText(R.id.widget_date, dateLabel)
                views.setTextViewText(R.id.widget_courses, courseLines)
                views.setTextViewText(
                    R.id.widget_focus,
                    when {
                        next != null ->
                            "下一节 ${CourseTimeDisplay.hm(next.beginTime)} ${shortName(next.name) ?: next.name}"
                        current != null ->
                            "当前 ${shortName(current.name) ?: current.name}"
                        else -> "今日课程已结束"
                    },
                )
                if (size == Size.TALL_4X4) {
                    val status = app.attendanceRepository.uiStatus(current, now)
                    views.setTextViewText(
                        R.id.widget_status,
                        when {
                            current == null -> "未到签到时间"
                            current.signed -> "已签到"
                            status == AttendanceUiStatus.READY -> "可以签到"
                            else -> "等待签到"
                        },
                    )
                }
            }
            Size.COMPACT_2X2 -> {
                val focus = buildString {
                    if (current == null) {
                        append("暂无当前课")
                        if (next != null) append("\n下一节 ").append(shortName(next.name))
                    } else {
                        append(shortName(current.name) ?: current.name)
                        append('\n')
                        append(CourseTimeDisplay.range(current.beginTime, current.endTime))
                        if (current.signed) append(" ·已签")
                    }
                }
                views.setTextViewText(R.id.widget_focus, focus)
            }
        }

        views.setViewVisibility(R.id.widget_open, View.GONE)
        views.setViewVisibility(R.id.widget_sign, View.VISIBLE)
        val canAttemptSign = current != null && !current.signed
        val readyToSign = canAttemptSign &&
            app.attendanceRepository.uiStatus(current!!, now) == AttendanceUiStatus.READY
        val receiverClass = when (size) {
            Size.WIDE_4X2 -> TodayCourseWidgetReceiver::class.java
            Size.COMPACT_2X2 -> TodayCourseCompactWidgetReceiver::class.java
            Size.TALL_4X4 -> TodayCourseTallWidgetReceiver::class.java
        }
        if (canAttemptSign) {
            views.setInt(R.id.widget_sign, "setBackgroundResource", R.drawable.widget_btn)
            views.setTextViewText(
                R.id.widget_sign,
                context.getString(
                    if (size == Size.TALL_4X4) R.string.widget_sign else R.string.widget_sign_short,
                ),
            )
            val signPi = PendingIntent.getBroadcast(
                context,
                appWidgetId + 2000 + size.ordinal,
                Intent(context, receiverClass).setAction(ACTION_SIGN_NOW),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_sign, signPi)
        } else {
            views.setInt(R.id.widget_sign, "setBackgroundResource", R.drawable.widget_btn_disabled)
            views.setTextViewText(
                R.id.widget_sign,
                when {
                    current?.signed == true -> "已签"
                    else -> "暂不可签"
                },
            )
            views.setOnClickPendingIntent(R.id.widget_sign, openApp)
        }

        // 负一屏优先级：可签到 → notice1；进行中未签 → progress1；其余 → other
        val event = when {
            readyToSign -> "notice1"
            canAttemptSign -> "progress1"
            else -> "other"
        }
        publishMiuiPriority(manager, appWidgetId, event)
        manager.updateAppWidget(appWidgetId, views)
    }

    private fun publishMiuiPriority(manager: AppWidgetManager, appWidgetId: Int, eventCode: String) {
        runCatching {
            val options = manager.getAppWidgetOptions(appWidgetId) ?: Bundle()
            options.putString("miuiWidgetEventCode", eventCode)
            options.putString("miuiWidgetTimestamp", System.currentTimeMillis().toString())
            manager.updateAppWidgetOptions(appWidgetId, options)
        }
    }

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

    private suspend fun performSign(context: Context): String {
        val app = context.applicationContext as? QingxinApp ?: return "签到失败"
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
