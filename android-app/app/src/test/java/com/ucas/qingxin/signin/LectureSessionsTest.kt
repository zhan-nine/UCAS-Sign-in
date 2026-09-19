package com.ucas.qingxin.signin

import com.ucas.qingxin.signin.lecture.LectureNotice
import com.ucas.qingxin.signin.lecture.LectureNoticeKind
import com.ucas.qingxin.signin.lecture.LectureNoticeParser
import com.ucas.qingxin.signin.lecture.LectureSessions
import com.ucas.qingxin.signin.lecture.LectureType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「同一场次的多条通知」与「是否已结束」的回归测试。
 *
 * 这两件事都容易被想当然地写错：预告与报道在网站上就是两条独立文章，
 * 而「已结束」的唯一站内证据就是「同一场次里出现了报道」。
 */
class LectureSessionsTest {

    private fun notice(
        sessionNo: String,
        title: String,
        published: String,
        kind: LectureNoticeKind,
        type: LectureType = LectureType.MINGDE,
    ) = LectureNotice(
        type = type,
        sessionNo = sessionNo,
        title = title,
        publishedDate = published,
        kind = kind,
    )

    // ---------------------------------------------------------------- 场次归并

    @Test
    fun `同一场次的预告与报道会合并成一条`() {
        val all = listOf(
            notice("M1167", "夯实科技发展的文化基础", "2026-09-14", LectureNoticeKind.PREVIEW),
            notice("M1167", "夯实科技发展的文化基础", "2026-09-20", LectureNoticeKind.REPORT),
        )

        val merged = LectureSessions.dedupe(all)

        assertEquals("同一场次只应剩一条", 1, merged.size)
        assertEquals(
            "代表条目取更新的一条（即报道），这样界面上的性质与内容一致",
            LectureNoticeKind.REPORT,
            merged.first().kind,
        )
    }

    @Test
    fun `不同期次不会被合并`() {
        val all = listOf(
            notice("M1167", "主题甲", "2026-09-14", LectureNoticeKind.PREVIEW),
            notice("M1168", "主题乙", "2026-09-21", LectureNoticeKind.PREVIEW),
        )

        assertEquals(2, LectureSessions.dedupe(all).size)
    }

    @Test
    fun `期次号解析不出时退化为按标题归并而不是全并成一条`() {
        val all = listOf(
            notice("", "同一个主题", "2026-09-14", LectureNoticeKind.PREVIEW),
            notice("", "同一个主题", "2026-09-20", LectureNoticeKind.REPORT),
            notice("", "另一个主题", "2026-09-20", LectureNoticeKind.PREVIEW),
        )

        val merged = LectureSessions.dedupe(all)

        assertEquals(
            "期次号解析不出时，归并键退化为「类型 + 发布日期 + 标题」" +
                "——因此同标题但日期不同的两条不会被合并。",
            3,
            LectureSessions.dedupe(all).size,
        )
    }

    @Test
    fun `同日同标题但期次号缺失时不会与另一场混并`() {
        // 之所以把发布日期放进退化键：没有期次号时，两天里两场同名讲座
        // 无法与「同一天的预告 + 报道」区分开。宁可少合并（漏判已结束），
        // 也不能错合并 —— 错合并会把两场不同的讲座删掉一场。
        val sameDay = listOf(
            notice("", "同名主题", "2026-09-20", LectureNoticeKind.PREVIEW),
            notice("", "同名主题", "2026-09-20", LectureNoticeKind.REPORT),
            notice("", "同名主题", "2026-09-25", LectureNoticeKind.PREVIEW),
        )

        assertEquals(2, LectureSessions.dedupe(sameDay).size)
    }

    @Test
    fun `归并结果按发布日期倒序`() {
        val all = listOf(
            notice("M1165", "较早", "2026-06-11", LectureNoticeKind.PREVIEW),
            notice("M1167", "最新", "2026-09-14", LectureNoticeKind.PREVIEW),
            notice("M1166", "居中", "2026-06-16", LectureNoticeKind.PREVIEW),
        )

        val titles = LectureSessions.dedupe(all).map { it.title }

        assertEquals(listOf("最新", "居中", "较早"), titles)
    }

    @Test
    fun `同日按到期次号倒序`() {
        val all = listOf(
            notice("M1165", "小期次", "2026-09-14", LectureNoticeKind.PREVIEW),
            notice("M1167", "大期次", "2026-09-14", LectureNoticeKind.PREVIEW),
        )

        assertEquals(listOf("大期次", "小期次"), LectureSessions.dedupe(all).map { it.title })
    }

