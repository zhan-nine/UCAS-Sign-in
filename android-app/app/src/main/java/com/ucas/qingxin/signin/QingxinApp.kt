package com.ucas.qingxin.signin

import android.app.Application
import com.ucas.qingxin.signin.attendance.AttendanceNotifier
import com.ucas.qingxin.signin.attendance.AttendanceRepository
import com.ucas.qingxin.signin.attendance.AttendanceScheduler
import com.ucas.qingxin.signin.auth.AuthRepository
import com.ucas.qingxin.signin.auth.SecureCredentialStore
import com.ucas.qingxin.signin.course.CourseRepository
import com.ucas.qingxin.signin.network.QingxinApiService
import com.ucas.qingxin.signin.qr.QrTimelineManager

class QingxinApp : Application() {
    lateinit var api: QingxinApiService
        private set
    lateinit var credentialStore: SecureCredentialStore
        private set
    lateinit var authRepository: AuthRepository
        private set
    lateinit var courseRepository: CourseRepository
        private set
    lateinit var qrTimeline: QrTimelineManager
        private set
    lateinit var attendanceRepository: AttendanceRepository
        private set
    lateinit var attendanceScheduler: AttendanceScheduler
        private set
    lateinit var notifier: AttendanceNotifier
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        api = QingxinApiService()
        credentialStore = SecureCredentialStore(this)
        authRepository = AuthRepository(api, credentialStore)
        courseRepository = CourseRepository(this, api, authRepository)
        qrTimeline = QrTimelineManager(api)
        attendanceRepository = AttendanceRepository(api, authRepository, courseRepository, qrTimeline)
        attendanceScheduler = AttendanceScheduler(this)
        notifier = AttendanceNotifier(this)
        if (attendanceScheduler.getSettings().autoSignEnabled) {
            attendanceScheduler.start()
        }
    }

    companion object {
        lateinit var instance: QingxinApp
            private set
    }
}
