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
import com.ucas.qingxin.signin.update.UpdateRelease
import com.ucas.qingxin.signin.update.VersionTags
import com.ucas.qingxin.signin.widget.TodayCourseWidgetReceiver
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
)

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
            startQrTicker()
        }
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
        app.attendanceScheduler.saveSettings(next)
        _ui.update { it.copy(settings = next, keepAlive = app.attendanceScheduler.readKeepAliveState()) }
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
     * 刷新保活状态；并借「应用已在前台」这个时机重试一次守护服务。
     *
     * 冷启动（例如被小部件宿主拉起）时启动前台服务会被系统拒绝，
     * 那时标记的「服务被拒」状态会在这里被纠正 —— 用户把 App 切到前台即可自愈。
     */
    fun refreshKeepAlive() {
        if (!app.isReady) return
        // 放到 IO 线程：这里会读本地缓存与系统状态，不应阻塞 onResume 的渲染。
        viewModelScope.launch(Dispatchers.IO) {
            val settings = app.attendanceScheduler.getSettings()
            if (settings.autoSignEnabled && !settings.lowPowerMode) {
                app.attendanceScheduler.start()
            }
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
     * 12 小时内最多检查一次。节流是必须的：主页在每次从子页面返回时都会重组，
     * 没有节流就会变成「点一次返回就查一次 GitHub」。
     * 首次启动（[UpdateUiState.lastCheckedAtMs] 为 0）不节流，保证装完就有结论。
     */
    fun onEnterHome() {
        val update = _ui.value.update
        if (!update.autoCheck) return
        val now = System.currentTimeMillis()
        if (update.lastCheckedAtMs > 0 && now - update.lastCheckedAtMs < AUTO_CHECK_INTERVAL_MS) return
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
                // 会让用户手里的新版本提示凭空消失 12 小时。
                app.updateCheckStore.markChecked(now)
                app.updateCheckStore.saveAvailable(newer)
                _ui.update { state ->
                    state.copy(
                        update = state.update.copy(
                            checking = false,
                            available = newer,
                            ignoredTag = app.updateCheckStore.ignoredTag(),
                            lastCheckedAtMs = now,
                            status = if (newer == null) {
                                "已是最新版本 ${BuildConfig.VERSION_NAME}"
                            } else {
                                "发现新版本 ${newer.version}"
                            },
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
            fallback.copy(
                autoCheck = store.isAutoCheckEnabled(),
                // 缓存里那条若已经不比当前版本新（用户自己装了新版），就地丢弃，
                // 否则换包之后横幅还会挂着上一个版本。
                available = store.cachedAvailable()?.takeIf { isNewerThanCurrent(it) },
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

    private fun startQrTicker() {
        qrTickJob?.cancel()
        qrTickJob = viewModelScope.launch {
            while (isActive) {
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
                delay(100)
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

    private fun startQrLoopIfNeeded(force: Boolean = false) {
        if (!_ui.value.authenticated) {
            qrRefreshJob?.cancel()
            return
        }
        if (!force && qrRefreshJob?.isActive == true) return
        qrRefreshJob?.cancel()
        qrRefreshJob = viewModelScope.launch {
            while (isActive) {
                val displayNow = System.currentTimeMillis()
                val courses = _ui.value.courses
                val (current, next) = app.courseRepository.currentAndNext(courses, displayNow)
                if (_ui.value.current?.id != current?.id || _ui.value.next?.id != next?.id) {
                    _ui.update { it.copy(current = current, next = next) }
                }

                val target = resolveQrCourse(courses, current, next)
                if (target == null) {
                    // 无课时清空二维码；有课则始终刷新（含已签到），避免时间轴空白
                    if (_ui.value.qr != null || _ui.value.selected != null) {
                        _ui.update {
                            it.copy(
                                selected = null,
                                qr = null,
                                qrRemaining = 0.0,
                                qrProgress = 0f,
                            )
                        }
                    }
                    delay(5_000)
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
                try {
                    val key = courseForQr.id.ifBlank { courseForQr.uuid }
                    val snap = app.qrTimeline.refreshQr(key)
                    coroutineContext.ensureActive()
                    val schoolNow = app.qrTimeline.currentSchoolTimeOrNull()
                    val remainMs = (snap.expiresAtLocalMs - System.currentTimeMillis()).coerceAtLeast(0L)
                    _ui.update {
                        it.copy(
                            selected = courseForQr,
                            qr = snap,
                            qrRemaining = remainMs / 1000.0,
                            qrProgress = if (snap.validityDurationMs > 0) {
                                remainMs.toFloat() / snap.validityDurationMs.toFloat()
                            } else {
                                0f
                            },
                            schoolNowLabel = schoolNow?.toString().orEmpty(),
                            status = app.attendanceRepository.uiStatus(courseForQr, schoolNow ?: displayNow),
                            error = "",
                        )
                    }
                    val wait = (snap.expiresAtLocalMs - System.currentTimeMillis()).coerceAtLeast(200L)
                    delay(wait)
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

/**
 * 自动检查更新的节流间隔（12 小时）。
 *
 * 12 小时是一个刻意偏保守的值：应用发版以天为单位，而主页在每次从子页面
 * 返回时都会重组 —— 没有节流就会变成「点一次返回就查一次 GitHub」。
 * 手动的「检查更新」按钮不受它限制，用户想立刻知道结果随时可以问。
 */
private const val AUTO_CHECK_INTERVAL_MS = 12L * 60L * 60L * 1000L
