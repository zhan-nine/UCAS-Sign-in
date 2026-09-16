package com.ucas.qingxin.signin

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import com.ucas.qingxin.signin.attendance.AttendanceNotifier
import com.ucas.qingxin.signin.attendance.AttendanceRepository
import com.ucas.qingxin.signin.attendance.AttendanceScheduler
import com.ucas.qingxin.signin.auth.AuthRepository
import com.ucas.qingxin.signin.auth.SecureCredentialStore
import com.ucas.qingxin.signin.course.CourseRepository
import com.ucas.qingxin.signin.network.QingxinApiService
import com.ucas.qingxin.signin.qr.QrTimelineManager
import com.ucas.qingxin.signin.widget.WidgetRefreshScheduler
import com.ucas.qingxin.signin.widget.WidgetTicker
import com.ucas.qingxin.signin.widget.WidgetUpdater

/**
 * 依赖容器。
 *
 * 关键约束：`Application.onCreate()` **绝不允许抛异常**。
 * 桌面小部件的更新会由系统宿主（启动器 / 负一屏）冷启动本进程，
 * 一旦这里崩溃，用户看到的就是「无法加载小部件」，而且无法从应用内恢复。
 * 因此所有依赖初始化都包在 `runCatching` 中，并用 [isReady] 对外暴露可用性。
 */
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

    /** 依赖是否初始化成功；小部件等外部入口必须先判断该值。 */
    var isReady: Boolean = false
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        try {
            buildDependencies()
            isReady = true
        } catch (t: Throwable) {
            isReady = false
            Log.e(TAG, "依赖初始化失败，小部件将退化为只读/请登录状态", t)
        }
        if (isReady) {
            runCatching {
                if (attendanceScheduler.getSettings().autoSignEnabled) {
                    attendanceScheduler.start()
                }
            }
        }
        // 进程被宿主拉起的常见原因就是小部件需要重绘：对齐刷新链路并立刻重绘一次。
        runCatching { WidgetRefreshScheduler.sync(this) }
        runCatching { WidgetUpdater.refreshLocal(this) }
        registerActivityLifecycleCallbacks(WidgetTickerLifecycle)
    }

    /**
     * 前后台切换：前台时让进程内定时器持续运行（小部件显示最跟手），
     * 退到后台后保留一小段宽限再停，之后交给边界闹钟与 WorkManager。
     */
    private object WidgetTickerLifecycle : ActivityLifecycleCallbacks {
        private var started = 0

        override fun onActivityStarted(activity: Activity) {
            started++
            runCatching { WidgetTicker.onAppForeground(activity.applicationContext) }
        }

        override fun onActivityStopped(activity: Activity) {
            started = (started - 1).coerceAtLeast(0)
            if (started == 0) {
                runCatching { WidgetTicker.onAppBackground(activity.applicationContext) }
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    private fun buildDependencies() {
        api = QingxinApiService()
        credentialStore = SecureCredentialStore(this)
        authRepository = AuthRepository(api, credentialStore)
        courseRepository = CourseRepository(this, api, authRepository)
        qrTimeline = QrTimelineManager(api)
        attendanceRepository = AttendanceRepository(api, authRepository, courseRepository, qrTimeline)
        attendanceScheduler = AttendanceScheduler(this)
        notifier = AttendanceNotifier(this)
    }

    companion object {
        private const val TAG = "QingxinApp"

        lateinit var instance: QingxinApp
            private set
    }
}
