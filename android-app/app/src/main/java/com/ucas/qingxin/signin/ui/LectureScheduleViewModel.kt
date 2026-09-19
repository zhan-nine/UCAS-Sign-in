package com.ucas.qingxin.signin.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.ucas.qingxin.signin.BuildConfig
import com.ucas.qingxin.signin.QingxinApp
import com.ucas.qingxin.signin.lecture.LectureEventKind
import com.ucas.qingxin.signin.lecture.LecturePortal
import com.ucas.qingxin.signin.lecture.LectureSessionBootstrap
import com.ucas.qingxin.signin.lecture.LectureTableParser
import com.ucas.qingxin.signin.lecture.XkctsPage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 「讲座预告（预约系统）」页面的界面状态。
 *
 * **刻意不含 URL**：SSO 跳转链接里带一次性票据。
 */
data class LectureScheduleUiState(
    val pageTitle: String = "",
    val onSchedulePage: Boolean = false,
    val pageLoading: Boolean = true,
    val reading: Boolean = false,
    /** 正在自动走「选课系统 → 讲座预告」会话建立。 */
    val bootstrapping: Boolean = false,
    val bootstrapHint: String = "",
    val readAt: String = "",
    val eventCount: Int = 0,
    val kinds: Set<LectureEventKind> = emptySet(),
    val message: String = "",
    val error: String = "",
) {
    val empty: Boolean get() = eventCount == 0
}

/**
 * 讲座预告 ViewModel。
 *
 * 会话建立对齐上游 UCAS-Desktop 的 `establishLectureSession`：
 * SEP 登录 → 选课系统门户 → xkgo「讲座预告」桥接 → xkcts 读表。
 * **禁止**在未建会话时直开栏目 URL。
 */
class LectureScheduleViewModel(app: Application) : AndroidViewModel(app) {

    private val app: QingxinApp get() = getApplication()
    private val bootstrap = LectureSessionBootstrap()

    private val _ui = MutableStateFlow(LectureScheduleUiState())
    val ui: StateFlow<LectureScheduleUiState> = _ui.asStateFlow()

    val sepLoginUrl: String get() = cfg { BuildConfig.SEP_LOGIN_URL }
    val xkgoMainUrl: String get() = cfg { BuildConfig.XKGO_MAIN_URL }
    val xkctsBaseUrl: String get() = cfg { BuildConfig.XKCTS_BASE_URL }
    val humanityUrl: String get() = cfg { BuildConfig.XKCTS_HUMANITY_URL }
    val scienceUrl: String get() = cfg { BuildConfig.XKCTS_SCIENCE_URL }
    val startUrl: String get() = sepLoginUrl

    val endpoints: LectureSessionBootstrap.Endpoints
        get() = LectureSessionBootstrap.Endpoints(
            sepLoginUrl = sepLoginUrl,
            xkgoMainUrl = xkgoMainUrl,
            xkctsBase = xkctsBaseUrl,
            humanityUrl = humanityUrl,
            scienceUrl = scienceUrl,
        )

    init {
        readStore()
    }

    fun onPageStarted() {
        _ui.update { it.copy(pageLoading = true) }
    }

    fun onPageFinished(pageTitle: String, onSchedulePage: Boolean) {
        _ui.update {
            it.copy(
                pageLoading = false,
                pageTitle = pageTitle,
                onSchedulePage = onSchedulePage,
                error = if (onSchedulePage) "" else it.error,
            )
        }
    }

    fun onPageError(message: String) {
        _ui.update { it.copy(pageLoading = false, bootstrapping = false, error = message) }
    }

    /**
     * 用户点「人文讲座 / 科学前沿讲座」。
     *
     * 返回状态机的下一步；界面据此导航或执行 JS。**不会**直接给出栏目 URL。
     */
    fun beginOpenSchedule(kind: LectureEventKind, currentUrl: String): LectureSessionBootstrap.Step {
        _ui.update {
            it.copy(
                bootstrapping = true,
                bootstrapHint = "正在经选课系统建立讲座会话…",
                error = "",
                message = "",
            )
        }
        return applyStep(bootstrap.begin(kind, currentUrl, endpoints))
    }

    /** 主文档加载完成：推进会话状态机。 */
    fun onBootstrapPageFinished(currentUrl: String): LectureSessionBootstrap.Step {
        if (bootstrap.phase == LectureSessionBootstrap.Phase.IDLE) {
            return LectureSessionBootstrap.Step.Wait
        }
        return applyStep(bootstrap.onPageFinished(currentUrl, endpoints))
    }

