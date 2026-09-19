package com.ucas.qingxin.signin.util

import java.time.LocalDate

/**
 * 「输入一个日期」的宽容解析与展示。
 *
 * ## 为什么需要单独一层，而不直接 `LocalDate.parse(text)`
 * 用户不会按 `ISO_LOCAL_DATE` 打字。实测最常见的几种输入是：
 * - `2026-09-20` / `2026/9/20` / `2026.9.20`（分隔符随手打）
 * - `20260920`（从别处复制粘贴的紧凑写法，学校接口本身就是这个形态）
 * - `2026年9月20日`（中文书写习惯）
 * - `9月20日` / `9-20`（只看近期，懒得打年份 → 补当年）
 *
 * 另外中文输入法会产出**全角**数字与连字符（`２０２６－０９－２０`），
 * 直接扔给 `LocalDate.parse` 只会得到一句莫名其妙的异常。
 *
 * ## 严格性
 * 形态一律用 `matchEntire`（而不是 `find`）：宁可明确报「格式不对」，也不要从
 * `abc2026-09-20xyz` 里抠出一个日期然后默默接受 —— 打错字的场景里，猜一个日期的
 * 代价（看到错的那天课表）比让用户重输一次大。
 *
 * 唯一的例外是**时间尾巴**（[TIME_TAIL]）：从聊天记录里粘贴
 * `2026-09-20 08:00` 进来是合理用法，把整串判为非法反而像 bug。
 *
 * 日期本身走 [LocalDate.of]，因此 `2026-02-30` 这类不存在的日子会被拒绝，
 * 而不是被悄悄顺延到 3 月 2 日。
 */
object DateInput {

    /**
     * 年份允许范围。
     *
     * 下界取 2000、上界取 2100：学校课表不可能落在这之外，而放宽到
     * `LocalDate` 的极限（±999999999 年）只会让「手滑多打几位」也被接受，
     * 然后拿去发一次注定无结果的请求。
     */
    private const val MIN_YEAR = 2000
    private const val MAX_YEAR = 2100

    /** `20260920` —— 学校接口的紧凑形态，必须先于 [SEPARATED] 尝试。 */
    private val COMPACT = Regex("""(\d{4})(\d{2})(\d{2})""")

    /** `2026-09-20` / `2026/9/20` / `2026.9.20` / `2026年9月20日`（尾缀「日」可选）。 */
    private val SEPARATED = Regex("""(\d{4})[-/.年](\d{1,2})[-/.月](\d{1,2})日?""")

    /** `9-20` / `9/20` / `9月20日` —— 缺年份，由调用方传入的「今天」补上。 */
    private val MONTH_DAY = Regex("""(\d{1,2})[-/.月](\d{1,2})日?""")

    /**
     * 时间尾巴：` 08:00` / `T08:00:00` / `T08:00:00.000`。
     *
     * 必须在**去掉空白之前**剥掉，否则分隔用的空格会先消失，
     * `2026-09-20` 与 `08:00` 就粘成一个再也切不开的串。
     */
    private val TIME_TAIL = Regex("""[T\s]\d{1,2}[:：]\d{2}(?::\d{2})?(?:\.\d+)?""")

    /**
     * 全角 → 半角。
     *
     * U+FF01–U+FF5E 是「全角 ASCII」区段，整体减去 0xFEE0 即得对应半角字符，
     * 因此用区段而不是逐个字符列举 —— 漏掉一个（例如全角减号）就会出现
     * 「看起来一样却解析不了」的输入。
     */
    private val FULL_WIDTH_ASCII = Regex("""[\uFF01-\uFF5E]""")

    /** 输入末尾常被带上来的标点（`。` `，` 等）。 */
    private val TRAILING_PUNCT = Regex("""[。．，,、;；]+$""")

    /**
     * 解析用户输入。
     *
     * @param today 用于补全省略的年份。
     * @return 解析失败（形态不符 / 日期不存在 / 年份越界 / 空串）时返回 null，
     *   由界面负责提示 —— 不替用户猜一个日期。
     */
    fun parse(raw: String, today: LocalDate): LocalDate? {
        val text = normalize(raw)
        if (text.isEmpty()) return null

        // 顺序有讲究：紧凑形态（8 位纯数字）必须先试，否则 `20260920` 会被
        // 后面的「缺年份」规则按 `20`+`9`+`20` 误读。
        COMPACT.matchEntire(text)?.let { m ->
            return build(m.groupValues[1], m.groupValues[2], m.groupValues[3])
        }
        SEPARATED.matchEntire(text)?.let { m ->
            return build(m.groupValues[1], m.groupValues[2], m.groupValues[3])
        }
        MONTH_DAY.matchEntire(text)?.let { m ->
            return build(today.year.toString(), m.groupValues[1], m.groupValues[2])
        }
        return null
    }

    /** `yyyy-MM-dd`，输入框与列表标题统一用它，避免同一日期出现第二种写法。 */
    fun display(date: LocalDate): String = date.toString()

    /**
     * 中文星期（`周一` … `周日`）。
     *
     * 用固定数组而不是 `TextStyle.FULL` + `Locale.CHINESE`：后者的输出依赖设备
     * 语言与 `java.time` 的语言数据，同一份代码在不同 ROM 上会得到
     * 「星期一 / 周一 / Mon」，而课表这种扫一眼就要看懂的界面不该有这种不确定性。
     */
    fun weekdayLabel(date: LocalDate): String = WEEKDAYS[date.dayOfWeek.value - 1]

    /** `9月20日 周日` —— 列表标题用。 */
    fun prettyLabel(date: LocalDate): String =
        "${date.monthValue}月${date.dayOfMonth}日 ${weekdayLabel(date)}"

    /**
     * 归一化：剥时间尾巴 → 全角转半角 → 去空白 → 去尾标点。
     *
     * 空白是**全部删掉**而不是只 trim 两端：`2026 - 09 - 20` 与 `2026-09-20`
     * 应当等价，而保留内部空格就得让每条正则都容忍空白，可读性会明显变差。
     */
    private fun normalize(raw: String): String {
        val noTime = TIME_TAIL.replace(raw.trim(), "")
        val halfWidth = FULL_WIDTH_ASCII.replace(noTime) { m ->
            (m.value[0].code - 0xFEE0).toChar().toString()
        }
        val noSpace = halfWidth.filterNot { it.isWhitespace() }
        return TRAILING_PUNCT.replace(noSpace, "")
    }

    /** 组装并按 [MIN_YEAR]..[MAX_YEAR] 校验；任何不合法（含 2 月 30 日）都返回 null。 */
    private fun build(yearRaw: String, monthRaw: String, dayRaw: String): LocalDate? {
        val year = yearRaw.toIntOrNull() ?: return null
        val month = monthRaw.toIntOrNull() ?: return null
        val day = dayRaw.toIntOrNull() ?: return null
        if (year !in MIN_YEAR..MAX_YEAR) return null
        return runCatching { LocalDate.of(year, month, day) }.getOrNull()
    }

    private val WEEKDAYS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
}
