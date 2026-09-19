package com.ucas.qingxin.signin.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ucas.qingxin.signin.QingxinApp
import com.ucas.qingxin.signin.attendance.SignCode
import com.ucas.qingxin.signin.data.Course
import com.ucas.qingxin.signin.data.SignOutcome
import com.ucas.qingxin.signin.network.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 「手动打卡」独立页面的界面状态。
 *
 * 与 [LectureUiState] 一样是**不可变**快照：界面只读，变更只走 [ManualSignViewModel]。
 */
data class ManualSignUiState(
    /** 输入框内容（原样保留，不做实时改写 —— 边打字边被改写会让光标乱跳）。 */
    val input: String = "",
    /** 是否正在提交。 */
    val signing: Boolean = false,
    /** 步骤信息（浅色提示，如「已识别为 7 位节次编号」）。 */
    val hint: String = "",
    /** 成功信息。 */
    val success: String = "",
    /** 失败原因。 */
    val error: String = "",
) {
    /**
     * 识别结果；无法识别时为 `null`。
     *
     * 由界面用来做实时反馈与按钮可用性判断 —— 让用户在**点之前**就知道
     * 「这串编号认不认」，而不是点了之后才被告知格式不对。
     */
    val recognized: SignCode? get() = SignCode.parse(input)

    /** 按钮是否可点：非空、可识别、且当前没有正在提交的请求。 */
    val canSubmit: Boolean get() = !signing && input.isNotBlank() && recognized != null
}

/**
 * 「手动打卡」页面的 ViewModel。
 *
 * ## 为什么单独一个页面 / 单独一个 ViewModel
 * 手动打卡与讲座**没有任何耦合**：签到通道就是普通课程那条
 * （`stu_scan_sign.action` 只认 7 位 `courseSchedId` 或 32 位 `timeTableId`），
 * 用户的使用场景是「现场读到班牌上的编号，立刻要打卡」——
 * 这时候再从讲座页里翻出一个折叠卡片，路径既长又没有道理。
 *
 * 早先把入口塞在讲座页内，是把它当成「讲座专用」的妥协；现在它独立成页，
 * 因此本类**不依赖**讲座相关的任何数据（不抓通知、不读缓存），
 * 打开即可用、断网也能正常报错。
 */
class ManualSignViewModel(app: Application) : AndroidViewModel(app) {

    private val app: QingxinApp get() = getApplication()

    private val _ui = MutableStateFlow(ManualSignUiState())
    val ui: StateFlow<ManualSignUiState> = _ui.asStateFlow()

    /**
     * 输入变化。只更新输入与提示，**不**清掉上一次的结果 ——
     * 用户常常是「签到失败 → 改一位数字 → 再点一次」，
     * 一改字就把失败原因抹掉反而让人失去参照。
     */
    fun onInputChange(text: String) {
        _ui.update { it.copy(input = text, hint = hintFor(text)) }
    }

    fun clear() {
        _ui.update { ManualSignUiState() }
    }

    /** 提交签到。 */
    fun sign() {
        val code = SignCode.parse(_ui.value.input)
        if (code == null) {
            _ui.update { it.copy(error = "格式不对：需要 7 位数字，或 32 位十六进制标识", success = "") }
            return
        }
        if (!app.isReady) {
            _ui.update { it.copy(error = "应用尚未初始化完成，请稍后重试", success = "") }
            return
        }
        // 已在提交中就不再发第二次：签到接口对重复提交并不友好，
        // 双请求可能让服务端把第二次判成异常。
        if (_ui.value.signing) return

        viewModelScope.launch {
            _ui.update { it.copy(signing = true, error = "", success = "") }
            val course = Course(
                id = code.courseSchedId,
                uuid = code.timeTableId,
                // 手动录入没有名称与时间可言：这些字段只用于展示，
                // 而本条记录不会进入任何列表，因此留空即可。
                name = "手动打卡",
                teacher = "",
                beginTime = "",
                endTime = "",
                day = "",
                signed = false,
            )
            try {
                val result = app.attendanceRepository.signOneClick(course)
                val ok = result.outcome == SignOutcome.SIGNED
                _ui.update {
                    it.copy(
                        signing = false,
                        success = if (ok) result.message.ifBlank { "打卡成功" } else "",
                        error = if (ok) "" else result.message.ifBlank { outcomeMessage(result.outcome) },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                val loginExpired = e.code == "LOGIN_EXPIRED" || e.code == "LOGIN_REJECTED"
                _ui.update {
                    it.copy(
                        signing = false,
                        error = if (loginExpired) "登录已过期，请返回首页重新登录" else e.message ?: e.code,
                    )
                }
            } catch (e: Exception) {
                _ui.update { it.copy(signing = false, error = e.message ?: "打卡失败") }
            }
        }
    }

    /** 实时识别提示：让用户在点之前就知道这串编号认不认。 */
    private fun hintFor(text: String): String = when {
        text.isBlank() -> ""
        SignCode.parse(text) != null -> "已识别为${SignCode.parse(text)?.label}"
        else -> "未识别：需要 7 位数字，或 32 位十六进制标识"
    }

    private fun outcomeMessage(outcome: SignOutcome): String = when (outcome) {
        SignOutcome.SIGNED -> "打卡成功"
        SignOutcome.QR_EXPIRED -> "二维码已过期，请稍后重试"
        SignOutcome.OUTSIDE_SIGN_WINDOW -> "当前不在签到时间内"
        SignOutcome.SIGN_RESULT_UNKNOWN -> "签到结果未知，请刷新后确认"
    }
}
