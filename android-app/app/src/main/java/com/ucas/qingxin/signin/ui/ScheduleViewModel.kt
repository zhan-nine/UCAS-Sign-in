package com.ucas.qingxin.signin.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ucas.qingxin.signin.QingxinApp
import com.ucas.qingxin.signin.course.CourseRepository
import com.ucas.qingxin.signin.data.Course
import com.ucas.qingxin.signin.data.CourseQueryResult
import com.ucas.qingxin.signin.util.DateInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * 「按日期看课表」页面的完整界面状态。
 *
 * 与 [AppUiState] 一样是不可变快照，所有变更都必须走 [ScheduleViewModel]。
 *
 * 刻意**不含任何签到字段**：这一页只回答「某天有什么课」。签到二维码、手动选课、
 * 一键签到都留在首页，因为那些能力都隐含「就是今天」这个前提 ——
 * 把明天的课配上二维码，只会让人误以为现在能签。
 */
data class ScheduleUiState(
    /** 学校时区下的今天；用于「今天」按钮与 [isToday] 判定。 */
    val today: LocalDate,
    val selectedDate: LocalDate,
    /** 输入框里的原始文本。选中日期后会被规范成 `yyyy-MM-dd`。 */
    val dateInput: String,
    /** 输入无法解析，界面据此标红并提示。 */
    val inputError: Boolean = false,
    val courses: List<Course> = emptyList(),
    val loading: Boolean = false,
    val message: String = "",
    val error: String = "",
    val fromCache: Boolean = false,
) {
    val isToday: Boolean get() = selectedDate == today
    val total: Int get() = courses.size
}

/**
 * 课表浏览页的 ViewModel。
 *
 * ## 职责边界（关键）
 * 本页**只读**：它通过 [CourseRepository.browseDay] 取数，而那条路径刻意不写
 * `CourseRepository.courses` —— 那条 StateFlow 是「今天」的唯一真值源，
 * 同时被桌面小部件与自动签到引擎消费。若浏览结果也写进去，用户在新鲜窗口内
 * 翻一下明天的课表，守护进程就可能把明天的课当成今天去签。
 *
 * ## 取数策略
 * 先渲染本地缓存（翻回看过的日期时立刻有内容），再发网络请求覆盖。
 * 每天的缓存键是 `c_<学号>_<yyyyMMdd>`，与首页共用同一份，
 * 因此首页刚刷过的今天在这里是秒开的。
 *
 * ## 为什么每次进页面都重取
 * [onEnter] 无条件重新拉取当前选中日期。刻意**不**记「这份数据属于哪个学号」：
 * 退出登录换账号后，用标记判断很容易漏掉某条路径，而漏掉的后果是把上一个账号的
 * 课表留在屏幕上。缓存优先渲染又让这次重取几乎没有感知成本，
 * 于是「永远重取」是这里最省心也最安全的规则。
 */
class ScheduleViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as QingxinApp

    private val initialToday: LocalDate = todayNow()

    private val _ui = MutableStateFlow(
        ScheduleUiState(
            today = initialToday,
            selectedDate = initialToday,
            dateInput = DateInput.display(initialToday),
            error = if (app.isReady) "" else INIT_ERROR,
        ),
    )
    val ui: StateFlow<ScheduleUiState> = _ui.asStateFlow()

    private var loadJob: Job? = null

    /** 进入页面时调用：重新拉取当前选中日期（首次即为今天）。 */
    fun onEnter() = selectDate(_ui.value.selectedDate)

    /** 输入框每次变化：只清掉上一次的错误提示，不实时校验（打到一半必然是非法日期）。 */
    fun onDateInputChange(text: String) {
        _ui.update { it.copy(dateInput = text, inputError = false) }
    }

    /** 点「查询」/ 键盘回车：解析输入并跳转；解析不出就标红，绝不替用户猜一个日期。 */
    fun submitDateInput() {
        val state = _ui.value
        val parsed = DateInput.parse(state.dateInput, state.today)
        if (parsed == null) {
            _ui.update { it.copy(inputError = true, error = "") }
            return
        }
        selectDate(parsed)
    }

    /** 前一天 / 后一天（[delta] 为 ±1）。 */
    fun stepDay(delta: Long) = selectDate(_ui.value.selectedDate.plusDays(delta))

    /** 回到今天。 */
    fun goToday() = selectDate(todayNow())

    /** 重新拉取当前选中日期。 */
    fun refresh() = selectDate(_ui.value.selectedDate)

    /** 跳到某一天：先铺缓存，再取网络。 */
    fun selectDate(date: LocalDate) {
        val cached = readCached(date)
        _ui.update {
            it.copy(
                today = todayNow(),
                selectedDate = date,
                // 输入框与选中日期保持同步：否则「输入 9-20 后按后一天」会让框里
                // 留着 9-20 而列表显示 9-21，看起来像没生效。
                dateInput = DateInput.display(date),
                inputError = false,
                courses = cached?.courses ?: emptyList(),
                message = cached?.message.orEmpty(),
                fromCache = cached != null,
                error = "",
            )
        }
        load(date)
    }

    private fun load(date: LocalDate) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = "") }
            try {
                val result = app.courseRepository.browseDay(date)
                // 请求期间用户又翻了别的日期：这次结果已经过期，直接丢弃。
                // 不能只依赖取消 —— 结果落地的瞬间可能正巧在取消生效之前。
                if (_ui.value.selectedDate != date) return@launch
                _ui.update {
                    it.copy(
                        courses = result.courses,
                        message = result.message,
                        fromCache = result.fromCache,
                        loading = false,
                        error = "",
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_ui.value.selectedDate != date) return@launch
                // 失败时清空列表，而不是留着上一个日期那批课：标题写的是新日期，
                // 留着旧行会让人以为「那天就是这些课」。
                _ui.update {
                    it.copy(
                        courses = emptyList(),
                        message = "",
                        fromCache = false,
                        loading = false,
                        error = e.message ?: "课表加载失败",
                    )
                }
            }
        }
    }

    /** 只读本地缓存；未登录或读取异常时返回 null（当作没有缓存，而不是报错）。 */
    private fun readCached(date: LocalDate): CourseQueryResult? {
        if (!app.isReady) return null
        val studentNo = runCatching { app.authRepository.session.value?.studentNo }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return app.courseRepository.cachedToday(studentNo, date)
    }

    private fun todayNow(): LocalDate = LocalDate.now(CourseRepository.ZONE)

    private companion object {
        const val INIT_ERROR = "应用初始化失败（可能是系统密钥库异常），请重启应用后再试"
    }
}
