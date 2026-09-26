package com.ucas.qingxin.signin.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ucas.qingxin.signin.QingxinApp
import com.ucas.qingxin.signin.BuildConfig
import com.ucas.qingxin.signin.attendance.AutoSignExclusions
import com.ucas.qingxin.signin.attendance.AutoSignRandomizer
import com.ucas.qingxin.signin.data.AttendanceUiStatus
import com.ucas.qingxin.signin.data.Course
import com.ucas.qingxin.signin.data.KeepAliveState
import com.ucas.qingxin.signin.data.QrSnapshot
import com.ucas.qingxin.signin.data.SignOutcome
import com.ucas.qingxin.signin.data.UserSettings
import com.ucas.qingxin.signin.network.ApiException
import com.ucas.qingxin.signin.network.QingxinApiService
import com.ucas.qingxin.signin.update.UpdateRelease
import com.ucas.qingxin.signin.update.UpdateSummary
import com.ucas.qingxin.signin.update.VersionTags
import com.ucas.qingxin.signin.widget.TodayCourseWidgetReceiver
import com.ucas.qingxin.signin.widget.WidgetRefreshScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

data class AppUiState(
    val restoring: Boolean = true,
    val authenticated: Boolean = false,
    val busy: Boolean = false,
    val signing: Boolean = false,
    val studentNoInput: String = "",
    val passwordInput: String = "",
    val studentNo: String = "",
    val courses: List<Course> = emptyList(),
    val current: Course? = null,
    val next: Course? = null,
    val selected: Course? = null,
    /** 是否为用户手动点选的二维码课程（false=跟随当前课默认） */
    val usingManualQr: Boolean = false,
    val status: AttendanceUiStatus = AttendanceUiStatus.NOT_STARTED,
    val message: String = "",
    val error: String = "",
    val fromCache: Boolean = false,
    val qr: QrSnapshot? = null,
    val qrRemaining: Double = 0.0,
    val qrProgress: Float = 0f,
    val schoolNowLabel: String = "",
    val settings: UserSettings = UserSettings(),
    /** 保活 / 省电相关实时状态（设置页展示与告警）。 */
    val keepAlive: KeepAliveState = KeepAliveState(),
    /**
     * 自动打卡排除名单：**今天临时跳过**的课程键（[AutoSignExclusions.courseKey]）。
     *
     * 只存键而不是 `Course` 对象：界面要判定的只是「这一行是否被排除」，
     * 存对象会让同一份课表数据在两个地方各自持有、各自过期。
     */
    val excludedToday: Set<String> = emptySet(),
    /** 自动打卡排除名单：**长期不打卡**的课程键。 */
    val excludedPermanent: Set<String> = emptySet(),
    /** 版本检查与更新提示。 */
    val update: UpdateUiState = UpdateUiState(),
    /**
     * 二维码为何处于「不自动取码」的状态。
     *
     * 1.2.1 起二维码只在**签到窗口内**取（见 `QrRefreshPolicy`），窗口之外界面上
     * 不再挂着一张过期二维码，而是明确告诉用户「什么时候会有码」。没有这个字段，
     * 界面只能在「正在同步…」的转圈里干等，看起来像卡住了。
     */
    val qrStandby: QrStandbyReason = QrStandbyReason.NONE,
)

/** 二维码的待机原因，决定卡片上的文案。 */
enum class QrStandbyReason {
    /** 正在正常取码（或今天已签完但保留最后一张码）。 */
    NONE,

    /** 未到签到窗口：进入窗口后会自动开始刷新。 */
    WAITING_WINDOW,

    /** 今日课程已全部签到完成：不再取新码，冻结显示最后一张供核对。 */
    ALL_DONE,
}

/**
 * 「检查更新」的界面状态。
 *
 * ## 为什么 `available` 与 `ignoredTag` 分开存
 * 用户点「不再提示」只是**不展示**，而不是「假装没有新版本」：
 * 设置页仍要能告诉用户「检测到 vX，你已选择不再提示」，并且提供恢复入口。
 * 把两者合并成一个可空字段就表达不了这个中间状态。
 */
