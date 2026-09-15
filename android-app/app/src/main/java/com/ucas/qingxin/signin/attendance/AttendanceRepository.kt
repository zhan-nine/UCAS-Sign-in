package com.ucas.qingxin.signin.attendance

import com.ucas.qingxin.signin.auth.AuthRepository
import com.ucas.qingxin.signin.course.CourseRepository
import com.ucas.qingxin.signin.data.AttendanceUiStatus
import com.ucas.qingxin.signin.data.Course
import com.ucas.qingxin.signin.data.SignOutcome
import com.ucas.qingxin.signin.data.SignResult
import com.ucas.qingxin.signin.network.ApiException
import com.ucas.qingxin.signin.network.QingxinApiService
import com.ucas.qingxin.signin.qr.QrTimelineManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AttendanceRepository(
    private val api: QingxinApiService,
    private val auth: AuthRepository,
    private val courses: CourseRepository,
    private val qrTimeline: QrTimelineManager,
) {
    private val signMutex = Mutex()
    @Volatile private var inFlightCourseId: String? = null

    fun uiStatus(course: Course?, schoolNowMs: Long?): AttendanceUiStatus {
        if (course == null) return AttendanceUiStatus.NOT_STARTED
        if (course.signed) return AttendanceUiStatus.SIGNED
        if (inFlightCourseId == course.id) return AttendanceUiStatus.SIGNING
        val now = schoolNowMs ?: System.currentTimeMillis()
        val begin = courses.parseBeginMs(course)
        val end = courses.parseEndMs(course)
        if (begin == null || end == null) return AttendanceUiStatus.WAITING
        return when {
            now < begin - QingxinApiService.SIGN_WINDOW_LEAD_MS -> AttendanceUiStatus.NOT_STARTED
            now >= end -> AttendanceUiStatus.NOT_STARTED
            courses.isInSignWindow(course, now) -> AttendanceUiStatus.READY
            else -> AttendanceUiStatus.WAITING
        }
    }

    suspend fun signOneClick(course: Course): SignResult = signMutex.withLock {
        if (inFlightCourseId != null) {
            throw ApiException("SIGN_IN_PROGRESS", "签到进行中，请勿重复点击")
        }
        if (course.signed) {
            return SignResult(SignOutcome.OUTSIDE_SIGN_WINDOW, "学校提示已签到，请刷新课程状态", "0", "0", "")
        }
        inFlightCourseId = course.id
        try {
            var session = try {
                auth.requireSession()
            } catch (e: ApiException) {
                if (e.code == "LOGIN_EXPIRED") {
                    throw e
                }
                auth.refreshSession()
            }
            // Manual one-click: do not hard-block on local window guess; backend is authority.
            val courseKey = course.id.ifBlank { course.uuid }
            val qr = qrTimeline.refreshQr(courseKey)
            return try {
                api.submitAttendance(session, course.id, qr.schoolTimestampMs)
            } catch (e: ApiException) {
                if (e.code.startsWith("HTTP_") || e.message?.contains("登录") == true) {
                    session = auth.refreshSession()
                    val qr2 = qrTimeline.refreshQr(courseKey)
                    api.submitAttendance(session, course.id, qr2.schoolTimestampMs)
                } else {
                    throw e
                }
            }
        } finally {
            inFlightCourseId = null
        }
    }
}
