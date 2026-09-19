package com.ucas.qingxin.signin

import com.ucas.qingxin.signin.lecture.LectureNoticeKind
import com.ucas.qingxin.signin.lecture.LectureNoticeParser
import com.ucas.qingxin.signin.lecture.LectureNoticeService
import com.ucas.qingxin.signin.lecture.LectureType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

/**
 * 人文讲座通知解析的离线回归。
 *
 * 报文取自真实响应（只做了截断），因为这里要防的正是「站点结构一变、
 * 正则静默匹配不到」——用自己编的、与真实报文同构的片段测不出这个问题。
 */
class LectureNoticeParseTest {

    // ------------------------------------------------------------------ RSS

    @Test
    fun `RSS 解析出期次号_性质_标题正文与相对路径`() {
        val notices = LectureNoticeParser.parseRss(MINGDE_RSS, LectureType.MINGDE)

        assertEquals(3, notices.size)

        val preview = notices[0]
        assertEquals("M1167", preview.sessionNo)
        assertEquals(LectureNoticeKind.PREVIEW, preview.kind)
        assertEquals("夯实科技发展的文化基础", preview.title)
        assertEquals("2026-09-14", preview.publishedDate)
        assertEquals(LectureType.MINGDE, preview.type)
        // 详情地址必须只留相对路径：校内主机名不得进入内存对象（隐私红线）。
        assertTrue(preview.detailUrl.startsWith("/index.php/"))
        assertTrue(!preview.detailUrl.contains("://"))

        val report = notices[1]
        assertEquals("M1166", report.sessionNo)
        assertEquals(LectureNoticeKind.REPORT, report.kind)
        assertEquals("时间的建构:青铜时代政治经济中季节循环的象征表达", report.title)
        assertEquals("2026-06-26", report.publishedDate)
    }

    @Test
    fun `RSS 中带 CDATA 与实体的标题也能解析`() {
        val rss = item(
            title = "<![CDATA[艺术与人文修养讲座系列第207讲预告：京剧的人物塑造与情感表达]]>",
            link = "/index.php/rczp/2015-12-09-08-37-3/57100-207",
            pubDate = "Mon, 14 Sep 2026 02:21:28 +0800",
        )
        val notices = LectureNoticeParser.parseRss(rss, LectureType.ART_HUMANITY)

        assertEquals(1, notices.size)
        assertEquals("207", notices[0].sessionNo)
        assertEquals(LectureNoticeKind.PREVIEW, notices[0].kind)
        assertEquals("京剧的人物塑造与情感表达", notices[0].title)
        assertEquals("2026-09-14", notices[0].publishedDate)
    }

    @Test
    fun `艺术系列_第207讲这类前缀被完整剥离`() {
        assertEquals(
            "科学、艺术、人生",
            LectureNoticeParser.cleanTitle("艺术与人文修养讲座系列213讲：科学、艺术、人生"),
        )
        assertEquals(
            "让艺术改变生活谈国家大剧院艺术生产、艺术教育和艺术美育",
            LectureNoticeParser.cleanTitle("艺术与人文修养系列讲座第203讲报道：让艺术改变生活谈国家大剧院艺术生产、艺术教育和艺术美育"),
        )
    }

