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
    /**
     * 课程层标识（与 [id] 的节次层 7 位编码不同）：`courseId` / `courseNum`。
     *
     * 学校课表接口一直会返回这两个字段，此前被丢弃。补抓它们用于：
     * 1. 讲座发现的回退阶梯（节次缺失时仍有课程层标识可寻址）；
     * 2. 与离线研究中的课程注册表做桥接对照。
     * 均为可空默认，兼容既有构造点与本地缓存。
     */
    val courseId: String = "",
    val courseNum: String = "",
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
    /**
     * 省电模式（旧名「低耗电模式」，取值语义不变，避免升级时把用户的开关重置）。
     *
     * 它现在影响的是**整个应用的后台刷新预算**（小部件周期、讲座巡检、重试上限、
     * 守护服务），而不再只是后台签到通道 —— 见 `PowerProfile`。
     */
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
    /** 系统是否处于省电模式（只收紧重试预算与小部件秒级重绘，不改用户档位）。 */
    val systemPowerSave: Boolean = false,
)