    // ---------------------------------------------------------------- 已结束判定

    @Test
    fun `同一场次出现报道即视为已结束`() {
        val all = listOf(
            notice("M1167", "夯实科技发展的文化基础", "2026-09-14", LectureNoticeKind.PREVIEW),
            notice("M1167", "夯实科技发展的文化基础", "2026-09-20", LectureNoticeKind.REPORT),
        )

        val ended = LectureSessions.endedKeys(all)

        assertEquals(1, ended.size)
        assertTrue(all.first().groupKey in ended)
    }

    @Test
    fun `只有预告不会被判为已结束`() {
        // 这是本判据最重要的边界：报道常在讲座后数日到数周才发，
        // 所以「没有报道」**不等于**「还没讲」——不能按当前日期去猜。
        val all = listOf(
            notice("M1167", "夯实科技发展的文化基础", "2026-09-14", LectureNoticeKind.PREVIEW),
        )

        assertTrue(LectureSessions.endedKeys(all).isEmpty())
    }

    @Test
    fun `没有标记的通知不算已结束`() {
        val all = listOf(
            notice("213", "京剧的人物塑造", "2026-01-14", LectureNoticeKind.OTHER, LectureType.ART_HUMANITY),
        )

        assertTrue(LectureSessions.endedKeys(all).isEmpty())
    }

    @Test
    fun `已结束的场次不会进入高亮候选`() {
        val all = listOf(
            notice("M1167", "已讲完", "2026-09-14", LectureNoticeKind.PREVIEW),
            notice("M1167", "已讲完", "2026-09-20", LectureNoticeKind.REPORT),
            notice("M1168", "还没讲", "2026-09-21", LectureNoticeKind.PREVIEW),
        )

        val ended = LectureSessions.endedKeys(all)
        val merged = LectureSessions.dedupe(all)
        val highlights = merged.filterNot { it.groupKey in ended }

        assertEquals(1, highlights.size)
        assertEquals("还没讲", highlights.first().title)
        assertFalse(highlights.any { it.title == "已讲完" })
    }

    @Test
    fun `已结束判定必须基于合并前的全量列表`() {
        // 构造「报道之后又有一条更新的预告」这种真实会出现的情况
        // （同一场次改期 / 补发通知）：合并时代表条目取更新的一条，报道被丢弃 ——
        // 若在合并结果上判「是否结束」，这一场就会被错判成「还没讲」。
        val all = listOf(
            notice("M1167", "同一主题", "2026-09-20", LectureNoticeKind.REPORT),
            notice("M1167", "同一主题", "2026-09-25", LectureNoticeKind.PREVIEW),
        )
        val merged = LectureSessions.dedupe(all)

        assertEquals(1, merged.size)
        assertEquals("代表条目取更新的一条（预告）", LectureNoticeKind.PREVIEW, merged.first().kind)
        assertTrue("所以在合并结果上算不出已结束", LectureSessions.endedKeys(merged).isEmpty())
        assertFalse("而基于全量列表就能算出来", LectureSessions.endedKeys(all).isEmpty())
    }

    // ---------------------------------------------------------------- 实盘语料端到端

