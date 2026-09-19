package com.ucas.qingxin.signin.lecture

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import org.json.JSONArray

/**
 * 讲座预约系统里的一场讲座（**带确切时间与场地**）。
 *
 * ## 这个模型为什么值得单独存在
 * 本项目此前只有 [LectureNotice]（学院网站的通知）：它有标题、期次号和**发布日期**，
 * 但没有讲座举办时间与场地 —— 实测预告正文多为海报图片，日期印在图上。
 * 缺了日期，「这场讲完了没」就只能靠「有没有发报道」间接推断（[LectureSessions]）。
 *
 * 预约系统的讲座时间表补上了那两列：`讲座时间`（`2026-09-16 15:30-17:30`）与 `讲座地点`。
 * 有了它，「按日期判定往期」才第一次成为**有证据**的判断，而不是猜。
 *
 * ## 边界（必须诚实写下来）
 * 1. 它**不是签到通道**：这里的讲座编号与 iClass 的 7 位 `courseSchedId`
 *    是两套编号，不能互换（上游 UCAS-Desktop 的 `docs/讲座编号验证.md` 与
 *    本项目 `docs/lecture-signin-research.md` §4 各自独立得出同一结论）。
 * 2. 它**只在登录后可读**，因此不能作为后台无人值守的数据源；
 *    后台通知仍由 [LectureNoticeService]（学院网站 RSS）承担。
 */
data class LectureEvent(
    /** 讲座名称，已去掉多余空白。 */
    val title: String,
    /** 讲座日期（`yyyy-MM-dd`）；解析不出时为空串。 */
    val date: String,
    /** 开始时刻（`HH:mm`）；解析不出时为空串。 */
    val startTime: String,
    /** 结束时刻（`HH:mm`）；缺失时为空串（时间表里偶有只写开始时间的行）。 */
    val endTime: String,
    /** 场地；缺失时为空串。 */
    val location: String,
    val kind: LectureEventKind,
) {
    /**
     * 排序与「是否已结束」共用的时间戳（`yyyy-MM-dd HH:mm`）；无日期时为空串。
     *
     * 刻意用可排序的定长字符串而不是 `LocalDateTime`：它会被写进缓存、
     * 也会参与界面的稳定 key，保持成一个**字面量**比序列化一个对象简单得多，
     * 而 `yyyy-MM-dd HH:mm` 的字典序恰好等于时间序。
     */
    val startsAt: String
        get() = when {
            date.isBlank() -> ""
            startTime.isBlank() -> "$date 00:00"
            else -> "$date $startTime"
        }

    /**
     * 稳定 key：同一场讲座在多次读取、进程重启后必须得到同一个值。
     *
     * 用「名称 + 时间 + 场地」而不是列表下标：时间表的行序会随报名状态变化，
     * 用下标会让 `LazyColumn` 的 key 每刷新一次就整体错位。
     */
    val key: String
        get() = "e_" + Integer.toHexString((title + "|" + startsAt + "|" + location).hashCode())

    /** 界面上显示的时间标签，如 `09-16 15:30-17:30`；无日期时为空串。 */
    val timeLabel: String
        get() {
            if (date.isBlank()) return ""
            val md = date.substring(5).replace('-', '-')
            val span = when {
                startTime.isBlank() -> ""
                endTime.isBlank() -> " $startTime"
                else -> " $startTime-$endTime"
            }
            return md + span
        }
}

/** 讲座所属的栏目。**来源即类型**，与 [LectureType] 同理，不需要关键词分类。 */
enum class LectureEventKind(val label: String) {
    HUMANITY("人文讲座"),
    SCIENCE("科学前沿讲座"),
}

/**
 * 讲座时间表的解析器（**纯函数，不触网**）。
 *
 * ## 为什么和 [EXTRACT_SCRIPT] 放在同一个文件里
 * 两者是一对**契约**：脚本负责「把 DOM 里的表格搬出来」，本对象负责「读懂它」。
 * 只改其中一个必定出问题，而放在同一文件里能让这种疏忽在阅读时就显形。
 * 反过来，脚本里刻意**不做任何语义判断**（不认表头、不解析时间）——
 * 判断逻辑留在 Kotlin 里，才能用真实报文离线穷尽单测。
 *
 * ## 输入形态
 * [parse] 接受 [EXTRACT_SCRIPT] 的返回值：一个「表格数组」，每项形如
 * `{"h": ["讲座时间", "讲座名称", "讲座地点", ...], "r": [["...", "...", "..."], ...]}`。
 * 之所以搬整张表而不是在 JS 里直接抽字段：真实页面里可能同时存在多个表格
 * （导航、分页、图例），**按表头认表**比按位置认表稳得多，而认表头这件事必须可测。
 */
internal object LectureTableParser {

