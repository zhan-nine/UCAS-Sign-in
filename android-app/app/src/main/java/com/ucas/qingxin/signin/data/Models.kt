package com.ucas.qingxin.signin.data

data class SchoolSession(
    val userId: String,
    val sessionId: String,
    val studentNo: String,
)

data class Course(
    val id: String,
    val uuid: String,
    val name: String,
    val teacher: String,
    val beginTime: String,
    val endTime: String,
    val day: String,
    val signed: Boolean,
)

data class CourseQueryResult(
    val courses: List<Course>,
    val fromWeeklyFallback: Boolean,
    val message: String,
    val fromCache: Boolean = false,
)

enum class SignOutcome {
    SIGNED,
    QR_EXPIRED,
    OUTSIDE_SIGN_WINDOW,
    SIGN_RESULT_UNKNOWN,
}

data class SignResult(
    val outcome: SignOutcome,
    val message: String,
    val status: String,
    val errCode: String,
    val stuSignId: String,
)

enum class AttendanceUiStatus {
    NOT_STARTED,
    WAITING,
    READY,
    SIGNING,
    SIGNED,
    FAILED,
    QR_EXPIRED,
    LOGIN_EXPIRED,
}

data class QrSnapshot(
    val url: String,
    val schoolTimestampMs: Long,
    /** Absolute local wall-clock millis when this QR must be refreshed (from original app). */
    val expiresAtLocalMs: Long,
    /** Validity span of this QR frame in ms (= min(5s, remaining sync TTL)). */
    val validityDurationMs: Long,
    val remainingSeconds: Double,
)

data class UserSettings(
    val autoSignEnabled: Boolean = false,
    val notifyEnabled: Boolean = true,
    /** 低耗电模式：不常驻前台服务与常驻通知，仅保留「每节课一个闹钟」。 */
    val lowPowerMode: Boolean = false,
)

/**
 * 保活/耗电相关的实时状态，供设置页展示与告警。
 *
 * 其中 [autoStartConfirmed] 与 [lockConfirmed] 无法通过系统 API 查询，
 * 分别由「是否收到过开机广播」与「用户手动确认」代替。
 */
data class KeepAliveState(
    val autoSignEnabled: Boolean = false,
    val lowPowerMode: Boolean = false,
    val batteryExempt: Boolean = false,
    val exactAlarmAllowed: Boolean = false,
    val notificationsAllowed: Boolean = true,
    val daemonRunning: Boolean = false,
    val autoStartConfirmed: Boolean = false,
    val lockConfirmed: Boolean = false,
    val daemonBlocked: Boolean = false,
    val estimatedWakeupsToday: Int = 0,
)