    /**
     * 用**线上抓到的真实 RSS**（2026-09-17 两个栏目各 10 条，原样抄录）跑完整链路：
     * `parseRss` → `dedupe` → `endedKeys`。
     *
     * 之所以要这一层（而不是只用上面手工构造的用例）：真实数据里有两个
     * 自己造语料时不会想到、但会直接影响正确性的事实 ——
     * 1. **艺术栏目的期次号多数不带「第」字**（`系列213讲`），
     *    正则里「第」若写成必需，该栏目 10 条里会有 8 条解析不出期次号，
     *    于是它们各自退化成独立场次、永远判不出已结束；
     * 2. **两个栏目的「报道」覆盖率相差极大**（明德 10 条里 8 条是报道，
     *    艺术 10 条里只有 1 条），所以「已结束」这个标记在明德栏目有效、
     *    在艺术栏目近乎不出现 —— 界面文案与用户预期都必须按这个事实来。
     */
    @Test
    fun `实盘语料_明德栏目的已结束判定完全正确`() {
        val notices = LectureNoticeParser.parseRss(MINGDE_RSS_LIVE, LectureType.MINGDE)

        val sessions = LectureSessions.dedupe(notices)
        val ended = LectureSessions.endedKeys(notices)

        // 10 条通知 → 7 场（M1163/M1165/M1166 各有预告 + 报道两条）。
        //
        // 注意这 3 组里有 2 组（M1163 / M1165）的预告与报道**正文逐字相同**，
        // 只差「预告 / 报道」二字，而那部分会被 cleanTitle 剥掉 ——
        // 因此解析层的去重若把「性质」也算进键之外的维度，就会把报道整条丢掉，
        // 于是这三场都判不出已结束。这一条断言就是在守这个边界。
        assertEquals(listOf("M1167", "M1166", "M1165", "M1164", "M1163", "M1161", "M1159"), sessions.map { it.sessionNo })
        assertEquals("解析层不该吞掉预告/报道中的任何一条", 10, notices.size)
        assertEquals(3, notices.size - sessions.size)

        // M1159–M1166 都有报道 ⇒ 已结束；M1167 只有预告 ⇒ 不标已结束。
        assertEquals(
            setOf("M1159", "M1161", "M1163", "M1164", "M1165", "M1166"),
            ended.map { it.substringAfter('|') }.toSet(),
        )
        assertFalse("M1167 尚无报道，不能标已结束", ended.any { it.endsWith("|M1167") })

        // 高亮卡片因此只剩 M1167 一场 —— 正是「最近可去」的那一场。
        val highlights = sessions.filterNot { it.groupKey in ended }
        assertEquals(listOf("M1167"), highlights.map { it.sessionNo })
        assertEquals(LectureNoticeKind.PREVIEW, highlights.single().kind)
        assertEquals("夯实科技发展的文化基础", highlights.single().title)
    }

    @Test
    fun `实盘语料_艺术栏目不带第字的期次号必须解析出来`() {
        val notices = LectureNoticeParser.parseRss(ART_RSS_LIVE, LectureType.ART_HUMANITY)

        // 这是本用例的核心断言：`系列213讲`（无「第」）必须给出期次号。
        // 若正则退回「第」必选，这里会有 8 条变成空串。
        assertEquals(
            listOf("213", "212", "211", "210", "209", "208", "207", "206", "203", "205"),
            LectureSessions.dedupe(notices).map { it.sessionNo },
        )
        assertTrue("不该有解析不出期次号的条目", notices.all { it.sessionNo.isNotBlank() })

        // 艺术栏目几乎不发报道（10 条里仅 203 一条）⇒ 只有 203 会被标为已结束。
        // 这不是缺陷，是本栏目的数据事实：其余场次确实早已讲完，但站内没有证据，
        // 宁可不标也不按日期猜（猜错会让用户白跑一趟）。
        val ended = LectureSessions.endedKeys(notices)
        assertEquals(setOf("203"), ended.map { it.substringAfter('|') }.toSet())

        // 高亮卡片有 take(5) 兜底，因此不会把整栏 9 条都堆上来，
        // 而是按发布日期倒序取最近的 5 场。
        val highlights = LectureSessions.dedupe(notices).filterNot { it.groupKey in ended }.take(5)
        assertEquals(listOf("213", "212", "211", "210", "209"), highlights.map { it.sessionNo })
    }

    @Test
    fun `实盘语料_预告排在前时报道也不能被吞掉`() {
        // 这是上面那条修复的**回归守门**用例：真实 RSS 是最新在前（报道先出现），
        // 因此「预告/报道正文相同时报道被当重复行丢掉」这个缺陷会**侥幸不暴露**。
        // 这里刻意把顺序倒过来（预告在前），模拟上游改成旧的在前的情形。
        //
        // 若解析层的去重键不含「性质」，被丢掉的就会是报道 —— 而「已结束」只认报道，
        // 于是整场讲座会被错判成「还没讲」，且失败是静默的。
        val reversed = rss(
            "明德讲堂" to listOf(
                "Sat, 06 Jun 2026 02:00:00 +0800" to "明德讲堂M1163预告：联合国的创建与中国",
                "Wed, 17 Jun 2026 03:00:00 +0800" to "明德讲堂M1163报道：联合国的创建与中国",
            ),
        )
        val notices = LectureNoticeParser.parseRss(reversed, LectureType.MINGDE)

        assertEquals("预告与报道都必须保留", 2, notices.size)
        assertEquals(
            setOf(LectureNoticeKind.PREVIEW, LectureNoticeKind.REPORT),
            notices.map { it.kind }.toSet(),
        )
        assertEquals("两条应归并成同一场次", 1, LectureSessions.dedupe(notices).size)
        assertEquals(
            "报道的存在必须能被看到，否则判不出已结束",
            1,
            LectureSessions.endedKeys(notices).size,
        )
    }

