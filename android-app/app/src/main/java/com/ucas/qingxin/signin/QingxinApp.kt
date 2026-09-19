package com.ucas.qingxin.signin

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import com.ucas.qingxin.signin.attendance.AttendanceNotifier
import com.ucas.qingxin.signin.attendance.AttendanceRepository
import com.ucas.qingxin.signin.attendance.AttendanceScheduler
import com.ucas.qingxin.signin.attendance.AutoSignExclusionStore
import com.ucas.qingxin.signin.attendance.LectureNoticeWatcher
import com.ucas.qingxin.signin.auth.AuthRepository
import com.ucas.qingxin.signin.auth.SecureCredentialStore
import com.ucas.qingxin.signin.course.CourseRepository
import com.ucas.qingxin.signin.lecture.LectureNoticeService
import com.ucas.qingxin.signin.lecture.LectureRepository
import com.ucas.qingxin.signin.lecture.LectureScheduleStore
import com.ucas.qingxin.signin.network.QingxinApiService
import com.ucas.qingxin.signin.qr.QrTimelineManager
import com.ucas.qingxin.signin.update.UpdateCheckStore
import com.ucas.qingxin.signin.update.UpdateService
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
    lateinit var lectureRepository: LectureRepository
        private set
    lateinit var qrTimeline: QrTimelineManager
        private set
    lateinit var attendanceRepository: AttendanceRepository
        private set
    lateinit var attendanceScheduler: AttendanceScheduler
        private set
    /**
     * 自动签到的课程排除表（当天临时 + 长期黑名单）。
     *
     * 挂在容器上而不是塞进 `AttendanceScheduler`：它是**独立的一份用户数据**，
     * 与「开关 / 保活状态」没有关系；混进 `user_settings` 会让一次读错
     * 直接改变自动签到的行为。
     */
    lateinit var autoSignExclusionStore: AutoSignExclusionStore
        private set
    /**
     * 讲座时间表（预约系统）最后一次读取的结果。
     *
     * 与 [autoSignExclusionStore] 同样挂在容器上：它是独立的一份用户数据。
     * 与讲座通知不同的地方在于**它必须落盘** —— 那份数据只能靠用户手工登录后读取，
     * 掉线时应用无法自己恢复，不留盘就等于把用户上次看到的内容也一起丢掉
     * （见 [com.ucas.qingxin.signin.lecture.LectureScheduleStore] 的说明）。
     */
    lateinit var lectureScheduleStore: LectureScheduleStore
        private set
    /**
     * 更新检查（读本仓库的 GitHub Releases）与其本地状态。
     *
     * 与 [lectureScheduleStore] 一样挂在容器上：它是独立的一份用户数据，
     * 与签到 / 讲座都没有依赖关系。
     */
    lateinit var updateService: UpdateService
        private set
    lateinit var updateCheckStore: UpdateCheckStore
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
            // 讲座相关周期任务（新讲座消息 + 找下一场讲座的哨兵）登记是幂等的：
            // 这里无条件调一次，既能覆盖「开关是上一版留下的、任务还没登记过」，
            // 也能自愈被系统清掉的任务；两个开关都关时它会自行撤销任务。
            runCatching { LectureNoticeWatcher.syncPeriodicWork(this) }
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
        // 讲座通知只依赖公开的人文学院网站数据源，不需要登录态，也不参与签到。
        // 构造不需要 Context：列表**不做本地缓存**，每次刷新都直接抓网页。
        lectureRepository = LectureRepository(LectureNoticeService())
        qrTimeline = QrTimelineManager(api)
        attendanceRepository = AttendanceRepository(api, authRepository, courseRepository, qrTimeline)
        attendanceScheduler = AttendanceScheduler(this)
        autoSignExclusionStore = AutoSignExclusionStore(this)
        // 讲座时间表（预约系统）的本机存档；只存讲座内容，不存任何登录凭据。
        lectureScheduleStore = LectureScheduleStore(this)
        // 更新检查：只读 GitHub 公开 Releases，不带身份、不写任何远端内容。
        updateService = UpdateService()
        updateCheckStore = UpdateCheckStore(this)
        notifier = AttendanceNotifier(this)
    }

    companion object {
        private const val TAG = "QingxinApp"

        lateinit var instance: QingxinApp
            private set
    }
}
