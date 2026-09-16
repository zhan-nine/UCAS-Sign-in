package com.ucas.qingxin.signin.attendance

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ucas.qingxin.signin.R
import com.ucas.qingxin.signin.ui.MainActivity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 签到相关通知。
 *
 * 两个渠道（渠道重要性创建后**不可修改**，因此常驻通知必须用新 id）：
 * - [CHANNEL_ID] `attendance`（DEFAULT）：手动结果、自动结果、前置提醒 —— 会响铃/震动；
 * - [CHANNEL_STATUS] `auto_sign_status`（LOW）：常驻状态通知 —— 静默、不打扰。
 */
class AttendanceNotifier(private val context: Context) {

    /** 上一次已渲染的常驻通知文案（用于避免无效 notify）。 */
    @Volatile
    private var lastStatusText: String? = null

    init {
        ensureChannels(context)
    }

    // ------------------------------------------------------------------ 手动路径（语义不变）

    fun notifyClassSoon(courseName: String) =
        show(ID_CLASS, "即将上课", courseName)

    fun notifyReady(courseName: String) =
        show(ID_READY, "可以签到", courseName)

    fun notifySuccess(courseName: String) =
        show(ID_SUCCESS, "签到成功", courseName)

    fun notifyFailure(message: String) =
        show(ID_FAIL, "签到失败", message)

    fun notifyLoginExpired() =
        show(ID_LOGIN, "登录失效", "请打开应用重新登录")

    // ------------------------------------------------------------------ 常驻状态通知

    /**
     * 常驻状态通知（由守护前台服务持有）。
     * 静默、不可划掉、不显示时间戳，避免在通知栏里反复重绘。
     */
    fun daemonNotification(text: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.auto_sign_daemon_title))
            .setContentText(text)
            .setContentIntent(openAppIntent(ID_DAEMON))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    /**
     * 更新常驻通知内容。
     * **内容未变化时直接返回**，不调用 `notify()` —— 避免通知栏与系统 UI 无意义重绘。
     */
    fun updateDaemonStatus(text: String) {
        if (text == lastStatusText) return
        lastStatusText = text
        runCatching {
            NotificationManagerCompat.from(context).notify(ID_DAEMON, daemonNotification(text))
        }
    }

    fun clearDaemonStatus() {
        lastStatusText = null
        runCatching { NotificationManagerCompat.from(context).cancel(ID_DAEMON) }
    }

    // ------------------------------------------------------------------ 自动签到专用

    /**
     * 签到前提醒：将在什么时刻、为哪一门课自动签到。
     * **不排专用闹钟**，只在进程本就醒着的时机顺带发出（零额外唤醒成本）。
     */
    fun notifyAutoSignUpcoming(courseId: String, courseName: String, targetMs: Long) {
        show(
            upcomingId(courseId),
            context.getString(R.string.auto_sign_upcoming_title),
            context.getString(
                R.string.auto_sign_upcoming_body,
                formatClock(targetMs),
                courseName,
            ),
        )
    }

    /**
     * 自动签到结果通知 —— **强制发出**。
     *
     * 刻意**不检查** `notifyEnabled`：「自动签到」是用户明确开启的后台行为，
     * 其结果属于必须让用户知晓的信息（成功与否直接影响考勤）。
     * 仅检查系统通知权限。
     */
    fun notifyAutoSignResult(
        success: Boolean,
        courseId: String,
        courseName: String,
        detail: String,
    ) {
        val title = context.getString(
            if (success) R.string.auto_sign_result_ok_title else R.string.auto_sign_result_fail_title,
        )
        val body = if (success) {
            context.getString(R.string.auto_sign_result_ok_body, courseName)
        } else {
            context.getString(R.string.auto_sign_result_fail_body, courseName, detail)
        }
        show(resultId(courseId), title, body)
    }

    fun clearUpcoming(courseId: String) {
        runCatching { NotificationManagerCompat.from(context).cancel(upcomingId(courseId)) }
    }

    // ------------------------------------------------------------------ 内部

    private fun show(id: Int, title: String, body: String) {
        if (!canPost()) return
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(openAppIntent(id))
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    private fun openAppIntent(id: Int): PendingIntent = PendingIntent.getActivity(
        context,
        id,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun canPost(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        const val CHANNEL_ID = "attendance"
        const val CHANNEL_STATUS = "auto_sign_status"

        /**
         * 建立通知渠道。**必须可独立调用**：守护前台服务可能在应用依赖
         * 尚未初始化完成（[com.ucas.qingxin.signin.QingxinApp.isReady] == false）时启动，
         * 此时仍需要一个可用的渠道，否则 `startForeground` 会失败。
         */
        fun ensureChannels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            runCatching {
                val nm = context.getSystemService(NotificationManager::class.java) ?: return
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        context.getString(R.string.channel_attendance),
                        NotificationManager.IMPORTANCE_DEFAULT,
                    ).apply {
                        description = context.getString(R.string.channel_attendance_desc)
                    },
                )
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_STATUS,
                        context.getString(R.string.channel_auto_status),
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply {
                        description = context.getString(R.string.channel_auto_status_desc)
                        setShowBadge(false)
                    },
                )
            }
        }

        /** 守护前台服务的通知 id（也是常驻状态通知的 id）。 */
        const val ID_DAEMON = 2001

        private const val ID_CLASS = 1001
        private const val ID_READY = 1002
        private const val ID_SUCCESS = 1003
        private const val ID_FAIL = 1004
        private const val ID_LOGIN = 1005

        private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
        private val clockFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        /** 把 epoch millis 按学校时区格式化为 `HH:mm`。 */
        fun formatClock(ms: Long): String =
            runCatching { Instant.ofEpochMilli(ms).atZone(zone).format(clockFmt) }
                .getOrDefault("--:--")

        /** 同一门课的前置提醒共用一个 id：新课覆盖旧提醒，不堆叠。 */
        fun upcomingId(courseId: String): Int = 4100 + (courseId.hashCode().rem(500) + 500) % 500

        /** 同一门课的结果通知共用一个 id：重试/重复不会刷出多条。 */
        fun resultId(courseId: String): Int = 5100 + (courseId.hashCode().rem(500) + 500) % 500
    }
}
