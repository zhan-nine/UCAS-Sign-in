package com.ucas.qingxin.signin.lecture

/**
 * 建立讲座列表会话的状态机 —— 安卓侧对 UCAS-Desktop
 * `establishLectureSession` 的移植。
 *
 * 界面持有 WebView；本对象只产出「下一步该干什么」，不碰 View 引用。
 */
class LectureSessionBootstrap {

    /** 用户想打开哪一栏。 */
    var targetKind: LectureEventKind = LectureEventKind.HUMANITY
        private set

    /**
     * 当前阶段。
     *
     * - [Phase.IDLE]：未发起「打开讲座列表」
     * - [Phase.AWAIT_LOGIN]：等用户在 SEP 登录
     * - [Phase.FIND_PORTAL]：在工作台找选课入口
     * - [Phase.FIND_BRIDGE]：在选课主页找「讲座预告」
     * - [Phase.CONFIRM_TABLE]：已到栏目 URL，确认表头后再读
     */
    var phase: Phase = Phase.IDLE
        private set

    enum class Phase {
        IDLE,
        AWAIT_LOGIN,
        FIND_PORTAL,
        FIND_BRIDGE,
        CONFIRM_TABLE,
    }

    /** 状态机给界面的下一步指令。 */
    sealed class Step {
        data class Navigate(val url: String) : Step()
        data class Evaluate(val script: String, val expect: Expect) : Step()
        data class ReadSchedule(val kind: LectureEventKind) : Step()
        data class Fail(val message: String) : Step()
        data object Wait : Step()
    }

    enum class Expect { PORTAL_HREF, BRIDGE_HREF, TABLE_OK }

    /**
     * 用户点了「人文讲座 / 科学前沿讲座」。
     *
     * **绝不**直接返回栏目 URL —— 那正是 401 JSON 的来源。
     * 一律从 SEP 登录/工作台走门户跳转。
     */
    fun begin(
        kind: LectureEventKind,
        currentUrl: String,
        endpoints: Endpoints,
    ): Step {
        targetKind = kind
        val target = endpoints.scheduleUrl(kind)
        val surface = LecturePortal.surfaceOf(
            currentUrl, target, endpoints.sepLoginUrl, endpoints.xkgoMainUrl, endpoints.xkctsBase,
        )
        return when (surface) {
            LecturePortal.Surface.SCHEDULE -> {
                phase = Phase.CONFIRM_TABLE
                Step.Evaluate(LecturePortal.HAS_SCHEDULE_TABLE_SCRIPT, Expect.TABLE_OK)
            }
            LecturePortal.Surface.XKGO_MAIN -> {
                phase = Phase.FIND_BRIDGE
                Step.Evaluate(LecturePortal.findBridgeScript(kind == LectureEventKind.SCIENCE), Expect.BRIDGE_HREF)
            }
            LecturePortal.Surface.SEP_WORKBENCH -> {
                phase = Phase.FIND_PORTAL
                Step.Evaluate(LecturePortal.FIND_PORTAL_SCRIPT, Expect.PORTAL_HREF)
            }
            LecturePortal.Surface.SEP_LOGIN,
            LecturePortal.Surface.XKCTS_REDIRECT,
            LecturePortal.Surface.OTHER,
            -> {
                phase = Phase.AWAIT_LOGIN
                val login = endpoints.sepLoginUrl
                if (login.isBlank()) Step.Fail("未配置 SEP 登录地址")
                else Step.Navigate(login)
            }
        }
    }