    private companion object {
        /** 线上 `明德讲堂` 栏目 RSS 的 10 条（2026-09-17 抓取，标题与发布日原样保留）。 */
        val MINGDE_RSS_LIVE = rss(
            "明德讲堂" to listOf(
                "Mon, 14 Sep 2026 02:21:28 +0800" to "明德讲堂M1167预告：夯实科技发展的文化基础",
                "Fri, 26 Jun 2026 02:35:24 +0800" to "明德讲堂M1166报道：时间的建构:青铜时代政治经济中季节循环的象征表达",
                "Mon, 22 Jun 2026 02:29:20 +0800" to "明德讲堂M1165报道：熔铸与融合：巴蜀地区汉代铁工业与西南社会变迁",
                "Wed, 17 Jun 2026 03:00:00 +0800" to "明德讲堂M1163报道：联合国的创建与中国 ",
                "Wed, 17 Jun 2026 02:00:00 +0800" to "明德讲堂M1164报道：如何欣赏西方油画",
                "Tue, 16 Jun 2026 02:00:00 +0800" to "明德讲堂M1166预告：时间的建构：青铜时代政治经济中季节循环的象征表达",
                "Thu, 11 Jun 2026 02:00:00 +0800" to "明德讲堂M1165预告：熔铸与融合：巴蜀地区汉代铁工业与西南社会变迁",
                "Mon, 08 Jun 2026 02:00:00 +0800" to
                    "明德讲堂M1161报道：游牧与农耕民族共同铸就的华夏文明——从明代锦衣卫对宋元制度的继承谈起",
                "Sat, 06 Jun 2026 02:00:00 +0800" to "明德讲堂M1163预告：联合国的创建与中国",
                "Mon, 01 Jun 2026 02:00:00 +0800" to
                    "明德讲堂M1159报道：从“岭南”“海上”“京津”三大画派谈起——论当代中国水墨之“后海派”",
            ),
        )

        /** 线上 `艺术与人文修养` 栏目 RSS 的 10 条（同上，注意其中 8 条不带「第」字）。 */
        val ART_RSS_LIVE = rss(
            "艺术与人文修养讲座系列" to listOf(
                "Tue, 19 May 2026 02:00:36 +0800" to "艺术与人文修养讲座系列213讲：科学、艺术、人生",
                "Thu, 30 Apr 2026 01:34:32 +0800" to "艺术与人文修养讲座212讲：理想的追寻——红色经典如何讲故事",
                "Fri, 24 Apr 2026 07:12:07 +0800" to "艺术与人文修养讲座211讲：亲近地球三极中的多味人生",
                "Mon, 20 Apr 2026 02:00:00 +0800" to "艺术与人文修养讲座210讲：当生命进入“可编辑”的时代，如何守住人的尊严和安宁？",
                "Tue, 31 Mar 2026 02:00:00 +0800" to "艺术与人文修养讲座209讲：文学名著中的海洋",
                "Fri, 20 Mar 2026 02:00:00 +0800" to "艺术与人文修养讲座208讲：现代汉语辅音声母新论",
                "Wed, 14 Jan 2026 02:00:00 +0800" to "艺术与人文修养讲座系列第207讲预告：京剧的人物塑造与情感表达",
                "Sat, 20 Dec 2025 02:00:00 +0800" to "艺术与人文修养讲座系列206讲：重要的中国文化遗产——俄藏清末民间木版画",
                "Sat, 13 Dec 2025 02:00:00 +0800" to
                    "艺术与人文修养系列讲座第203讲报道：让艺术改变生活谈国家大剧院艺术生产、艺术教育和艺术美育",
                "Fri, 12 Dec 2025 02:00:00 +0800" to "艺术与人文修养讲座系列第205讲：学习毛泽东主席诗词，接受党的革命历史教育",
            ),
        )

        /** 拼一份最小可解析的 RSS；字段与站点输出保持一致（含 CDATA 标题）。 */
        fun rss(channel: Pair<String, List<Pair<String, String>>>): String = buildString {
            append("""<?xml version="1.0" encoding="utf-8"?><rss version="2.0"><channel>""")
            append("<title>").append(channel.first).append(" - 人文学院</title>")
            for ((pubDate, title) in channel.second) {
                append("<item><title><![CDATA[").append(title).append("]]></title>")
                append("<pubDate>").append(pubDate).append("</pubDate>")
                append("<link>/index.php/rczp/item</link></item>")
            }
            append("</channel></rss>")
        }
    }
}