package com.ucas.qingxin.signin.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ucas.qingxin.signin.QingxinApp
import com.ucas.qingxin.signin.attendance.LectureNoticeWatcher
import com.ucas.qingxin.signin.lecture.LectureBoard
import com.ucas.qingxin.signin.lecture.LectureEntry
import com.ucas.qingxin.signin.lecture.LectureEvent
import com.ucas.qingxin.signin.lecture.LectureNotice
import com.ucas.qingxin.signin.lecture.LectureSentinel
import com.ucas.qingxin.signin.lecture.LectureType
import com.ucas.qingxin.signin.lecture.NoticeQueryResult
import com.ucas.qingxin.signin.network.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDateTime

/**
 * 「人文讲座」页面的完整界面状态。
 *
 * 与 [AppUiState] 一样是**不可变**快照：界面只读，所有变更都必须走
 * [LectureViewModel] 的方法，避免出现「一处改了、另一处没跟上」的漂移。
 */
data class LectureUiState(
    val loading: Boolean = false,
    /**
     * 「即将开始」段：只有**带确切讲座日期**且尚未结束的条目（来自预约系统）。
     * 按时间升序。学院网站只有标题的通知不在这里 —— 见 [undated]。
     */
    val upcoming: List<LectureEntry> = emptyList(),
    /**
     * 「暂无举办日期」段：学院网站的预告/通知，尚未出现报道，也还没有从预约系统
     * 读到时间。发布日 ≠ 讲座日，因此不能放进「即将开始」。
     */
    val undated: List<LectureEntry> = emptyList(),
    /**
     * 「往期」段：已确认结束的讲座与往期报道。默认**折叠**。
     *
     * 折叠是有意的：用户打开这一页几乎总是为了看「接下来有什么」，
     * 而往期会随时间无限增长 —— 不折叠的话，越用越旧的历史会把真正要看的内容顶下去。
     */
    val past: List<LectureEntry> = emptyList(),
    /** 往期段是否展开（默认折叠，见 [LectureUiState.past]）。 */
    val pastExpanded: Boolean = false,
    /**
     * 选中的栏目；**null 表示「全部」**。
     *
     * 用「单值 + null」而不是「集合」：这三个按钮在语义上是**互斥**的
     * （「全部」与某个具体栏目不可能同时成立）。此前用集合表达，就必然出现
     * 「明德 + 艺术」同时选中的合法状态，于是三个按钮可以一起高亮，
     * 而这在界面上没有任何意义 —— 用类型把这个非法状态消掉，
     * 比在点击逻辑里小心翼翼地互斥更可靠。
     */
    val selectedType: LectureType? = null,
    /** 当前筛选下的条目总数（三段之和），用于标题报数。 */
    val total: Int = 0,
    val message: String = "",
    val error: String = "",
    /** 「新讲座通知」开关。 */
    val notifyEnabled: Boolean = false,
    /**
     * 「找下一场讲座」哨兵的开关与状态。
     *
     * 与 [notifyEnabled] 分开：通知是「学院网站发了新预告」，哨兵是「课程注册表里
     * 开出了新场次」。后者能更早发现，但**只有名称、没有时间场地**，
     * 因此两者的开关与文案都必须分开，不能让用户以为它们是一回事。
     */
    val sentinelEnabled: Boolean = false,
    /** 是否具备运行条件（构建时未注入注册表端点/基点编号时为 false）。 */
    val sentinelAvailable: Boolean = true,
    val sentinelScanning: Boolean = false,
    val sentinelLastRunAt: String = "",
    val sentinelSummary: String = "",
    /** 上一次哨兵发现的讲座名称。 */
    val sentinelFound: List<String> = emptyList(),
    /**
     * 讲座时间表（预约系统）上次读取的时间（`yyyy-MM-dd HH:mm`）；从未读过时为空串。
     *
     * 必须显示在界面上：这份数据只能由用户手工登录后读取，陈旧程度只有用户自己知道
     * 该不该信。把它藏起来，用户会把几天前的列表当成当前的。
     */
    val scheduleReadAt: String = "",
    /** 本机存着的讲座时间表条数（= 有时间场地的讲座条数）。 */
    val scheduleCount: Int = 0,
    /**
     * 提示信息（浅色，非错误）：目前用于哨兵扫描的结果。
     *
     * 手动打卡已搬去独立页面（[ManualSignViewModel]），因此这里不再是「签到结果」；
     * 名字从 `signMessage` 改成 `statusMessage`，以免读者以为本页还能签到。
     */
    val statusMessage: String = "",
)

/**
 * 人文讲座页 ViewModel。
 *
 * 设计约束：
 * 1. 栏目筛选是**纯本地过滤**（不重新发网络请求），因此 [LectureUiState.notices]
 *    始终是「已加载数据 ∩ 已选栏目」的结果。
 * 2. 列表数据**每次刷新都从网页抓取**，没有本地缓存回退（见 `LectureRepository`）。
 * 3. 讲座通知里**没有**可签到的节次标识，因此列表条目一律不可一键签到；
 *    唯一的签到入口是**手动录入现场读到的编号**（[signManual]）。
 * 4. 每次成功刷新后都会调用 [LectureNoticeWatcher.sync]，把「出现了新通知」
 *    变成一条推送 —— 这也是本页唯一的主动打扰。
 */
class LectureViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as QingxinApp

    /**
     * 只在需要时向系统取一次 application context，**不持有字段**。
     *
     * 存成字段会被 lint 判为 `StaticFieldLeak`（它无法判断这是 Application 级上下文）；
     * 用取值器则既无泄漏风险，也避免了这个假阳性。
     */
    private val context: Context get() = getApplication<Application>()

    /**
     * 未经栏目筛选的全量通知（[LectureUiState.upcoming] / [LectureUiState.past] 的过滤源）。
     *
     * 必须与已筛选的列表分开保存：只凭已过滤列表无法在用户
     * 「取消筛选」时还原出被过滤掉的条目。
     */
    private var allNotices: List<LectureNotice> = emptyList()

    /**
     * 已确认结束的场次键。
     *
     * 与 [allNotices] 同源、同生命周期，但**不能**从 [allNotices] 反推：
     * 列表已按场次合并过，而判据需要「同一场次下同时存在预告与报道」这份原始信息。
     */
    private var endedKeys: Set<String> = emptySet()

    /**
     * 讲座时间表（预约系统）的条目。
     *
     * 每次重算时**现读**存档，而不是缓存在字段里：这份数据由另一个页面
     * （[LectureScheduleViewModel] 那边的 WebView）写入，两个 ViewModel 都是
     * Activity 级的、谁先谁后不确定。现读存档是让「读完回到本页立刻能看到」
     * 这件事成立的最省事、也最不容易错的做法（SharedPreferences 首读之后有内存缓存，
     * 每次读取的代价只是一次 JSON 解析，量级在几十条）。
     */
    private fun scheduleEvents(): List<LectureEvent> =
        if (app.isReady) app.lectureScheduleStore.events() else emptyList()

    private val _ui = MutableStateFlow(
        LectureUiState(
            error = if (app.isReady) "" else INIT_ERROR,
            notifyEnabled = readNotifyEnabled(),
            sentinelAvailable = if (app.isReady) LectureSentinel.isAvailable() else true,
        ),
    )
    val ui: StateFlow<LectureUiState> = _ui.asStateFlow()

    private var loadJob: Job? = null

    init {
        // 哨兵的开关与上次结果存在 SharedPreferences 里，构造时读一次即可；
        // 之后每次切换/扫描完成由 readSentinelState() 刷新。
        readSentinelState()
    }

    /** 重新拉取通知（进入页面、点「刷新」都会走这里）。 */
    fun refresh() = reload(showLoading = true)

    /**
     * 选择栏目。
     *
     * [LectureType] 表示只看该栏目；传 `null` 表示「全部」。
     * 重复点击同一个栏目不再「取消选中」—— 三个按钮互斥，点哪个就是哪个，
     * 「取消」的语义由「全部」这个按钮承担，不需要一个隐式的第二次点击。
     */
    fun selectType(type: LectureType?) {
        _ui.update { rebuild(it.copy(selectedType = type)) }
    }

    /**
     * 「新讲座通知」开关。
     *
     * 开启时顺手用当前列表对齐一次「已见集合」：用户往往是在看到列表之后才想起开通知的，
     * 此刻不对齐，下一次刷新会把列表里**早就存在**的通知当成「刚刚发布」全部推一遍。
     */
    fun setNotifyEnabled(enabled: Boolean) {
        _ui.update { it.copy(notifyEnabled = enabled, error = "") }
        try {
            LectureNoticeWatcher.setEnabled(context, enabled)
            if (enabled) LectureNoticeWatcher.sync(context, allNotices)
        } catch (e: Exception) {
            _ui.update { it.copy(error = e.message ?: "通知设置失败") }
        }
    }

    /**
     * 展开 / 折叠「往期」段。
     *
     * 折叠状态只是**视图偏好**，因此这里不做任何持久化：每次进页面都回到折叠态，
     * 这正是想要的默认（用户几乎总是先看「接下来有什么」）。
     */
    fun togglePast() {
        _ui.update { rebuild(it.copy(pastExpanded = !it.pastExpanded)) }
    }

    /**
     * 「找下一场讲座」哨兵开关。
     *
     * 与 [setNotifyEnabled] 一样顺手对齐一次「已见期次」是不需要的：
     * 哨兵自己有「首次只记录」的保护（见 `LectureSentinel`），
     * 而且它的去重是以期次号为单位，不依赖当前列表。
     */
    fun setSentinelEnabled(enabled: Boolean) {
        if (!app.isReady) return
        _ui.update { it.copy(sentinelEnabled = enabled, error = "") }
        try {
            LectureSentinel.setEnabled(context, enabled)
        } catch (e: Exception) {
            _ui.update { it.copy(error = e.message ?: "哨兵设置失败") }
        }
        readSentinelState()
    }

    /** 立刻跑一轮哨兵扫描（不等待周期任务）。 */
    fun scanSentinelNow() {
        if (!app.isReady) return
        if (_ui.value.sentinelScanning) return
        viewModelScope.launch {
            _ui.update { it.copy(sentinelScanning = true, error = "", statusMessage = "") }
            val outcome = LectureSentinel.run(context)
            _ui.update {
                it.copy(
                    sentinelScanning = false,
                    error = if (outcome.ok) "" else outcome.message,
                    statusMessage = if (outcome.ok) outcome.message else "",
                )
            }
            readSentinelState()
        }
    }

    /** 把哨兵的开关与上次结果读进界面状态（构造时、切换后、扫描后各读一次）。 */
    private fun readSentinelState() {
        if (!app.isReady) return
        val available = LectureSentinel.isAvailable()
        _ui.update {
            it.copy(
                sentinelAvailable = available,
                sentinelEnabled = LectureSentinel.isEnabled(context),
                sentinelLastRunAt = LectureSentinel.lastRunAt(context),
                sentinelSummary = LectureSentinel.lastSummary(context),
                sentinelFound = LectureSentinel.lastFound(context),
            )
        }
    }

    private fun reload(showLoading: Boolean) {
        if (!app.isReady) return
        // 连点刷新：只保留最后一次，避免旧结果盖回新状态。
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            if (showLoading) _ui.update { it.copy(loading = true, error = "") }
            // 先用本机已有的数据铺一次：讲座时间表存在本地，不依赖网络。
            // 这一步保证「去预约系统读完表、返回本页」立刻能看到结果，
            // 也保证网络失败时页面不是空的。
            _ui.update { rebuild(it) }
            try {
                val result = app.lectureRepository.loadNotices()
                applyLoaded(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                _ui.update { it.copy(loading = false, error = e.message ?: e.code) }
            } catch (e: Exception) {
                _ui.update { it.copy(loading = false, error = e.message ?: "讲座通知加载失败") }
            }
        }
    }

    private fun applyLoaded(result: NoticeQueryResult) {
        allNotices = result.notices
        endedKeys = result.endedGroupKeys
        _ui.update {
            rebuild(
                it.copy(
                    loading = false,
                    message = result.message,
                    error = "",
                ),
            )
        }
        // 新通知推送：即使开关关闭也要比对一次，否则关着开关期间累积的通知
        // 会在用户下次开启的瞬间被全部当成「新的」推出去。
        try {
            LectureNoticeWatcher.sync(context, allNotices)
        } catch (_: Exception) {
            // 通知逻辑失败不应影响列表展示。
        }
    }

    /**
     * 由「未筛选的通知 + 预约系统的时间表 + 当前筛选」重算全部派生列表。
     *
     * 之所以收成一个入口，是因为派生量已不止一个（即将开始、往期、总数、读取时间），
     * 若每个改动点各自拼 `copy(...)`，迟早出现「改了筛选却忘了重算往期」这类不一致。
     *
     * 每次调用都会**重新读一次存档**并重新取当前时刻：前者保证另一个页面刚写入的数据
     * 立刻可见，后者保证「讲座刚讲完」这件事会被下一次重算捕捉到
     * （否则一条已结束的讲座会一直留在「即将开始」里，直到用户手动刷新）。
     */
    private fun rebuild(state: LectureUiState): LectureUiState {
        val events = scheduleEvents()
        val board = LectureBoard.build(
            events = events,
            notices = allNotices,
            endedGroupKeys = endedKeys,
            now = LocalDateTime.now(),
        )
        val upcoming = filterByType(board.upcoming, state.selectedType)
        val undated = filterByType(board.undated, state.selectedType)
        val past = filterByType(board.past, state.selectedType)
        return state.copy(
            upcoming = upcoming,
            undated = undated,
            past = past,
            total = upcoming.size + undated.size + past.size,
            scheduleReadAt = if (app.isReady) app.lectureScheduleStore.readAt() else "",
            scheduleCount = events.size,
        )
    }

    /**
     * 栏目筛选。
     *
     * 判据是 [LectureEntry.type]（按期次号形态），不是条目的 `category` ——
     * 后者在同一条记录被两个来源合并后可能来自任一侧，用它筛会时灵时不灵。
     */
    private fun filterByType(list: List<LectureEntry>, type: LectureType?): List<LectureEntry> =
        if (type == null) list else list.filter { it.type == type }

    private fun readNotifyEnabled(): Boolean = try {
        if (app.isReady) LectureNoticeWatcher.isEnabled(context) else false
    } catch (_: Exception) {
        false
    }

    private companion object {
        const val INIT_ERROR = "应用初始化失败（可能是系统密钥库异常），请重启应用后再试"
    }
}