    fun onBootstrapScriptResult(
        expect: LectureSessionBootstrap.Expect,
        raw: String?,
        currentUrl: String,
    ): LectureSessionBootstrap.Step =
        applyStep(bootstrap.onScriptResult(expect, raw, currentUrl, endpoints))

    fun onExtracted(raw: String?, kind: LectureEventKind) {
        if (!app.isReady) return
        val events = LectureTableParser.parse(raw, kind)
        if (events.isEmpty()) {
            _ui.update {
                it.copy(
                    reading = false,
                    bootstrapping = false,
                    error = "没有读到讲座时间表：请确认已出现「讲座时间 / 讲座名称」表头后再试。",
                )
            }
            return
        }
        val now = LocalDateTime.now()
        val at = now.format(READ_AT_FORMAT)
        val merged = app.lectureScheduleStore.events().filter { it.kind != kind } + events
        app.lectureScheduleStore.save(merged, at)
        val ended = events.count { LectureTableParser.isEnded(it, now) }
        _ui.update {
            it.copy(
                reading = false,
                bootstrapping = false,
                bootstrapHint = "",
                error = "",
                message = "已读到 ${events.size} 场${kind.label}（其中 ${ended} 场已结束）",
                readAt = at,
                eventCount = merged.size,
                kinds = merged.mapTo(LinkedHashSet()) { e -> e.kind },
            )
        }
    }

    fun onReadFailed(message: String) {
        _ui.update { it.copy(reading = false, bootstrapping = false, bootstrapHint = "", error = message) }
        bootstrap.reset()
    }

    fun onReading() {
        _ui.update { it.copy(reading = true, error = "", message = "") }
    }

    fun clearStore() {
        if (!app.isReady) return
        app.lectureScheduleStore.clear()
        _ui.update {
            it.copy(readAt = "", eventCount = 0, kinds = emptySet(), message = "已清空本机保存的讲座列表")
        }
    }

    private fun applyStep(step: LectureSessionBootstrap.Step): LectureSessionBootstrap.Step {
        when (step) {
            is LectureSessionBootstrap.Step.Fail -> {
                _ui.update {
                    it.copy(bootstrapping = false, bootstrapHint = "", error = step.message)
                }
                bootstrap.reset()
            }
            is LectureSessionBootstrap.Step.ReadSchedule -> {
                _ui.update {
                    it.copy(
                        bootstrapping = false,
                        bootstrapHint = "",
                        onSchedulePage = true,
                        pageTitle = "讲座列表",
                    )
                }
            }
            is LectureSessionBootstrap.Step.Navigate -> {
                _ui.update {
                    it.copy(
                        bootstrapping = true,
                        bootstrapHint = when (bootstrap.phase) {
                            LectureSessionBootstrap.Phase.AWAIT_LOGIN -> "请先完成 SEP 登录"
                            LectureSessionBootstrap.Phase.FIND_PORTAL -> "正在进入选课系统…"
                            LectureSessionBootstrap.Phase.FIND_BRIDGE -> "正在打开讲座预告…"
                            LectureSessionBootstrap.Phase.CONFIRM_TABLE -> "正在打开讲座列表…"
                            else -> "正在建立讲座会话…"
                        },
                    )
                }
            }
            is LectureSessionBootstrap.Step.Evaluate -> {
                _ui.update { it.copy(bootstrapping = true) }
            }
            LectureSessionBootstrap.Step.Wait -> Unit
        }
        return step
    }

    private fun readStore() {
        if (!app.isReady) return
        val events = app.lectureScheduleStore.events()
        _ui.update {
            it.copy(
                readAt = app.lectureScheduleStore.readAt(),
                eventCount = events.size,
                kinds = events.mapTo(LinkedHashSet()) { e -> e.kind },
            )
        }
    }

    private inline fun cfg(block: () -> String): String =
        runCatching { block().trim() }.getOrDefault("")

    companion object {
        private val READ_AT_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        fun kindOf(url: String, humanity: String, science: String): LectureEventKind? =
            XkctsPage.kindOf(url, humanity, science)

        fun isSessionRedirect(url: String, xkctsBase: String): Boolean =
            XkctsPage.isSessionRedirect(url, xkctsBase)

        fun isAuthFailurePayload(body: String): Boolean = XkctsPage.isAuthFailurePayload(body)

        fun surfaceOf(
            url: String,
            scheduleTarget: String,
            sepLoginUrl: String,
            xkgoMainUrl: String,
            xkctsBase: String,
        ): LecturePortal.Surface = LecturePortal.surfaceOf(
            url, scheduleTarget, sepLoginUrl, xkgoMainUrl, xkctsBase,
        )
    }
}