    @Test
    fun `真实标题全量回归_两个栏目的期次号与性质都判对`() {
        // 2026-09-17 线上抓取到的真实标题（原样抄录，含标题末尾空格与中英冒号混用）。
        // 这组用例的价值在于覆盖「同一栏目里标记写法不一致」的真实分布：
        // 艺术系列里既有「第207讲预告」，也有「213讲：」这种**完全不带标记**的写法。
        val mingde = listOf(
            "明德讲堂M1167预告：夯实科技发展的文化基础",
            "明德讲堂M1166报道：时间的建构:青铜时代政治经济中季节循环的象征表达",
            "明德讲堂M1163报道：联合国的创建与中国 ",
            "明德讲堂M1161报道：游牧与农耕民族共同铸就的华夏文明——从明代锦衣卫对宋元制度的继承谈起",
        )
        for (title in mingde) {
            assertTrue(
                "明德讲堂标题必须能解析出 `M` 开头的期次号：$title",
                LectureNoticeParser.sessionNo(title, LectureType.MINGDE).matches(Regex("""M\d{2,4}""")),
            )
        }
        assertEquals(
            "M1167" to LectureNoticeKind.PREVIEW,
            LectureNoticeParser.sessionNo(mingde[0], LectureType.MINGDE) to LectureNoticeParser.kindOf(mingde[0]),
        )
        assertEquals(
            "M1166" to LectureNoticeKind.REPORT,
            LectureNoticeParser.sessionNo(mingde[1], LectureType.MINGDE) to LectureNoticeParser.kindOf(mingde[1]),
        )
        assertEquals("联合国的创建与中国", LectureNoticeParser.cleanTitle(mingde[2]))

        val art = listOf(
            "艺术与人文修养讲座系列213讲：科学、艺术、人生" to Triple("213", LectureNoticeKind.OTHER, "科学、艺术、人生"),
            "艺术与人文修养讲座212讲：理想的追寻——红色经典如何讲故事" to
                Triple("212", LectureNoticeKind.OTHER, "理想的追寻——红色经典如何讲故事"),
            "艺术与人文修养讲座系列第207讲预告：京剧的人物塑造与情感表达" to
                Triple("207", LectureNoticeKind.PREVIEW, "京剧的人物塑造与情感表达"),
            "艺术与人文修养系列讲座第203讲报道：让艺术改变生活谈国家大剧院艺术生产、艺术教育和艺术美育" to
                Triple("203", LectureNoticeKind.REPORT, "让艺术改变生活谈国家大剧院艺术生产、艺术教育和艺术美育"),
            "艺术与人文修养讲座系列第205讲：学习毛泽东主席诗词，接受党的革命历史教育" to
                Triple("205", LectureNoticeKind.OTHER, "学习毛泽东主席诗词，接受党的革命历史教育"),
            "艺术与人文修养讲座211讲：亲近地球三极中的多味人生" to
                Triple("211", LectureNoticeKind.OTHER, "亲近地球三极中的多味人生"),
        )
        for ((raw, expected) in art) {
            assertEquals("标题「$raw」的期次号", expected.first, LectureNoticeParser.sessionNo(raw, LectureType.ART_HUMANITY))
            assertEquals("标题「$raw」的性质", expected.second, LectureNoticeParser.kindOf(raw))
            assertEquals("标题「$raw」剥离后的正文", expected.third, LectureNoticeParser.cleanTitle(raw))
        }
    }

    @Test
    fun `没有预告或报道标记的条目仍算未讲完_值得提醒`() {
        // 实测艺术系列 10 条里 8 条不带任何标记；若只认「预告」二字，
        // 该栏目几乎永远不会触发「新讲座通知」，等于功能对这一半栏目失效。
        val notices = LectureNoticeParser.parseRss(
            item(
                title = "艺术与人文修养讲座系列213讲：科学、艺术、人生",
                link = "/index.php/rczp/2015-12-09-08-37-3/1-a",
                pubDate = "2026-09-14",
            ),
            LectureType.ART_HUMANITY,
        )
        assertEquals(LectureNoticeKind.OTHER, notices[0].kind)
        assertTrue(notices[0].actionable)
    }

    @Test
    fun `报道类条目明确判定为已讲完_不参与提醒`() {
        val notices = LectureNoticeParser.parseRss(MINGDE_RSS, LectureType.MINGDE)
        // 夹具第 2 条是「M1166报道」。
        val report = notices.first { it.sessionNo == "M1166" }
        assertEquals(LectureNoticeKind.REPORT, report.kind)
        assertTrue(!report.actionable)
        // 而同一期的「预告」是未讲完的（夹具里 M1167 预告）。
        assertTrue(notices.first { it.sessionNo == "M1167" }.actionable)
    }

    @Test
    fun `标题里只有期次号时不做剥离_原样保留`() {
        // 剥离后为空必须回退原文，否则界面上会出现一行空白标题。
        assertEquals("明德讲堂M1167", LectureNoticeParser.cleanTitle("明德讲堂M1167"))
    }

