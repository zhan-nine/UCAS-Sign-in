package com.ucas.qingxin.signin.attendance

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ucas.qingxin.signin.QingxinApp
import com.ucas.qingxin.signin.data.SignOutcome
import com.ucas.qingxin.signin.data.UserSettings
import com.ucas.qingxin.signin.network.ApiException
import com.ucas.qingxin.signin.widget.TodayCourseWidgetReceiver
import java.util.concurrent.TimeUnit

class AttendanceScheduler(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("user_settings", Context.MODE_PRIVATE)

    fun getSettings(): UserSettings {
        return UserSettings(
            autoSignEnabled = prefs.getBoolean(KEY_AUTO, false),
            notifyEnabled = prefs.getBoolean(KEY_NOTIFY, true),
        )
    }

    fun saveSettings(settings: UserSettings) {
        prefs.edit()
            .putBoolean(KEY_AUTO, settings.autoSignEnabled)
            .putBoolean(KEY_NOTIFY, settings.notifyEnabled)
            .apply()
        if (settings.autoSignEnabled) start() else stop()
    }

    fun start() {
        val req = PeriodicWorkRequestBuilder<AutoSignWorker>(15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            req,
        )
        AttendanceForegroundService.start(appContext)
    }

    fun stop() {
        WorkManager.getInstance(appContext).cancelUniqueWork(WORK_NAME)
        AttendanceForegroundService.stop(appContext)
    }

    companion object {
        private const val KEY_AUTO = "auto_sign"
        private const val KEY_NOTIFY = "notify"
        const val WORK_NAME = "auto_sign_work"
    }
}

class AutoSignWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? QingxinApp ?: return Result.success()
        if (!app.isReady) return Result.success()
        val settings = app.attendanceScheduler.getSettings()
        if (!settings.autoSignEnabled) return Result.success()
        if (!app.authRepository.isLoggedIn()) {
            if (settings.notifyEnabled) app.notifier.notifyLoginExpired()
            return Result.success()
        }
        return try {
            val query = app.courseRepository.loadToday()
            val schoolNow = app.qrTimeline.currentSchoolTimeOrNull()
                ?: app.qrTimeline.syncClock().schoolNowMs
            val (current, _) = app.courseRepository.currentAndNext(query.courses, schoolNow)
            val target = current
                ?: app.courseRepository.currentAndNext(query.courses, System.currentTimeMillis()).first
            if (target == null || target.signed) return Result.success()
            if (settings.notifyEnabled) app.notifier.notifyReady(target.name)
            val result = app.attendanceRepository.signOneClick(target)
            when (result.outcome) {
                SignOutcome.SIGNED -> {
                    if (settings.notifyEnabled) app.notifier.notifySuccess(target.name)
                    app.courseRepository.loadToday()
                    TodayCourseWidgetReceiver.requestUpdate(applicationContext)
                }
                else -> {
                    if (settings.notifyEnabled) app.notifier.notifyFailure(result.message)
                }
            }
            Result.success()
        } catch (e: ApiException) {
            if (e.code == "LOGIN_EXPIRED" || e.code == "LOGIN_REJECTED") {
                if (settings.notifyEnabled) app.notifier.notifyLoginExpired()
                app.attendanceScheduler.stop()
            } else if (settings.notifyEnabled) {
                app.notifier.notifyFailure(e.message ?: e.code)
            }
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