    /**
     * 在 WebView 里执行的提取脚本。
     *
     * 契约：返回一个数组，每项是 `{h: string[], r: string[][]}`（表头 + 数据行）。
     * **不含任何语义判断**，见类注释。
     *
     * 几处刻意的写法：
     * - 表头取「第一个含 `th` 的行」而不是 `thead`：实测该页面用的是普通表格，
     *   并没有 `thead` 包裹；
     * - 只要 `td` 不为空的行才算数据行，因此表头行不会被重复计入；
     * - 单元格内空白压成单空格：服务端渲染会插入大量缩进与换行，
     *   留着会让「地点」这类字段在界面里看起来像空的。
     */
    const val EXTRACT_SCRIPT: String = """
        (function () {
          var out = [];
          var tables = document.querySelectorAll('table');
          for (var i = 0; i < tables.length; i++) {
            var t = tables[i];
            var headRow = null;
            var rows = t.querySelectorAll('tr');
            for (var j = 0; j < rows.length; j++) {
              if (rows[j].querySelectorAll('th').length > 0) { headRow = rows[j]; break; }
            }
            var heads = [];
            if (headRow) {
              var ths = headRow.querySelectorAll('th');
              for (var k = 0; k < ths.length; k++) {
                heads.push((ths[k].textContent || '').replace(/\s+/g, ' ').trim());
              }
            }
            var data = [];
            for (var m = 0; m < rows.length; m++) {
              var tds = rows[m].querySelectorAll('td');
              if (tds.length === 0) { continue; }
              var cells = [];
              for (var n = 0; n < tds.length; n++) {
                cells.push((tds[n].textContent || '').replace(/\s+/g, ' ').trim());
              }
              data.push(cells);
            }
            out.push({ h: heads, r: data });
          }
          return out;
        })()
    """

    /** 表头里必须有这两列，否则这张表不是讲座时间表。 */
    private const val HEAD_TIME = "讲座时间"
    private const val HEAD_TITLE = "讲座名称"

    private const val HEAD_LOCATION = "讲座地点"

    /**
     * 解析 [EXTRACT_SCRIPT] 的返回值。
     *
     * 找不到合规格的表格时返回空列表（由调用方决定是否算失败）：
     * 这与「表在、但每一行都解析不出来」是不同的状态，前者通常是**还没登录**
     * （跳到了登录页或工作台），后者是页面改版。调用方据此给出不同文案。
     */
    fun parse(raw: String?, kind: LectureEventKind): List<LectureEvent> {
        val text = unquote(raw).trim()
        if (text.isEmpty()) return emptyList()
        val tables = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()

        for (i in 0 until tables.length()) {
            val table = tables.optJSONObject(i) ?: continue
            val heads = table.optJSONArray("h")?.toStringList().orEmpty()
            val timeCol = heads.indexOf(HEAD_TIME)
            val titleCol = heads.indexOf(HEAD_TITLE)
            if (timeCol < 0 || titleCol < 0) continue
            // 地点列是可选的：宁可少一列信息，也不要因为站点去掉这一列就整表读不出。
            val locationCol = heads.indexOf(HEAD_LOCATION)

            val rows = table.optJSONArray("r") ?: continue
            val out = ArrayList<LectureEvent>()
            for (j in 0 until rows.length()) {
                val cells = rows.optJSONArray(j)?.toStringList() ?: continue
                val event = build(
                    rawTime = cells.getOrNull(timeCol).orEmpty(),
                    rawTitle = cells.getOrNull(titleCol).orEmpty(),
                    rawLocation = if (locationCol >= 0) cells.getOrNull(locationCol).orEmpty() else "",
                    kind = kind,
                ) ?: continue
                out += event
            }
            // 认到第一张合规格的表就返回：同一页不会有两张讲座时间表，
            // 而继续扫下去只会把分页/图例里的数字当成讲座。
            return dedupe(out)
        }
        return emptyList()
    }

    private fun build(
        rawTime: String,
        rawTitle: String,
        rawLocation: String,
        kind: LectureEventKind,
    ): LectureEvent? {
        val title = rawTitle.trim()
        // 没有名称的行不是讲座（实测表尾会有合计/空行）。
        if (title.isEmpty()) return null
        val (date, start, end) = splitTime(rawTime)
        return LectureEvent(
            title = title,
            date = date,
            startTime = start,
            endTime = end,
            location = rawLocation.trim(),
            kind = kind,
        )
    }

