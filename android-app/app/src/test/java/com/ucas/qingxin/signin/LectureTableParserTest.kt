package com.ucas.qingxin.signin

import com.ucas.qingxin.signin.lecture.LectureEventKind
import com.ucas.qingxin.signin.lecture.LectureTableParser
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 讲座时间表解析器的用例。
 *
 * ## 这些样例是从哪来的
 * 表头与时间格式取自**上游 [UCAS-Desktop](https://github.com/zhangzhendan-Berkeley/UCAS-Desktop)
 * 读取同一张表时的实现**（`patches/lecture-portal.ts` 的 `readScienceSchedule` 与
 * `parseScheduleTime`）：它明确要求表头含「讲座时间」「讲座名称」，并用
 * `(\d{4}-\d{2}-\d{2})\s+(\d{1,2}:\d{2})\s*[-~～—至]\s*(\d{1,2}:\d{2})` 解析时间。
 * 这些都是**别人的实测结果**，不是本项目的猜测，因此适合当作回归基线。
 *
 * 本项目**不能**直接抓一份真实报文放进仓库：那张表在登录之后，而仓库是公开的
 * （端点与端口不进仓库，见 `docs/lecture-signin-research.md`）。
 * 因此这里用等价结构的合成报文，并把「哪些形态是真实出现过的」标注在用例上。
 */
class LectureTableParserTest {

    /** 造一段 [LectureTableParser.EXTRACT_SCRIPT] 返回值形态的 JSON。 */
    private fun table(heads: List<String>, vararg rows: List<String>): String {
        val h = heads.joinToString(",") { "\"$it\"" }
        val r = rows.joinToString(",") { row ->
            "[" + row.joinToString(",") { "\"$it\"" } + "]"
        }
        return """[{"h":[$h],"r":[$r]}]"""
    }

    private val HUMAN = LectureEventKind.HUMANITY

    // ---------- 正常路径 ----------

    @Test
    fun `解析标准三列并拆分日期与起止时刻`() {
        val json = table(
            listOf("讲座时间", "讲座名称", "讲座地点"),
            listOf("2026-09-16 15:30-17:30", "夯实科技发展的文化基础", "雁栖湖 - 教三楼204"),
        )
        val events = LectureTableParser.parse(json, HUMAN)
        assertEquals(1, events.size)
        val e = events.single()
        assertEquals("2026-09-16", e.date)
        assertEquals("15:30", e.startTime)
        assertEquals("17:30", e.endTime)
        assertEquals("夯实科技发展的文化基础", e.title)
        assertEquals("雁栖湖 - 教三楼204", e.location)
        assertEquals("2026-09-16 15:30", e.startsAt)
        assertEquals("09-16 15:30-17:30", e.timeLabel)
    }

    /**
     * 分隔符不统一是**上游实现里显式覆盖过的**：它同时接受 `-` `~` `～` `—` `至`。
     * 本项目按「一段日期 + 两个时刻」解析，因此这些形态自然都能过 ——
     * 这个用例就是把这个等价性固定下来。
     */
    @Test
    fun `各种时间分隔符都能解析`() {
        val separators = listOf("-", "~", "～", "—", "至")
        for (sep in separators) {
            val json = table(
                listOf("讲座时间", "讲座名称"),
                listOf("2026-09-16 15:30${sep}17:30", "测试讲座"),
            )
            val e = LectureTableParser.parse(json, HUMAN).single()
            assertEquals("分隔符 $sep 的日期", "2026-09-16", e.date)
            assertEquals("分隔符 $sep 的开始", "15:30", e.startTime)
            assertEquals("分隔符 $sep 的结束", "17:30", e.endTime)
        }
    }

    /** 只写开始时间的行也要留下：为了拿到结束时刻而丢掉整场讲座不划算。 */
    @Test
    fun `只有开始时间也能解析`() {
        val json = table(
            listOf("讲座时间", "讲座名称"),
            listOf("2026-09-16 15:30", "半场讲座"),
        )
        val e = LectureTableParser.parse(json, HUMAN).single()
        assertEquals("15:30", e.startTime)
        assertEquals("", e.endTime)
        assertEquals("09-16 15:30", e.timeLabel)
    }

    /** 单数字的时与分：上游的 `\d{1,2}:\d{2}` 明确允许。 */
    @Test
    fun `个位小时也能解析`() {
        val json = table(
            listOf("讲座时间", "讲座名称"),
            listOf("2026-09-16 9:05-9:55", "早课讲座"),
        )
        val e = LectureTableParser.parse(json, HUMAN).single()
        assertEquals("9:05", e.startTime)
        assertEquals("9:55", e.endTime)
    }

    @Test
    fun `地点列缺失时不影响其余字段`() {
        val json = table(
            listOf("讲座时间", "讲座名称", "备注"),
            listOf("2026-09-16 15:30-17:30", "无地点列讲座", "线上"),
        )
        val e = LectureTableParser.parse(json, HUMAN).single()
        assertEquals("无地点列讲座", e.title)
        assertEquals("", e.location)
        assertEquals("2026-09-16", e.date)
    }

    /** 表头顺序不固定：按**表头名**定位列，而不是按位置。 */
    @Test
    fun `表头顺序变化仍能正确取值`() {
        val json = table(
            listOf("讲座地点", "讲座名称", "讲座时间"),
            listOf("中关村 - 教一楼", "调序讲座", "2026-09-20 18:00-20:00"),
        )
        val e = LectureTableParser.parse(json, HUMAN).single()
        assertEquals("调序讲座", e.title)
        assertEquals("中关村 - 教一楼", e.location)
        assertEquals("2026-09-20", e.date)
    }

    // ---------- 认表 ----------

    /** 页面上常有导航 / 分页用的小表格，必须按表头认出**正确的那一张**。 */
    @Test
    fun `跳过不含讲座表头的表`() {
        val nav = """[{"h":["序号","名称"],"r":[["1","首页"]]}]"""
        val body = nav.removeSuffix("]") + """,""" +
            """{"h":["讲座时间","讲座名称"],"r":[["2026-09-16 15:30-17:30","真讲座"]]}]"""
        val events = LectureTableParser.parse(body, HUMAN)
        assertEquals(1, events.size)
        assertEquals("真讲座", events.single().title)
    }

    /**
     * 「页面上没有这张表」是**未登录**的典型表现，此时必须返回空列表，
     * 让调用方给出「请先登录」而不是「今天没有讲座」。
     */
    @Test
    fun `页面上没有讲座表时返回空列表`() {
        assertEquals(emptyList<Any>(), LectureTableParser.parse("""[{"h":["学号"],"r":[["2026"] ]}]""", HUMAN))
        assertEquals(emptyList<Any>(), LectureTableParser.parse("[]", HUMAN))
        assertEquals(emptyList<Any>(), LectureTableParser.parse("", HUMAN))
        assertEquals(emptyList<Any>(), LectureTableParser.parse(null, HUMAN))
    }

    /**
     * 认表只看**表头**，不看表头里的数字：`optString` 会把 JSON 里的数字
     * 变成字符串，而表头若写成数字列（如「1」）不应被误当成「讲座时间」。
     */
    @Test
    fun `表头需要完整匹配才算`() {
        val json = table(
            listOf("时间", "名称"),
            listOf("2026-09-16 15:30-17:30", "缺字的表头"),
        )
        assertTrue(LectureTableParser.parse(json, HUMAN).isEmpty())
    }

    /** 名称为空的行不是讲座（实测表尾会有空行/合计行）。 */
    @Test
    fun `跳过名称为空的行`() {
        val json = table(
            listOf("讲座时间", "讲座名称"),
            listOf("2026-09-16 15:30-17:30", ""),
            listOf("", ""),
            listOf("2026-09-17 15:30-17:30", "有效讲座"),
        )
        val events = LectureTableParser.parse(json, HUMAN)
        assertEquals(1, events.size)
        assertEquals("有效讲座", events.single().title)
    }

    /** 同一场讲座在多个会场是多行，场地不同 → **必须都留下**（用户要靠场地决定去哪）。 */
    @Test
    fun `不同会场各自成行`() {
        val json = table(
            listOf("讲座时间", "讲座名称", "讲座地点"),
            listOf("2026-09-16 15:30-17:30", "M1167雁栖湖会场：夯实科技发展的文化基础", "雁栖湖 - 教三楼204"),
            listOf("2026-09-16 15:30-17:30", "M1167中关村会场：夯实科技发展的文化基础", "中关村 - 教学楼"),
        )
        val events = LectureTableParser.parse(json, HUMAN)
        assertEquals(2, events.size)
        assertEquals(setOf("雁栖湖 - 教三楼204", "中关村 - 教学楼"), events.map { it.location }.toSet())
    }

    /** 逐字重复的行要去掉（上游偶有重复推送同一行）。 */
    @Test
    fun `完全相同的重复行合并为一条`() {
        val row = listOf("2026-09-16 15:30-17:30", "重复讲座", "雁栖湖")
        val json = table(listOf("讲座时间", "讲座名称", "讲座地点"), row, row)
        assertEquals(1, LectureTableParser.parse(json, HUMAN).size)
    }

    // ---------- 异常数据 ----------

    /**
     * 不存在的日期必须**整行降级为无日期**，而不是保留一个会被拿去比较的错日期。
     *
     * 这一条很关键：`2026-02-30` 若被留下，它会和「今天」做字典序比较，
     * 于是一场凭空捏造的讲座会永久停在「即将开始」里。
     */
    @Test
    fun `不存在的日期被丢弃`() {
        val json = table(
            listOf("讲座时间", "讲座名称"),
            listOf("2026-02-30 15:30-17:30", "不存在的日期"),
        )
        val e = LectureTableParser.parse(json, HUMAN).single()
        assertEquals("", e.date)
        assertEquals("", e.startsAt)
        assertEquals("", e.timeLabel)
    }

    /** 时间列完全为空：条目仍然保留（标题本身有价值），只是没有时间。 */
    @Test
    fun `时间列缺失时条目仍保留`() {
        val json = table(
            listOf("讲座时间", "讲座名称", "讲座地点"),
            listOf("", "待定讲座", "雁栖湖"),
        )
        val e = LectureTableParser.parse(json, HUMAN).single()
        assertEquals("待定讲座", e.title)
        assertFalse(e.startsAt.isNotEmpty())
    }

    /** `evaluateJavascript` 对字符串返回值会再包一层引号；本脚本返回数组，但解析器要耐受。 */
    @Test
    fun `被引号包裹的 JSON 也能解析`() {
        val inner = table(
            listOf("讲座时间", "讲座名称"),
            listOf("2026-09-16 15:30-17:30", "转义讲座"),
        )
        val quoted = "\"" + inner.replace("\"", "\\\"") + "\""
        val e = LectureTableParser.parse(quoted, HUMAN).single()
        assertEquals("转义讲座", e.title)
    }

    /** 报文损坏时返回空列表而不是抛异常：解析失败不该让整页崩掉。 */
    @Test
    fun `非法 JSON 返回空列表`() {
        assertTrue(LectureTableParser.parse("不是 JSON", HUMAN).isEmpty())
        assertTrue(LectureTableParser.parse("""{"h":1}""", HUMAN).isEmpty())
    }

    // ---------- 结束判定 ----------

    /**
     * 结束判定用**结束时刻**而不是日期。
     *
     * 这一条来自一个真实会遇到的场景：讲座当晚 17:30 讲完，
     * 而「今天」这个词要到次日零点才变 —— 只比日期的话，它会一直留在「即将开始」里。
     */
    @Test
    fun `结束判定精确到时刻`() {
        val json = table(
            listOf("讲座时间", "讲座名称"),
            listOf("2026-09-16 15:30-17:30", "讲座"),
        )
        val e = LectureTableParser.parse(json, HUMAN).single()

        assertFalse(
            "开讲前不该算结束",
            LectureTableParser.isEnded(e, LocalDateTime.of(2026, 9, 16, 15, 29)),
        )
        assertFalse(
            "讲的过程中不该算结束",
            LectureTableParser.isEnded(e, LocalDateTime.of(2026, 9, 16, 16, 0)),
        )
        assertTrue(
            "结束时刻之后算结束",
            LectureTableParser.isEnded(e, LocalDateTime.of(2026, 9, 16, 17, 31)),
        )
        assertTrue(
            "次日当然算结束",
            LectureTableParser.isEnded(e, LocalDateTime.of(2026, 9, 17, 9, 0)),
        )
    }

    /**
     * 没有结束时刻时取当日 23:59 —— **刻意保守**：宁可把可能已经讲完的讲座多留一天，
     * 也不要让它提前消失（用户白跑一趟的代价远大于列表里多一条）。
     */
    @Test
    fun `缺结束时刻时保守地按当日末处理`() {
        val json = table(
            listOf("讲座时间", "讲座名称"),
            listOf("2026-09-16 15:30", "无结束时刻"),
        )
        val e = LectureTableParser.parse(json, HUMAN).single()
        assertFalse(
            "当天晚上仍不该算结束",
            LectureTableParser.isEnded(e, LocalDateTime.of(2026, 9, 16, 20, 0)),
        )
        assertTrue(
            "次日算结束",
            LectureTableParser.isEnded(e, LocalDateTime.of(2026, 9, 17, 0, 1)),
        )
    }

    /** 没有日期就**不能**判定结束：宁可把它留在「即将开始」，也不要凭空说它结束了。 */
    @Test
    fun `没有日期时不判定结束`() {
        val json = table(
            listOf("讲座时间", "讲座名称"),
            listOf("", "待定讲座"),
        )
        val e = LectureTableParser.parse(json, HUMAN).single()
        assertFalse(LectureTableParser.isEnded(e, LocalDateTime.of(2030, 1, 1, 0, 0)))
    }

    // ---------- 稳定 key ----------

    /**
     * key 必须**不含列表下标**：时间表的行序会随报名状态变化，
     * 用下标会让 `LazyColumn` 的 key 每次刷新整体错位（甚至重复 key 闪退）。
     */
    @Test
    fun `key 由内容决定且稳定`() {
        val json = table(
            listOf("讲座时间", "讲座名称", "讲座地点"),
            listOf("2026-09-16 15:30-17:30", "讲座甲", "雁栖湖"),
            listOf("2026-09-16 15:30-17:30", "讲座乙", "中关村"),
        )
        val first = LectureTableParser.parse(json, HUMAN)
        // 把行序颠倒后重新解析：同一条讲座必须得到同一个 key。
        val reordered = table(
            listOf("讲座时间", "讲座名称", "讲座地点"),
            listOf("2026-09-16 15:30-17:30", "讲座乙", "中关村"),
            listOf("2026-09-16 15:30-17:30", "讲座甲", "雁栖湖"),
        )
        val second = LectureTableParser.parse(reordered, HUMAN)
        assertEquals(
            first.associate { it.title to it.key },
            second.associate { it.title to it.key },
        )
    }
}