data class UpdateUiState(
    val currentVersion: String = "",
    val currentCode: Int = 0,
    /** 「自动检查更新」开关。 */
    val autoCheck: Boolean = true,
    val checking: Boolean = false,
    /** 检测到的、版本号高于当前版本的 Release；无更新时为 `null`。 */
    val available: UpdateRelease? = null,
    /**
     * 最近一次成功检查发现的**最高版本**，无论它是否高于当前版本；从未成功检查过为 `null`。
     *
     * 与 [available] 的分工是「远端最新是什么」vs「有没有新版本」。单列一个字段是为了
     * **让检查结果始终可见**：用户把应用升到最新之后，[available] 必然是 `null`，
     * 设置页就只剩一句「已是最新」这类无从核对的结论，看起来就像这个功能没实现；
     * 有了它就能常显「最新版本 1.2.1（已是最新）」与检查时间。
     */
    val latest: UpdateRelease? = null,
    /** 用户点过「不再提示」的版本 tag；未忽略时为空串。 */
    val ignoredTag: String = "",
    /** 上次成功检查的时刻（本地毫秒）；从未检查为 0。 */
    val lastCheckedAtMs: Long = 0L,
    /** 手动检查的结果文案（已是最新 / 检查中）。 */
    val status: String = "",
    /** 检查失败的文案。只在设置页展示：主页横幅不该因为一次网络故障而弹错。 */
    val error: String = "",
    /** 本次会话里用户点过「稍后」；不落盘，重启后重新提示。 */
    val dismissed: Boolean = false,
) {
    /** 主页是否展示更新横幅。 */
    val showBanner: Boolean
        get() = !dismissed && available != null && available.tag != ignoredTag
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as QingxinApp
    private val _ui = MutableStateFlow(
        if (app.isReady) {
            AppUiState(
                settings = app.attendanceScheduler.getSettings(),
                keepAlive = app.attendanceScheduler.readKeepAliveState(),
                excludedToday = readExclusions().first,
                excludedPermanent = readExclusions().second,
                update = readUpdateState(),
            )
        } else {
            AppUiState(
                restoring = false,
                error = "应用初始化失败（可能是系统密钥库异常），请重启应用后再试",
                update = readUpdateState(),
            )
        },
    )
    val ui: StateFlow<AppUiState> = _ui.asStateFlow()
    private var qrRefreshJob: Job? = null
    private var qrTickJob: Job? = null

    /**
     * 应用是否在前台。
     *
     * 由 `MainActivity` 的 `LifecycleResumeEffect` 维护。**这是 1.2.1 耗电治理的
     * 开关**：1.2.0 的两条循环（倒计时 ticker 与二维码轮询）从 Activity 创建起
     * 一直跑到销毁，与前后台无关 —— 锁屏后它们照样满速运行，单是 ticker 就
     * 86.4 万次/天的主线程唤醒。
     */
    @Volatile
    private var uiResumed = false

    /**
     * 主页是否可见。
     *
     * 二维码只在主页渲染，因此切到设置 / 讲座页时没有理由继续取码 ——
     * 这是 `QrRefreshPolicy` 里 `visible` 的来源。
     */
    @Volatile
    private var homeVisible = false

    /**
     * 用户手动点选的课程 id。
     * null = 使用「当前课」作为默认二维码课程（会随时间窗口变化）；
     * 非 null = 固定展示该课二维码，绝不被自动逻辑覆盖。
     */
    private var manualQrCourseId: String? = null

    init {
        if (app.isReady) {
            viewModelScope.launch {
                val session = app.authRepository.restore()
                if (session != null) {
                    _ui.update {
                        it.copy(
                            restoring = false,
                            authenticated = true,
                            studentNo = session.studentNo,
                            studentNoInput = app.credentialStore.getLoginId().orEmpty()
                                .ifBlank { session.studentNo },
                        )
                    }
                    refreshCourses()
                    startQrTicker()
                    startQrLoopIfNeeded()
                } else {
                    _ui.update {
                        it.copy(
                            restoring = false,
                            authenticated = false,
                            studentNoInput = app.credentialStore.getLoginId().orEmpty(),
                        )
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ 前台可见性

    /**
     * 应用回到前台：启动倒计时 ticker，并在主页可见时按策略恢复取码。
     *
     * 刻意**不再**在这里无条件取一次码：窗口之外取回来的码扫了也没用
     * （见 [QrRefreshPolicy]），界面改用 [AppUiState.qrStandby] 明确告知何时会有码，
     * 并提供「立即获取」按钮让用户随时手动取（例如老师提前开了签到）。
     */
    fun onUiResumed() {
        uiResumed = true
        startQrTicker()
        if (homeVisible) startQrLoopIfNeeded(force = true)
    }

    /** 应用退到后台：停掉两条循环。这是省电的关键一步。 */
    fun onUiPaused() {
        uiResumed = false
        qrTickJob?.cancel()
        qrRefreshJob?.cancel()
    }

    /** 当前页面变化：只有主页需要二维码。 */
    fun onScreenChanged(home: Boolean) {
        if (homeVisible == home) return
        homeVisible = home
        if (!uiResumed || !_ui.value.authenticated) return
        if (home) startQrLoopIfNeeded(force = true) else qrRefreshJob?.cancel()
    }

    fun onStudentNoChange(v: String) = _ui.update { it.copy(studentNoInput = v) }
    fun onPasswordChange(v: String) = _ui.update { it.copy(passwordInput = v) }

    fun login() {
        val studentNo = _ui.value.studentNoInput
        val password = _ui.value.passwordInput
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, error = "", message = "正在验证学校账号…") }
            try {
                val session = app.authRepository.login(studentNo, password)
                manualQrCourseId = null
                _ui.update {
                    it.copy(
                        busy = false,
                        authenticated = true,
                        studentNo = session.studentNo,
                        passwordInput = "",
                        message = "登录成功",
                    )
                }
                refreshCourses()
                startQrTicker()
                startQrLoopIfNeeded()
                TodayCourseWidgetReceiver.requestUpdate(getApplication())
            } catch (e: Exception) {
                _ui.update {
                    it.copy(busy = false, error = e.message ?: "登录失败", message = "")
                }
            }
        }
    }

    fun logout() {
        qrRefreshJob?.cancel()
        qrTickJob?.cancel()
        manualQrCourseId = null
        val studentNo = _ui.value.studentNo
        app.attendanceScheduler.stop()
        app.qrTimeline.clear()
        app.courseRepository.clearAccountCache(studentNo)
        // 排除名单是按「谁的课表」选出来的：换账号后同一门课未必还在，
        // 留着它只会在新账号上静默漏签。
        runCatching { app.autoSignExclusionStore.clearAll() }
        app.authRepository.logout()
        // 小部件快照是明文缓存，退出登录时必须清空，避免下一位使用者看到前一账号的课表。
        com.ucas.qingxin.signin.widget.WidgetSnapshotStore.of(getApplication()).clearAll()
        _ui.value = AppUiState(
            restoring = false,
            authenticated = false,
            settings = app.attendanceScheduler.getSettings(),
            keepAlive = app.attendanceScheduler.readKeepAliveState(),
            update = readUpdateState(),
        )
        TodayCourseWidgetReceiver.requestUpdate(getApplication())
    }

    fun refreshCourses() {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, error = "", message = "正在更新课程…") }
            try {
                val result = app.courseRepository.loadToday()
                // 记录抓取时间，避免小部件定时器紧接着又抓一次同样的数据。
                com.ucas.qingxin.signin.widget.WidgetRefreshScheduler.markDataFetched(getApplication())
                val displayNow = System.currentTimeMillis()
                val schoolNow = app.qrTimeline.currentSchoolTimeOrNull()
                    ?: runCatching { app.qrTimeline.syncClock().schoolNowMs }.getOrNull()
                val (current, next) = app.courseRepository.currentAndNext(result.courses, displayNow)
                // 手动选择若已不在列表中，清空，回到默认当前课
                if (manualQrCourseId != null &&
                    result.courses.none { it.id == manualQrCourseId }
                ) {
                    manualQrCourseId = null
                }
                val selected = resolveQrCourse(result.courses, current, next)
                _ui.update {
                    it.copy(
                        busy = false,
                        courses = result.courses,
                        current = current,
                        next = next,
                        selected = selected,
                        usingManualQr = manualQrCourseId != null,
                        fromCache = result.fromCache,
                        message = result.message,
                        status = app.attendanceRepository.uiStatus(selected, schoolNow ?: displayNow),
                        keepAlive = app.attendanceScheduler.readKeepAliveState(),
                    )
                }
                startQrLoopIfNeeded(force = true)
                TodayCourseWidgetReceiver.requestUpdate(getApplication())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(busy = false, error = e.message ?: "课程加载失败") }
            }
        }
    }

    /** 手动选择课程生成二维码（不会被时间自动逻辑覆盖）。 */
    fun selectCourse(course: Course) {
        manualQrCourseId = course.id
        _ui.update {
            it.copy(
                selected = course,
                usingManualQr = true,
                error = "",
                message = "已切换到「${course.name}」",
                status = app.attendanceRepository.uiStatus(
                    course,
                    app.qrTimeline.currentSchoolTimeOrNull() ?: System.currentTimeMillis(),
                ),
            )
        }
        startQrLoopIfNeeded(force = true)
    }

    /** 清除手动选择，二维码回到「当前课」默认。 */
    fun useDefaultQrCourse() {
        manualQrCourseId = null
        val now = System.currentTimeMillis()
        val (current, next) = app.courseRepository.currentAndNext(_ui.value.courses, now)
        val selected = resolveQrCourse(_ui.value.courses, current, next)
        _ui.update {
            it.copy(
                current = current,
                next = next,
                selected = selected,
                usingManualQr = false,
                message = "已使用当前课作为默认二维码",
                status = app.attendanceRepository.uiStatus(
                    selected,
                    app.qrTimeline.currentSchoolTimeOrNull() ?: now,
                ),
            )
        }
        startQrLoopIfNeeded(force = true)
    }

    fun canSignNow(): Boolean {
        val course = _ui.value.selected ?: _ui.value.current
        return !_ui.value.signing && course != null && !course.signed
    }

    fun signSelected() {
        val course = _ui.value.selected ?: _ui.value.current ?: return
        if (_ui.value.signing || course.signed) return
        viewModelScope.launch {
            _ui.update { it.copy(signing = true, status = AttendanceUiStatus.SIGNING, error = "") }
            try {
                val result = app.attendanceRepository.signOneClick(course)
                when (result.outcome) {
                    SignOutcome.SIGNED -> {
                        if (_ui.value.settings.notifyEnabled) {
                            app.notifier.notifySuccess(course.name)
                        }
                        _ui.update {
                            it.copy(
                                signing = false,
                                status = AttendanceUiStatus.SIGNED,
                                message = result.message,
                                error = "",
                            )
                        }
                        refreshCourses()
                    }
                    SignOutcome.QR_EXPIRED -> {
                        _ui.update {
                            it.copy(
                                signing = false,
                                status = AttendanceUiStatus.QR_EXPIRED,
                                error = result.message,
                            )
                        }
                        startQrLoopIfNeeded(force = true)
                    }
                    else -> {
                        if (_ui.value.settings.notifyEnabled) {
                            app.notifier.notifyFailure(result.message)
                        }
                        _ui.update {
                            it.copy(
                                signing = false,
                                status = AttendanceUiStatus.FAILED,
                                error = result.message,
                            )
                        }
                    }
                }
                TodayCourseWidgetReceiver.requestUpdate(getApplication())
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                val loginExpired = e.code == "LOGIN_EXPIRED" || e.code == "LOGIN_REJECTED"
                if (loginExpired && _ui.value.settings.notifyEnabled) {
                    app.notifier.notifyLoginExpired()
                }
                _ui.update {
                    it.copy(
                        signing = false,
                        authenticated = if (loginExpired) false else it.authenticated,
                        status = if (loginExpired) AttendanceUiStatus.LOGIN_EXPIRED else AttendanceUiStatus.FAILED,
                        error = e.message ?: e.code,
                    )
                }
            } catch (e: Exception) {
                _ui.update {
                    it.copy(
                        signing = false,
                        status = AttendanceUiStatus.FAILED,
                        error = e.message ?: "签到失败",
                    )
                }
            }
        }
    }

    fun updateSettings(
        autoSign: Boolean? = null,
        notify: Boolean? = null,
        lowPower: Boolean? = null,
    ) {
        val cur = _ui.value.settings
        val next = cur.copy(
            autoSignEnabled = autoSign ?: cur.autoSignEnabled,
            notifyEnabled = notify ?: cur.notifyEnabled,
            lowPowerMode = lowPower ?: cur.lowPowerMode,
        )
        val profileChanged = next.lowPowerMode != cur.lowPowerMode
        // saveSettings 内部会按需 start()/stop()，其中已包含 syncDaemon()。
        app.attendanceScheduler.saveSettings(next)
        _ui.update { it.copy(settings = next, keepAlive = app.attendanceScheduler.readKeepAliveState()) }
        if (profileChanged) rescheduleForProfile()
        if (next.autoSignEnabled) {
            // 常驻通知的文案要立刻跟上开关变化（读本地缓存，放到 IO 线程避免主线程 I/O）。
            val courses = _ui.value.courses.ifEmpty { null }
            viewModelScope.launch(Dispatchers.IO) {
                com.ucas.qingxin.signin.attendance.AutoSignEngine
                    .refreshDaemonStatus(getApplication(), app, courses)
            }
        }
        TodayCourseWidgetReceiver.requestUpdate(getApplication())
    }

    /**
     * 切换耗电档位后的显式重排。
     *
     * 三件事都**必须**做，因为它们用的都是「已排过就不重排」的语义：
     * 小部件的周期任务用 `UPDATE`（会替换间隔，但不主动调用就不会重排）、
     * 讲座巡检用 `KEEP`（**根本改不动**已排好的间隔）、
     * 小部件进程内 ticker 的宽限时长随档位变化（省电档是「不使用」）。
     * 不重排的话，用户切到省电模式后小部件与讲座巡检仍按普通档的节奏唤醒。
     */
    private fun rescheduleForProfile() {
        val context = getApplication<Application>()
        runCatching { WidgetRefreshScheduler.reschedule(context) }
        runCatching {
            com.ucas.qingxin.signin.attendance.LectureNoticeWatcher.reschedulePeriodicWork(context)
        }
        runCatching { app.attendanceScheduler.syncDaemon() }
    }

    /**
     * 刷新保活状态；并借「应用已在前台」这个时机重排一次调度。
     *
     * 1.2.0 在这里无条件 `start()`（＝拉起守护服务）来兜底「冷启动时前台服务被拒」的情形。
     * 1.2.1 起不再这样做了：守护服务只在临近签到窗口时才该存在
     * （见 [com.ucas.qingxin.signin.attendance.AttendanceScheduler.syncDaemon]），
     * 每次开 App 都把它拉起来会让进程整天不被系统冻结 —— 那正是耗电报告的根因。
     * 现在这里只做「重算闹钟 + 让守护服务回到它该有的状态」。
     */
    fun refreshKeepAlive() {
        if (!app.isReady) return
        // 放到 IO 线程：这里会读本地缓存与系统状态，不应阻塞 onResume 的渲染。
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { app.attendanceScheduler.ensureSchedule() }
            runCatching { app.attendanceScheduler.syncDaemon() }
            val state = app.attendanceScheduler.readKeepAliveState()
            _ui.update { it.copy(keepAlive = state) }
        }
    }

    fun setLockConfirmed(confirmed: Boolean) {
        app.attendanceScheduler.setLockConfirmed(confirmed)
        _ui.update { it.copy(keepAlive = app.attendanceScheduler.readKeepAliveState()) }
    }

    // ------------------------------------------------------------------ 自动打卡排除

    /**
     * 把一门课加入 / 移出「今天不自动打卡」名单。
     *
     * 落盘后立刻重排闹钟：被排除的课可能正是刚刚排好的下一次唤醒，
     * 不重排就会在那节课上白白醒一次（并且真的签上去）。
     */
    fun setExcludeToday(course: Course, excluded: Boolean) {
        if (!app.isReady) return
        val today = AutoSignRandomizer.todayKey()
        val key = AutoSignExclusions.courseKey(course)
        runCatching { app.autoSignExclusionStore.setExcludedToday(key, today, excluded) }
        _ui.update { it.copy(excludedToday = readExclusions().first) }
        afterExclusionsChanged()
    }

    /** 把一门课加入 / 移出「长期不自动打卡」名单。 */
    fun setExcludePermanent(course: Course, excluded: Boolean) {
        if (!app.isReady) return
        val key = AutoSignExclusions.courseKey(course)
        runCatching { app.autoSignExclusionStore.setPermanent(key, excluded) }
        _ui.update { it.copy(excludedPermanent = readExclusions().second) }
        afterExclusionsChanged()
    }

    /**
     * 排除名单变化后的收尾：重排闹钟 + 刷新常驻通知文案。
     *
     * 刻意**不**走 `saveSettings`（`AttendanceScheduler.kt` 的 `saveSettings` 会重启
     * 守护服务并清空运行态）：排除一门课不该把整条签到链路推倒重来。
     * 静态入口 `ensureSchedule` 只做「重算下一次唤醒」，正是这里需要的语义。
     */
    private fun afterExclusionsChanged() {
        val context = getApplication<Application>()
        runCatching { com.ucas.qingxin.signin.attendance.AttendanceScheduler.ensureSchedule(context) }
        if (!_ui.value.settings.autoSignEnabled) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                com.ucas.qingxin.signin.attendance.AutoSignEngine
                    .refreshDaemonStatus(context, app, null)
            }
        }
    }

    // ------------------------------------------------------------------ 版本检查与更新

    /**
     * 进入主页时调用：按需自动检查一次更新。
     *
     * ## 为什么是「进主页」而不是「开应用」
     * 主页是用户每次打开应用都会看到的地方，也是横幅唯一可能出现的位置；
     * 挂在别处（例如 `Application.onCreate`）会在后台被小部件宿主冷启动时
     * 也发一次无意义的网络请求。
     *
     * ## 节流
     * 24 小时内最多检查一次（间隔来自耗电档位，见 `PowerProfile.updateCheckIntervalMs`）。
     * 节流是必须的：主页在每次从子页面返回时都会重组，
     * 没有节流就会变成「点一次返回就查一次 GitHub」。
     * 首次启动（[UpdateUiState.lastCheckedAtMs] 为 0）不节流，保证装完就有结论。
     */
    fun onEnterHome() {
        val update = _ui.value.update
        if (!update.autoCheck) return
        val interval = if (app.isReady) {
            app.powerProfile().updateCheckIntervalMs
        } else {
            AUTO_CHECK_INTERVAL_FALLBACK_MS
        }
        val now = System.currentTimeMillis()
        if (update.lastCheckedAtMs > 0 && now - update.lastCheckedAtMs < interval) return
        checkForUpdate(auto = true)
    }

    /**
     * 检查更新。
     *
     * @param auto 自动检查（进主页触发）还是用户手动点了「检查更新」。
     *   两者的差别只有一处：手动检查会**重新展示**被「稍后」关掉的横幅 ——
     *   用户主动来问，就该把答案摆出来。
     */
    fun checkForUpdate(auto: Boolean = false) {
        if (!app.isReady) return
        if (_ui.value.update.checking) return
        _ui.update {
            it.copy(update = it.update.copy(checking = true, status = "正在检查更新…", error = ""))
        }
        viewModelScope.launch {
            try {
                val latest = app.updateService.fetchLatest()
                val newer = latest?.takeIf { isNewerThanCurrent(it) }
                val now = System.currentTimeMillis()
                // 只有**成功**（哪怕是「已是最新」）才记检查时间与结论：
                // 失败时若也记，等于把一次断网当成「刚查过且没有新版」，
                // 会让用户手里的新版本提示凭空消失一整个节流周期。
                app.updateCheckStore.markChecked(now)
                // 存「远端最高版本」而不是「比当前新的那一版」：后者在追平版本后
                // 会把结论清成空，设置页随之变回一片空白（见 UpdateCheckStore.cachedLatest）。
                app.updateCheckStore.saveLatest(latest)
                _ui.update { state ->
                    state.copy(
                        update = state.update.copy(
                            checking = false,
                            latest = latest,
                            available = newer,
                            ignoredTag = app.updateCheckStore.ignoredTag(),
                            lastCheckedAtMs = now,
                            status = UpdateSummary.statusLine(latest, BuildConfig.VERSION_NAME),
                            error = "",
                            dismissed = if (auto) state.update.dismissed else false,
                        ),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update {
                    it.copy(
                        update = it.update.copy(
                            checking = false,
                            status = "",
                            error = e.message ?: "检查更新失败",
                        ),
                    )
                }
            }
        }
    }

    /** 「稍后」：本次会话不再展示横幅，下次启动仍会提示。 */
    fun dismissUpdate() {
        _ui.update { it.copy(update = it.update.copy(dismissed = true)) }
    }

    /**
     * 「不再提示」：忽略**当前检测到的这一个版本**。
     *
     * 只记 tag：将来出现更高的版本仍会提示 —— 用户想摆脱的是一次具体的打扰，
     * 而不是从此不再知道有新版本。彻底关掉由 [setAutoCheckUpdate] 负责。
     */
    fun ignoreUpdateVersion() {
        val tag = _ui.value.update.available?.tag.orEmpty()
        if (tag.isBlank()) return
        if (app.isReady) app.updateCheckStore.setIgnoredTag(tag)
        _ui.update { it.copy(update = it.update.copy(ignoredTag = tag)) }
    }

    /** 设置页的「恢复提示」：清掉已忽略的版本。 */
    fun clearIgnoredVersion() {
        if (app.isReady) app.updateCheckStore.clearIgnoredTag()
        _ui.update { it.copy(update = it.update.copy(ignoredTag = "", dismissed = false)) }
    }

    /** 「自动检查更新」总开关。 */
    fun setAutoCheckUpdate(enabled: Boolean) {
        if (app.isReady) app.updateCheckStore.setAutoCheckEnabled(enabled)
        _ui.update { it.copy(update = it.update.copy(autoCheck = enabled)) }
    }

    /**
     * 读取更新相关的初始状态。
     *
     * 值全部来自本地，**不发网络请求**：构造 ViewModel 时做 I/O 会让冷启动变慢，
     * 而且 `Application` 可能根本没初始化成功（见 [QingxinApp.isReady]）。
     */
    private fun readUpdateState(): UpdateUiState {
        val version = BuildConfig.VERSION_NAME
        val code = BuildConfig.VERSION_CODE
        val fallback = UpdateUiState(currentVersion = version, currentCode = code)
        if (!app.isReady) return fallback
        return runCatching {
            val store = app.updateCheckStore
            // 缓存里那条若已经不比当前版本新（用户自己装了新版），就不再算「有更新」，
            // 否则换包之后主页横幅还会挂着上一个版本；但它仍然作为「最新版本」显示，
            // 让设置页有话可说。
            val cached = store.cachedLatest()
            fallback.copy(
                autoCheck = store.isAutoCheckEnabled(),
                available = cached?.takeIf { isNewerThanCurrent(it) },
                latest = cached,
                ignoredTag = store.ignoredTag(),
                lastCheckedAtMs = store.lastCheckedAtMs(),
            )
        }.getOrDefault(fallback)
    }

    /** 远端版本是否高于当前安装的版本。当前版本解析不出时保守认为「是」。 */
    private fun isNewerThanCurrent(release: UpdateRelease): Boolean {
        val current = VersionTags.parse(BuildConfig.VERSION_NAME) ?: return true
        return release.version > current
    }

    /** 读取排除名单：`(今天临时, 长期)`；应用未就绪或读取失败时都返回空集。 */
    private fun readExclusions(): Pair<Set<String>, Set<String>> = runCatching {
        if (!app.isReady) return@runCatching emptySet<String>() to emptySet()
        val store = app.autoSignExclusionStore
        store.permanentKeys() to store.todayKeys(AutoSignRandomizer.todayKey())
    }.getOrDefault(emptySet<String>() to emptySet())

    private fun resolveQrCourse(
        courses: List<Course>,
        current: Course?,
        next: Course?,
    ): Course? {
        val manualId = manualQrCourseId
        if (manualId != null) {
            return courses.firstOrNull { it.id == manualId } ?: current ?: next ?: courses.firstOrNull()
        }
        // 默认优先未签到：当前课 → 下一节 → 列表中第一节未签到 → 再回退任意一节（保证仍能出码）
        return current?.takeUnless { it.signed }
            ?: next?.takeUnless { it.signed }
            ?: courses.firstOrNull { !it.signed }
            ?: current
            ?: next
            ?: courses.firstOrNull()
    }

    /**
     * 倒计时心跳。
     *
     * 1.2.1 的两处改动：
     * - 间隔由固定 `delay(100)`（10 Hz）改为 [QrRefreshPolicy.tickMs]：
     *   **有二维码时 1 Hz**（倒计时环需要秒级），**没有二维码时 30 秒**
     *   （课程标签是分钟粒度，30 秒足够）。1.2.0 的 10 Hz 是 86.4 万次/天的主线程唤醒；
     * - 只在**前台且已登录**时运行。未登录时主页根本不显示二维码，空转毫无意义。
     *   `viewModelScope` 跑在 `Dispatchers.Main.immediate`，不停掉它就会一直占用主线程。
     */
    private fun startQrTicker() {
        qrTickJob?.cancel()
        if (!uiResumed || !_ui.value.authenticated) return
        qrTickJob = viewModelScope.launch {
            while (isActive) {
                if (!uiResumed) break
                val snap = app.qrTimeline.snapshot.value
                if (snap != null) {
                    val remainMs = (snap.expiresAtLocalMs - System.currentTimeMillis()).coerceAtLeast(0L)
                    val remainSec = remainMs / 1000.0
                    val progress = if (snap.validityDurationMs > 0) {
                        (remainMs.toFloat() / snap.validityDurationMs.toFloat()).coerceAtLeast(0f).coerceAtMost(1f)
                    } else {
                        0f
                    }
                    _ui.update {
                        it.copy(
                            qr = snap.copy(remainingSeconds = remainSec),
                            qrRemaining = remainSec,
                            qrProgress = progress,
                        )
                    }
                }
                refreshScheduleLabels()
                delay(QrRefreshPolicy.tickMs(snap != null))
            }
        }
    }

    /** 只更新当前/下一节展示；仅在「未手动选课」时同步默认二维码课程。 */
    private fun refreshScheduleLabels() {
        if (!_ui.value.authenticated || _ui.value.courses.isEmpty()) return
        val now = System.currentTimeMillis()
        val (current, next) = app.courseRepository.currentAndNext(_ui.value.courses, now)
        val scheduleChanged =
            current?.id != _ui.value.current?.id || next?.id != _ui.value.next?.id

        if (manualQrCourseId != null) {
            if (scheduleChanged) {
                _ui.update { it.copy(current = current, next = next) }
            }
            return
        }

        val defaultQr = resolveQrCourse(_ui.value.courses, current, next)
        val qrChanged = defaultQr?.id != _ui.value.selected?.id
        if (!scheduleChanged && !qrChanged) return
        _ui.update {
            it.copy(
                current = current,
                next = next,
                selected = defaultQr,
                usingManualQr = false,
                status = app.attendanceRepository.uiStatus(
                    defaultQr,
                    app.qrTimeline.currentSchoolTimeOrNull() ?: now,
                ),
            )
        }
        if (qrChanged) {
            startQrLoopIfNeeded(force = true)
        }
    }

    /**
     * 二维码循环：由 [QrRefreshPolicy] 驱动，而不是「固定 5 秒轮询」。
     *
     * 三条早退条件合起来覆盖了 1.2.0 的绝大部分浪费：
     * 未登录 / 应用不在前台 / 当前不在主页 —— 三种情况下都**不取码**。
     * 之后再由策略决定「现在取码」「睡到窗口开始」「今天不用取了」。
     */
    private fun startQrLoopIfNeeded(force: Boolean = false) {
        if (!isQrVisibleNow()) {
            qrRefreshJob?.cancel()
            return
        }
        if (!force && qrRefreshJob?.isActive == true) return
        qrRefreshJob?.cancel()
        qrRefreshJob = viewModelScope.launch {
            while (isActive) {
                if (!isQrVisibleNow()) break
                val displayNow = System.currentTimeMillis()
                val courses = _ui.value.courses
                val (current, next) = app.courseRepository.currentAndNext(courses, displayNow)
                if (_ui.value.current?.id != current?.id || _ui.value.next?.id != next?.id) {
                    _ui.update { it.copy(current = current, next = next) }
                }

                // 手动选择若已不在列表中，回到默认（否则会一直卡在一门不存在的课上）。
                if (manualQrCourseId != null && courses.none { it.id == manualQrCourseId }) {
                    manualQrCourseId = null
                }

                val target = resolveQrCourse(courses, current, next)
                if (target == null) {
                    // 真的没有课：清空二维码，低频回探。
                    if (_ui.value.qr != null || _ui.value.selected != null) {
                        _ui.update {
                            it.copy(
                                selected = null,
                                qr = null,
                                qrRemaining = 0.0,
                                qrProgress = 0f,
                                qrStandby = QrStandbyReason.NONE,
                            )
                        }
                    }
                    delay(QrRefreshPolicy.IDLE_TICK_MS)
                    continue
                }
                // 自动模式：同步默认选中；手动模式：绝不改成别的课
                if (manualQrCourseId == null && _ui.value.selected?.id != target.id) {
                    _ui.update {
                        it.copy(
                            selected = target,
                            usingManualQr = false,
                            status = app.attendanceRepository.uiStatus(target, displayNow),
                        )
                    }
                }
                val courseForQr = if (manualQrCourseId != null) {
                    courses.firstOrNull { it.id == manualQrCourseId } ?: target
                } else {
                    target
                }

                when (val decision = decideQrWork()) {
                    QrRefreshPolicy.Decision.Idle -> {
                        // 今天没有需要新码的课：**保留最后一张码**供核对，只是不再刷新。
                        // 1.2.0 在这里每 5 秒重取一次，是纯粹的浪费。
                        _ui.update { it.copy(qrStandby = QrStandbyReason.ALL_DONE) }
                        break
                    }
                    is QrRefreshPolicy.Decision.Wait -> {
                        _ui.update {
                            it.copy(
                                qrStandby = if (decision.ms > QrRefreshPolicy.ACTIVE_REFRESH_CAP_MS) {
                                    QrStandbyReason.WAITING_WINDOW
                                } else {
                                    QrStandbyReason.NONE
                                },
                            )
                        }
                        delay(decision.ms)
                    }
                    QrRefreshPolicy.Decision.Refresh -> {
                        _ui.update { it.copy(qrStandby = QrStandbyReason.NONE) }
                        try {
                            fetchQrInto(courseForQr, displayNow)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            _ui.update { it.copy(error = e.message ?: "QR 同步失败") }
                            delay(2_000)
                        }
                    }
                }
            }
        }
    }

    /**
     * 手动「立即获取二维码」。
     *
     * 存在的意义：万一老师提前开了签到窗口（我们算出的窗口之外），
     * 用户不该被策略锁死 —— 一次手动点击就能拿到码。这也是「省电不退让能力」
     * 这条底线在二维码上的落地。
     */
    fun refreshQrNow() {
        val course = _ui.value.selected ?: _ui.value.current ?: return
        viewModelScope.launch {
            _ui.update { it.copy(error = "", qrStandby = QrStandbyReason.NONE) }
            try {
                fetchQrInto(course, System.currentTimeMillis())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(error = e.message ?: "二维码同步失败") }
            }
            startQrLoopIfNeeded(force = true)
        }
    }

    /** 取一次二维码并写进界面状态。 */
    private suspend fun fetchQrInto(course: Course, displayNow: Long) {
        val key = course.id.ifBlank { course.uuid }
        val snap = app.qrTimeline.refreshQr(key)
        coroutineContext.ensureActive()
        val schoolNow = app.qrTimeline.currentSchoolTimeOrNull()
        val remainMs = (snap.expiresAtLocalMs - System.currentTimeMillis()).coerceAtLeast(0L)
        _ui.update {
            it.copy(
                selected = course,
                qr = snap,
                qrRemaining = remainMs / 1000.0,
                qrProgress = if (snap.validityDurationMs > 0) {
                    remainMs.toFloat() / snap.validityDurationMs.toFloat()
                } else {
                    0f
                },
                schoolNowLabel = schoolNow?.toString().orEmpty(),
                status = app.attendanceRepository.uiStatus(course, schoolNow ?: displayNow),
                error = "",
            )
        }
    }

    /** 现在是否「值得」为二维码发请求：已登录 + 应用在前台 + 停留在主页。 */
    private fun isQrVisibleNow(): Boolean =
        _ui.value.authenticated && uiResumed && homeVisible

    /** 组装策略输入。窗口与 `CourseRepository.isWithinQrLockWindow` 完全一致（开课前 25 分钟至下课）。 */
    private fun decideQrWork(): QrRefreshPolicy.Decision = QrRefreshPolicy.decide(
        nowMs = System.currentTimeMillis(),
        visible = isQrVisibleNow(),
        windows = qrWindows(),
        qrExpiresAtMs = app.qrTimeline.snapshot.value?.expiresAtLocalMs,
    )

    /**
     * 所有「还能签到」的课的签到窗口。
     *
     * 已签到的课**不在其中**：它们不需要新码（界面上仍冻结显示最后一张）。
     * 时间解析不出来的课以 `null` 起止带入，由策略按「随时可取」处理 ——
     * 不能因为一条脏数据就永远不出码。
     */
    private fun qrWindows(): List<QrRefreshPolicy.Window> {
        val courses = _ui.value.courses
        if (courses.isEmpty()) return emptyList()
        return courses.asSequence()
            .filter { !it.signed }
            .map { course ->
                val begin = runCatching { app.courseRepository.parseBeginMs(course) }.getOrNull()
                val end = runCatching { app.courseRepository.parseEndMs(course) }.getOrNull()
                if (begin == null || end == null) {
                    QrRefreshPolicy.Window(null, null)
                } else {
                    QrRefreshPolicy.Window(begin - QingxinApiService.SIGN_WINDOW_LEAD_MS, end)
                }
            }
            .toList()
    }
}

/**
 * 「自动检查更新」节流间隔的**兜底值**（24 小时）。
 *
 * 正常取值来自耗电档位（`PowerProfile.updateCheckIntervalMs`）；这个常量只在
 * `QingxinApp` 初始化失败、读不到设置时使用。
 * 24 小时是刻意偏保守的值：应用发版以天为单位，而主页在每次从子页面
 * 返回时都会重组 —— 没有节流就会变成「点一次返回就查一次 GitHub」。
 * 手动的「检查更新」按钮不受它限制，用户想立刻知道结果随时可以问。
 */
private const val AUTO_CHECK_INTERVAL_FALLBACK_MS = 24L * 60L * 60L * 1000L