    @Test
    fun `解析不出期次号时返回空串而不是猜一个`() {
        val notices = LectureNoticeParser.parseRss(
            item(title = "关于讲座场地调整的通知", link = "/index.php/rczp/x/1-a", pubDate = "2026-09-14"),
            LectureType.MINGDE,
        )
        assertEquals("", notices[0].sessionNo)
        assertEquals(LectureNoticeKind.OTHER, notices[0].kind)
    }

    @Test
    fun `重复条目只保留一条`() {
        val one = item(
            title = "明德讲堂M1167预告：夯实科技发展的文化基础",
            link = "/index.php/rczp/2015-12-09-08-37-2/57442-m1167",
            pubDate = "2026-09-14",
        )
        val notices = LectureNoticeParser.parseRss(one + one, LectureType.MINGDE)
        assertEquals(1, notices.size)
    }

    @Test
    fun `缺少标题的条目被丢弃`() {
        val notices = LectureNoticeParser.parseRss(
            item(title = "", link = "/a", pubDate = "2026-09-14"),
            LectureType.MINGDE,
        )
        assertTrue(notices.isEmpty())
    }

    @Test
    fun `空的或畸形的报文返回空列表而不抛异常`() {
        assertTrue(LectureNoticeParser.parseRss("", LectureType.MINGDE).isEmpty())
        assertTrue(LectureNoticeParser.parseRss("not xml at all", LectureType.MINGDE).isEmpty())
        assertTrue(LectureNoticeParser.parseRss("<item>无标题</item>", LectureType.MINGDE).isEmpty())
    }

    // ------------------------------------------------------------------ 日期

    @Test
    fun `RFC822 与 ISO 两种发布日期都能归一化`() {
        assertEquals("2026-09-14", LectureNoticeParser.normalizeDate("Mon, 14 Sep 2026 02:21:28 +0800"))
        assertEquals("2026-09-14", LectureNoticeParser.normalizeDate("2026-09-14"))
        assertEquals("2026-01-05", LectureNoticeParser.normalizeDate("Mon, 5 Jan 2026 02:21:28 +0800"))
    }

    @Test
    fun `日期解析不出时返回空串_绝不替一个今天`() {
        // 猜日期会让同一条通知在「已见集合」里反复换键，于是被当成新预告反复推送。
        assertEquals("", LectureNoticeParser.normalizeDate(""))
        assertEquals("", LectureNoticeParser.normalizeDate("昨天"))
    }

    // ------------------------------------------------------------------ HTML 回退

    @Test
    fun `HTML 列表页解析出与 RSS 同等的信息`() {
        val html = """
            <div class="b-pagecontent">
               <ul class="b-list">
                 <li>
                   <span class="m-date">
                       2026-09-14
                   </span>
                   <a href="/index.php/rczp/2015-12-09-08-37-2/57442-m1167" target="_black">明德讲堂M1167预告：夯实科技发展的文化基础</a></li>
                 <li>
                   <span class="m-date">
                       2026-06-26
                   </span>
                   <a href="https://renwen.invalid/index.php/rczp/2015-12-09-08-37-2/57444-m1166-2" target="_black">明德讲堂M1166报道：时间的建构</a></li>
               </ul>
            </div>
        """.trimIndent()

        val notices = LectureNoticeParser.parseListHtml(html, LectureType.MINGDE)

        assertEquals(2, notices.size)
        assertEquals("M1167", notices[0].sessionNo)
        assertEquals("夯实科技发展的文化基础", notices[0].title)
        assertEquals("2026-09-14", notices[0].publishedDate)
        // 完整 URL 也要被裁成相对路径（列表页偶尔给出绝对地址）。
        assertEquals("/index.php/rczp/2015-12-09-08-37-2/57444-m1166-2", notices[1].detailUrl)
    }

    @Test
    fun `HTML 结构变化时返回空列表_由调用方判定为失败`() {
        assertTrue(LectureNoticeParser.parseListHtml("<html><body>改版了</body></html>", LectureType.MINGDE).isEmpty())
    }

    // ------------------------------------------------------------------ id 与排序

