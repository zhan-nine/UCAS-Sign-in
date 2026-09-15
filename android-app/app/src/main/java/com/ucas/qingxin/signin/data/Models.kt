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
)