    /**
     * 把「讲座时间」单元格拆成 `(日期, 开始, 结束)`。
     *
     * 实测形态是 `2026-09-16 15:30-17:30`，但分隔符在页面里并不统一
     * （上游 UCAS-Desktop 的处理里连 `~`、`～`、`—`、`至` 都覆盖了，
     * 说明这些写法都真实出现过），因此按「一段日期 + 两个时刻」来解析，
     * 而不是去匹配某个固定的连接符。
     *
     * 只写开始时间的行也接受（`end` 为空）——时间表里确有这种行，
     * 为了拿到结束时刻而丢掉整场讲座是不划算的。
     */
    fun splitTime(raw: String): Triple<String, String, String> {
        val text = raw.trim()
        if (text.isEmpty()) return Triple("", "", "")
        val date = DATE.find(text)?.value.orEmpty()
        val times = TIME.findAll(text).map { it.value }.toList()
        val start = times.getOrNull(0).orEmpty()
        val end = times.getOrNull(1).orEmpty()
        // 日期不合法（如站点写错成 2026-02-30）时整行降级为「无时间」，
        // 而不是保留一个会被拿去比较、排序的错误日期。
        return Triple(if (validDate(date)) date else "", start, end)
    }

    /**
     * `yyyy-MM-dd` 是否真实存在。
     *
     * 必须显式校验：`LocalDate.parse` 对 `2026-02-30` 会抛异常，
     * 而如果不校验，这个字符串会被拿去和「今天」做字典序比较 —— 结果是
     * 一场不存在的讲座被永久留在「即将开始」里。
     */
    private fun validDate(date: String): Boolean {
        if (date.isEmpty()) return false
        return runCatching { LocalDate.parse(date) }.isSuccess
    }

    /** 去掉上游逐字重复的行（同一场讲座偶有重复行）。 */
    private fun dedupe(list: List<LectureEvent>): List<LectureEvent> {
        if (list.size < 2) return list
        val seen = HashSet<String>(list.size)
        val out = ArrayList<LectureEvent>(list.size)
        for (event in list) {
            // 名称 + 日期 + 开始 + 场地：同一场讲座在**不同分会场**是多行，
            // 那几行的场地不同，必须都留下（用户要靠场地决定去哪）。
            val key = "${event.title}|${event.date}|${event.startTime}|${event.location}"
            if (seen.add(key)) out += event
        }
        return out
    }

    /**
     * 事件是否已经结束。
     *
     * 判据优先用**结束时刻**而不是日期：同一场讲座在当天晚上讲完，
     * 若只比日期，它整个白天都会被归进「即将开始」——那是对的；
     * 但讲完之后到次日零点之前，日期仍然等于今天，它会被继续当成「即将开始」。
     * 因此这里用 `date + endTime`（缺失时退化为当日 23:59）与当前时刻比较。
     */
    fun isEnded(event: LectureEvent, now: LocalDateTime): Boolean {
        if (event.date.isBlank()) return false
        val end = endMoment(event) ?: return false
        return end.isBefore(now)
    }

    /**
     * 讲座结束时刻；无法确定时返回 `null`。
     *
     * 结束时刻缺失时取当日 `23:59`：这是**刻意保守**的选择 ——
     * 宁可把一场可能已经讲完的讲座多留一天，也不要让它提前消失
     * （用户白跑一趟的代价远大于列表里多一条）。
     */
    fun endMoment(event: LectureEvent): LocalDateTime? {
        val date = runCatching { LocalDate.parse(event.date) }.getOrNull() ?: return null
        val time = if (event.endTime.isNotBlank()) {
            runCatching { LocalTime.parse(event.endTime) }.getOrNull()
        } else {
            null
        } ?: LocalTime.of(23, 59)
        return LocalDateTime.of(date, time)
    }

    /**
     * WebView 的 `evaluateJavascript` 会把字符串返回值**再包一层引号**。
     *
     * 本脚本返回的是数组（只有 `JSONArray` 需要，但保留这层处理以防将来改成返回字符串），
     * 因此这里只在「整个串被引号包住」时才剥壳，避免误改正常内容。
     */
    private fun unquote(raw: String?): String {
        val text = raw?.trim().orEmpty()
        if (text.length < 2) return text
        if (!text.startsWith('"') || !text.endsWith('"')) return text
        return runCatching {
            // 用 JSONArray 的解析器做反转义，比自己写 replace 可靠（要处理 \" \\ \n 等）。
            org.json.JSONTokener(text).nextValue() as? String ?: text
        }.getOrDefault(text)
    }

    /** `JSONArray<String>` → `List<String>`（`optString` 会把 null 变成 "null"，因此逐项判空）。 */
    private fun JSONArray.toStringList(): List<String> {
        val out = ArrayList<String>(length())
        for (i in 0 until length()) {
            val value = opt(i)
            out += if (value == null || value == org.json.JSONObject.NULL) "" else value.toString()
        }
        return out
    }

    /** 日期：只认 `yyyy-MM-dd`（页面固定用这个形态，不做多形态猜测）。 */
    private val DATE = Regex("""\d{4}-\d{2}-\d{2}""")

    /** 时刻：`15:30`；兼容写成 `15:30:00` 的情况（取前两段即可）。 */
    private val TIME = Regex("""\b\d{1,2}:\d{2}\b""")
}