    @Test
    fun `同一场讲座的 id 稳定_不同场之间不同`() {
        val a = LectureNoticeParser.parseRss(MINGDE_RSS, LectureType.MINGDE)[0]
        val again = LectureNoticeParser.parseRss(MINGDE_RSS, LectureType.MINGDE)[0]
        val other = LectureNoticeParser.parseRss(MINGDE_RSS, LectureType.MINGDE)[1]

        assertEquals(a.id, again.id)
        assertNotEquals(a.id, other.id)
    }

    @Test
    fun `identityKey 不含标题_改错别字不会让它变成新预告`() {
        val before = LectureNoticeParser.parseRss(MINGDE_RSS, LectureType.MINGDE)[0]
        val after = before.copy(title = "夯实科技发展的文化基础（修订）")
        assertEquals(before.identityKey, after.identityKey)
    }

    @Test
    fun `排序把最新发布的排在前面_日期缺失的排在最后`() {
        val list = LectureNoticeParser.parseRss(MINGDE_RSS, LectureType.MINGDE)
            .sortedWith(LectureNoticeService.MOST_RECENT_FIRST)
        assertEquals(listOf("2026-09-14", "2026-06-26", "2026-06-22"), list.map { it.publishedDate })
    }

    // ------------------------------------------------------------------ 字符集与 URL

    @Test
    fun `按响应头字符集解码_GBK 报文不会变成乱码`() {
        val raw = "明德讲堂M1167预告".toByteArray(Charset.forName("GBK"))
        val text = LectureNoticeService.decode(raw, "text/html; charset=GBK")
        assertEquals("明德讲堂M1167预告", text)
    }

    @Test
    fun `响应头没有字符集时按正文声明解码`() {
        val xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?><title>明德讲堂</title>"
        val text = LectureNoticeService.decode(xml.toByteArray(Charsets.UTF_8), null)
        assertEquals(xml, text)
    }

    @Test
    fun `RSS 入口在带查询串的列表地址上也能拼对`() {
        assertEquals(
            "https://example.invalid/list?format=feed&type=rss",
            LectureNoticeService.rssUrl("https://example.invalid/list"),
        )
        assertEquals(
            "https://example.invalid/list?id=1&format=feed&type=rss",
            LectureNoticeService.rssUrl("https://example.invalid/list?id=1"),
        )
    }

    // ------------------------------------------------------------------ 夹具

    private fun item(title: String, link: String, pubDate: String): String = """
        <rss version="2.0"><channel><item>
          <title>$title</title>
          <link>$link</link>
          <guid isPermaLink="true">$link</guid>
          <pubDate>$pubDate</pubDate>
          <description></description>
        </item></channel></rss>
    """.trimIndent()

    private companion object {
        /** 真实 RSS 的截断版（字段结构、CDATA 包裹与 `+0800` 时区都保持原样）。 */
        val MINGDE_RSS = """
            <?xml version="1.0" encoding="utf-8"?>
            <rss version="2.0">
              <channel>
                <title>明德讲堂</title>
                <item>
                  <title><![CDATA[明德讲堂M1167预告：夯实科技发展的文化基础]]></title>
                  <link>/index.php/rczp/2015-12-09-08-37-2/57442-m1167</link>
                  <guid isPermaLink="true">/index.php/rczp/2015-12-09-08-37-2/57442-m1167</guid>
                  <pubDate>Mon, 14 Sep 2026 02:21:28 +0800</pubDate>
                  <description></description>
                </item>
                <item>
                  <title><![CDATA[明德讲堂M1166报道：时间的建构:青铜时代政治经济中季节循环的象征表达]]></title>
                  <link>/index.php/rczp/2015-12-09-08-37-2/57444-m1166-2</link>
                  <pubDate>Fri, 26 Jun 2026 03:10:00 +0800</pubDate>
                </item>
                <item>
                  <title><![CDATA[明德讲堂M1165报道：熔铸与融合：巴蜀地区汉代铁工业与西南社会变迁]]></title>
                  <link>/index.php/rczp/2015-12-09-08-37-2/57443-m1165-2</link>
                  <pubDate>Mon, 22 Jun 2026 06:00:00 +0800</pubDate>
                </item>
              </channel>
            </rss>
        """.trimIndent()
    }
}
