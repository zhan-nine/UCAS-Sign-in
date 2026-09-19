package com.ucas.qingxin.signin.attendance

/**
 * 手动打卡时用户录入的编号。
 *
 * 纯函数、不触网，因此可以离线穷尽单测（见 `SignCodesTest`）。
 * 放在 `attendance` 包是因为它描述的正是**签到接口的标识契约**：
 * `stu_scan_sign.action` 只认这两种形态，别的输入一律不该发请求。
 *
 * 公开（而非 `internal`）：它是 `ManualSignUiState.recognized` 的类型，
 * 而后者是公开的界面状态；Kotlin 不允许公开属性暴露 internal 类型。
 * 它本身只是一个「用户输入被识别成什么」的值对象，不含任何端点信息。
 */
data class SignCode(
    /** 7 位节次编号（`courseSchedId`）；不是这种形态时为空串。 */
    val courseSchedId: String,
    /** 32 位十六进制二维码标识（`timeTableId`）；不是这种形态时为空串。 */
    val timeTableId: String,
) {
    /** 界面提示用的人类可读名称。 */
    val label: String
        get() = when {
            courseSchedId.isNotEmpty() -> "7 位节次编号"
            timeTableId.isNotEmpty() -> "32 位二维码标识"
            else -> ""
        }

    companion object {
        /**
         * 解析用户录入的编号；不合法时返回 `null`。
         *
         * ## 为什么先本地判格式、而不是直接发给服务器
         * 一是没有意义：签到接口对不符格式的编号只会返回一个含糊的失败，
         * 用户看到「签到失败」却不知道是自己输错了；
         * 二是**不能**把任意输入往签到接口上送 —— 那等于让一个输入框可以触发
         * 任意编号的签到请求，且失败原因还不可辨。因此格式不合格**直接拦下**。
         *
         * ## 两处宽容都是必需的
         * - 首尾空白：从班牌 / 截图复制来的编号几乎必然带空白或换行；
         * - 连字符：`timeTableId` 在不少场合是按 UUID 形态（8-4-4-4-12）展示的，
         *   而签到通道要的是去掉连字符的 32 位十六进制（与 `Course.uuid` 的口径一致）。
         */
        fun parse(raw: String): SignCode? {
            val text = raw.trim()
            if (text.isEmpty()) return null
            if (SEVEN_DIGITS.matches(text)) return SignCode(courseSchedId = text, timeTableId = "")

            val compact = text.replace("-", "")
            if (HEX32.matches(compact)) return SignCode(courseSchedId = "", timeTableId = compact)
            return null
        }

        private val SEVEN_DIGITS = Regex("""\d{7}""")
        private val HEX32 = Regex("""[0-9a-fA-F]{32}""")
    }
}
