package com.ucas.qingxin.signin.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ucas.qingxin.signin.QingxinApp
import com.ucas.qingxin.signin.data.AttendanceUiStatus
import com.ucas.qingxin.signin.data.Course
import com.ucas.qingxin.signin.data.QrSnapshot
import com.ucas.qingxin.signin.data.SignOutcome
import com.ucas.qingxin.signin.data.UserSettings
import com.ucas.qingxin.signin.network.ApiException
import com.ucas.qingxin.signin.widget.TodayCourseWidgetReceiver
import kotlinx.coroutines.CancellationException
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
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as QingxinApp
    private val _ui = MutableStateFlow(AppUiState(settings = app.attendanceScheduler.getSettings()))
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
        viewModelScope.launch {
            val session = app.authRepository.restore()
            if (session != null) {
                _ui.update {
                    it.copy(
                        restoring = false,
                        authenticated = true,
                        studentNo = session.studentNo,
                        studentNoInput = app.credentialStore.getLoginId().orEmpty().ifBlank { session.studentNo },
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
        app.authRepository.logout()
        _ui.value = AppUiState(
            restoring = false,
            authenticated = false,
            settings = app.attendanceScheduler.getSettings(),
        )
        TodayCourseWidgetReceiver.requestUpdate(getApplication())
    }

    fun refreshCourses() {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, error = "", message = "正在更新课程…") }
            try {
                val result = app.courseRepository.loadToday()
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
    ) {
        val cur = _ui.value.settings
        val next = cur.copy(
            autoSignEnabled = autoSign ?: cur.autoSignEnabled,
            notifyEnabled = notify ?: cur.notifyEnabled,
        )
        app.attendanceScheduler.saveSettings(next)
        _ui.update { it.copy(settings = next) }
    }

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