    /** 每次主文档加载完成后调用。 */
    fun onPageFinished(currentUrl: String, endpoints: Endpoints): Step {
        if (phase == Phase.IDLE) return Step.Wait
        val target = endpoints.scheduleUrl(targetKind)
        val surface = LecturePortal.surfaceOf(
            currentUrl, target, endpoints.sepLoginUrl, endpoints.xkgoMainUrl, endpoints.xkctsBase,
        )
        if (surface == LecturePortal.Surface.XKCTS_REDIRECT) {
            phase = Phase.AWAIT_LOGIN
            return Step.Navigate(endpoints.sepLoginUrl)
        }
        return when (phase) {
            Phase.IDLE -> Step.Wait
            Phase.AWAIT_LOGIN -> when (surface) {
                LecturePortal.Surface.SEP_WORKBENCH -> {
                    phase = Phase.FIND_PORTAL
                    Step.Evaluate(LecturePortal.FIND_PORTAL_SCRIPT, Expect.PORTAL_HREF)
                }
                LecturePortal.Surface.XKGO_MAIN -> {
                    phase = Phase.FIND_BRIDGE
                    Step.Evaluate(
                        LecturePortal.findBridgeScript(targetKind == LectureEventKind.SCIENCE),
                        Expect.BRIDGE_HREF,
                    )
                }
                LecturePortal.Surface.SCHEDULE -> {
                    phase = Phase.CONFIRM_TABLE
                    Step.Evaluate(LecturePortal.HAS_SCHEDULE_TABLE_SCRIPT, Expect.TABLE_OK)
                }
                else -> Step.Wait // 仍在登录表单 / 验证页
            }
            Phase.FIND_PORTAL -> when (surface) {
                LecturePortal.Surface.SEP_WORKBENCH ->
                    Step.Evaluate(LecturePortal.FIND_PORTAL_SCRIPT, Expect.PORTAL_HREF)
                LecturePortal.Surface.XKGO_MAIN -> {
                    phase = Phase.FIND_BRIDGE
                    Step.Evaluate(
                        LecturePortal.findBridgeScript(targetKind == LectureEventKind.SCIENCE),
                        Expect.BRIDGE_HREF,
                    )
                }
                LecturePortal.Surface.SCHEDULE -> {
                    phase = Phase.CONFIRM_TABLE
                    Step.Evaluate(LecturePortal.HAS_SCHEDULE_TABLE_SCRIPT, Expect.TABLE_OK)
                }
                LecturePortal.Surface.SEP_LOGIN -> {
                    phase = Phase.AWAIT_LOGIN
                    Step.Wait
                }
                else -> Step.Wait
            }
            Phase.FIND_BRIDGE -> when (surface) {
                LecturePortal.Surface.XKGO_MAIN ->
                    Step.Evaluate(
                        LecturePortal.findBridgeScript(targetKind == LectureEventKind.SCIENCE),
                        Expect.BRIDGE_HREF,
                    )
                LecturePortal.Surface.SCHEDULE -> {
                    phase = Phase.CONFIRM_TABLE
                    Step.Evaluate(LecturePortal.HAS_SCHEDULE_TABLE_SCRIPT, Expect.TABLE_OK)
                }
                else -> Step.Wait
            }
            Phase.CONFIRM_TABLE -> when (surface) {
                LecturePortal.Surface.SCHEDULE ->
                    Step.Evaluate(LecturePortal.HAS_SCHEDULE_TABLE_SCRIPT, Expect.TABLE_OK)
                else -> {
                    // 栏目 URL 对了但会话丢了：重新走门户
                    phase = Phase.AWAIT_LOGIN
                    Step.Navigate(endpoints.sepLoginUrl)
                }
            }
        }
    }

    /** JS 查找脚本的回调。 */
    fun onScriptResult(
        expect: Expect,
        raw: String?,
        currentUrl: String,
        endpoints: Endpoints,
    ): Step {
        val hosts = LecturePortal.allowedHosts(
            endpoints.sepLoginUrl, endpoints.xkgoMainUrl, endpoints.xkctsBase,
        )
        return when (expect) {
            Expect.PORTAL_HREF -> {
                val href = LecturePortal.parseHrefResult(raw)
                val resolved = LecturePortal.resolvePortalHref(href, currentUrl, hosts)
                if (resolved.isNullOrBlank()) {
                    Step.Fail("SEP 中未找到「选课系统」或人文讲座入口，请确认已登录且账号有权限。")
                } else {
                    phase = Phase.FIND_BRIDGE
                    Step.Navigate(resolved)
                }
            }
            Expect.BRIDGE_HREF -> {
                val href = LecturePortal.parseHrefResult(raw)
                val resolved = LecturePortal.resolvePortalHref(href, currentUrl, hosts)
                if (resolved.isNullOrBlank()) {
                    Step.Fail("选课系统中未找到「讲座预告」入口，请核对菜单或账号权限。")
                } else {
                    phase = Phase.CONFIRM_TABLE
                    Step.Navigate(resolved)
                }
            }
            Expect.TABLE_OK -> {
                if (LecturePortal.parseOkResult(raw)) {
                    phase = Phase.IDLE
                    Step.ReadSchedule(targetKind)
                } else {
                    Step.Wait
                }
            }
        }
    }

    fun reset() {
        phase = Phase.IDLE
    }

    /** 建立会话所需的端点集合（全部来自 BuildConfig / local.properties）。 */
    data class Endpoints(
        val sepLoginUrl: String,
        val xkgoMainUrl: String,
        val xkctsBase: String,
        val humanityUrl: String,
        val scienceUrl: String,
    ) {
        fun scheduleUrl(kind: LectureEventKind): String =
            if (kind == LectureEventKind.SCIENCE) scienceUrl else humanityUrl
    }
}
