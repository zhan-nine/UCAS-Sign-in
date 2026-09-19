package com.ucas.qingxin.signin

import com.ucas.qingxin.signin.lecture.LectureBoard
import com.ucas.qingxin.signin.lecture.LectureEntry
import com.ucas.qingxin.signin.lecture.LectureEvent
import com.ucas.qingxin.signin.lecture.LectureEventKind
import com.ucas.qingxin.signin.lecture.LectureNotice
import com.ucas.qingxin.signin.lecture.LectureNoticeKind
import com.ucas.qingxin.signin.lecture.LectureOrigin
import com.ucas.qingxin.signin.lecture.LectureType
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 讲座列表归并层的用例。
 *
 * 覆盖三件容易出错、且线上表现很隐蔽的事：
 * 1. **同一场讲座的合并**：合并失败会让用户看到重复的两行，
 *    而且两行各自只有一半信息（一行有时间没详情、一行有详情没时间）；
 * 2. **「已结束」的两种判据**：有时间的按时间比、没时间的按报道判 ——
 *    混用任一种都会静默地把整类条目判错；
 * 3. **两段列表的顺序**：无时间的通知无法与有时间的讲座比较先后，
 *    硬塞进时间轴（等于编一个时间）是最糟的处理方式。
 */
class LectureBoardTest {

    private fun event(
        title: String,
        date: String,
        start: String = "15:30",
        end: String = "17:30",
        location: String = "雁栖湖 - 教三楼204",
        kind: LectureEventKind = LectureEventKind.HUMANITY,
    ) = LectureEvent(
        title = title,
        date = date,
        startTime = start,
        endTime = end,
        location = location,
        kind = kind,
    )

    private fun notice(
        title: String,
        sessionNo: String = "",
        published: String = "2026-09-01",
        kind: LectureNoticeKind = LectureNoticeKind.PREVIEW,
        type: LectureType = LectureType.MINGDE,
        detailUrl: String = "/index.php/rczp/x",
    ) = LectureNotice(
        type = type,
        sessionNo = sessionNo,
        title = title,
        publishedDate = published,
        kind = kind,
        detailUrl = detailUrl,
    )

    private val now: LocalDateTime = LocalDateTime.of(2026, 9, 17, 10, 0)

    // ---------- 合并 ----------

    /**
     * 明德讲堂在两个来源里的标题**本来就不同**：
     * 预约系统带会场前缀（`M1167雁栖湖会场：…`），学院网站没有。
     * 因此合并只能靠**期次号**，靠标题永远合不上。
     */
    @Test
    fun `预约系统与学院网站的同一场讲座合并为一条`() {
        val board = LectureBoard.build(
            events = listOf(event("M1167雁栖湖会场：夯实科技发展的文化基础", "2026-09-20")),
            notices = listOf(notice("夯实科技发展的文化基础", sessionNo = "M1167")),
            endedGroupKeys = emptySet(),
            now = now,
        )
        assertEquals(1, board.upcoming.size)
        val entry = board.upcoming.single()
        // 时间场地来自预约系统，详情链接来自学院网站 —— 两边各出一半。
        assertEquals("09-20 15:30-17:30", entry.timeLabel)
        assertEquals("雁栖湖 - 教三楼204", entry.location)
        assertEquals("/index.php/rczp/x", entry.detailUrl)
        assertEquals(LectureOrigin.SCHEDULE, entry.origin)
        assertTrue(entry.timed)
    }

    /** 多个会场时，详情链接要补到**每一行**：文章讲的是这场讲座，不是某个会场。 */
    @Test
    fun `同一场次的多个会场都补上详情链接`() {
        val board = LectureBoard.build(
            events = listOf(
                event("M1167雁栖湖会场：夯实科技发展的文化基础", "2026-09-20", location = "雁栖湖"),
                event("M1167中关村会场：夯实科技发展的文化基础", "2026-09-20", location = "中关村"),
            ),
            notices = listOf(notice("夯实科技发展的文化基础", sessionNo = "M1167")),
            endedGroupKeys = emptySet(),
            now = now,
        )
        assertEquals(2, board.upcoming.size)
        assertTrue("两个会场都该有详情链接", board.upcoming.all { it.detailUrl.isNotBlank() })
    }

    /** 期次号解析不出时才退化为标题包含判定（会场前缀只出现在一侧）。 */
    @Test
    fun `没有期次号时按标题包含兜底合并`() {
        val board = LectureBoard.build(
            events = listOf(event("雁栖湖会场：人工智能的过去与未来", "2026-09-20")),
            notices = listOf(notice("人工智能的过去与未来", sessionNo = "")),
            endedGroupKeys = emptySet(),
            now = now,
        )
        assertEquals(1, board.upcoming.size)
        assertEquals("/index.php/rczp/x", board.upcoming.single().detailUrl)
    }

    /**
     * 标题太短时**不做**包含判定。
     *
     * 「讲座」这种两字标题会命中一大片条目，把无关讲座的详情链接挂到别的讲座上 ——
     * 这比不合并更糟（用户点进去看到的是另一场）。
     */
    /** 过短标题不合并时：有时间的进即将开始，无时间的进暂无日期 —— 两段合计 2 条。 */
    @Test
    fun `过短的标题不触发包含兜底`() {
        val board = LectureBoard.build(
            events = listOf(event("雁栖湖会场：某场讲座", "2026-09-20")),
            notices = listOf(notice("讲座", sessionNo = "", published = "2026-09-15")),
            endedGroupKeys = emptySet(),
            now = now,
        )
        assertEquals(1, board.upcoming.size)
        assertEquals(1, board.undated.size)
    }

    /** 完全没有对应预约系统条目的通知要**单独成行**（不能丢），且进「暂无日期」段。 */
    @Test
    fun `没有时间场地的通知单独成行`() {
        val board = LectureBoard.build(
            events = emptyList(),
            notices = listOf(
                notice(
                    "艺术与人文修养系列讲座第213讲：某主题",
                    sessionNo = "213",
                    published = "2026-09-15",
                ),
            ),
            endedGroupKeys = emptySet(),
            now = now,
        )
        assertTrue(board.upcoming.isEmpty())
        val entry = board.undated.single()
        assertFalse(entry.timed)
        assertEquals("213", entry.sessionNo)
        assertEquals(LectureOrigin.NOTICE, entry.origin)
        assertEquals(LectureType.ART_HUMANITY, entry.type)
    }

    // ---------- 已结束的两种判据 ----------

    /** 有确切时间的：结束时刻已过即结束 —— 硬证据，不受「有没有报道」影响。 */
    @Test
    fun `有时间时按时间判定结束`() {
        val board = LectureBoard.build(
            events = listOf(
                event("已讲完的讲座", "2026-09-16"),
                event("还没讲的讲座", "2026-09-20"),
            ),
            notices = emptyList(),
            endedGroupKeys = emptySet(),
            now = now,
        )
        assertEquals(listOf("还没讲的讲座"), board.upcoming.map { it.title })
        assertEquals(listOf("已讲完的讲座"), board.past.map { it.title })
    }

    /**
     * 没有时间的：只能按「同一期次号下出现过报道」判。
     *
     * 这一条同时锁住了两个方向的错误：
     * - 用时间比一条**没有时间**的通知（等于按发布日期猜）；
     * - 或者干脆不判（往期段永远为空）。
     */
    @Test
    fun `没有时间时按报道判定结束`() {
        val reported = notice("某场讲座", sessionNo = "M1200")
        val board = LectureBoard.build(
            events = emptyList(),
            notices = listOf(reported, notice("另一场讲座", sessionNo = "M1201", published = "2026-09-16")),
            endedGroupKeys = setOf(reported.groupKey),
            now = now,
        )
        assertTrue(board.upcoming.isEmpty())
        // 发布仅 1 天前：仍在「暂无日期」，不因启发式进往期
        assertEquals(listOf("另一场讲座"), board.undated.map { it.title })
        assertEquals(listOf("某场讲座"), board.past.map { it.title })
    }

    /** 发布超过 14 天仍无报道、也无预约系统时间 ⇒ 视为已结束（进往期）。 */
    @Test
    fun `过期无日期预告进往期`() {
        val board = LectureBoard.build(
            events = emptyList(),
            notices = listOf(notice("很久以前的预告", sessionNo = "M1100", published = "2026-08-01")),
            endedGroupKeys = emptySet(),
            now = now,
        )
        assertTrue(board.undated.isEmpty())
        assertEquals(listOf("很久以前的预告"), board.past.map { it.title })
    }

    /**
     * 两个判据是**取或**的：预约系统说已经讲完、学院网站却还没发报道时，
     * 条目必须进往期。若取「与」，一场已经讲完的讲座会一直挂在「即将开始」。
     */
    @Test
    fun `预约系统的结束判定优先于学院网站的报道缺失`() {
        val board = LectureBoard.build(
            events = listOf(event("M1167雁栖湖会场：夯实科技发展的文化基础", "2026-09-16")),
            // 这条通知还是「预告」状态，学院网站还没发报道。
            notices = listOf(notice("夯实科技发展的文化基础", sessionNo = "M1167")),
            endedGroupKeys = emptySet(),
            now = now,
        )
        assertTrue("按时间已结束，就必须进往期", board.upcoming.isEmpty())
        assertEquals(1, board.past.size)
        // 合并仍然生效：往期条目也该带详情链接。
        assertEquals("/index.php/rczp/x", board.past.single().detailUrl)
    }

    /** 反过来：报道说结束了，但预约系统的时间还没到（例如讲座延期）⇒ 也进往期。 */
    @Test
    fun `报道判定同样能让条目进往期`() {
        val reported = notice("夯实科技发展的文化基础", sessionNo = "M1167", kind = LectureNoticeKind.REPORT)
        val board = LectureBoard.build(
            events = listOf(event("M1167雁栖湖会场：夯实科技发展的文化基础", "2026-09-20")),
            notices = listOf(reported),
            endedGroupKeys = setOf(reported.groupKey),
            now = now,
        )
        assertTrue(board.upcoming.isEmpty())
        assertEquals(1, board.past.size)
    }

    // ---------- 排序 ----------

    /**
     * 「即将开始」只收有时间的；无时间通知进「暂无举办日期」。
     *
     * 把无时间的塞进即将开始，等于用发布日冒充讲座日 —— 这正是用户看到
     * 「日期判定不对」的根因之一。
     */
    @Test
    fun `即将开始只含有时间的条目无时间通知单独成段`() {
        val board = LectureBoard.build(
            events = listOf(
                event("晚的讲座", "2026-09-25"),
                event("早的讲座", "2026-09-20"),
            ),
            notices = listOf(
                notice("新发布的通知", sessionNo = "M1300", published = "2026-09-15"),
                notice("旧发布的通知", sessionNo = "M1301", published = "2026-09-10"),
            ),
            endedGroupKeys = emptySet(),
            now = now,
        )
        assertEquals(listOf("早的讲座", "晚的讲座"), board.upcoming.map { it.title })
        assertEquals(listOf("新发布的通知", "旧发布的通知"), board.undated.map { it.title })
    }

    /** 往期段按时间**降序**：刚讲完的在最前。 */
    @Test
    fun `往期段按时间降序`() {
        val board = LectureBoard.build(
            events = listOf(
                event("较早讲完", "2026-09-10"),
                event("刚讲完", "2026-09-16"),
            ),
            notices = emptyList(),
            endedGroupKeys = emptySet(),
            now = now,
        )
        assertEquals(listOf("刚讲完", "较早讲完"), board.past.map { it.title })
    }

    // ---------- 栏目标签 ----------

    /**
     * 栏目标签必须按**期次号形态**判，而不是按条目的 `category`。
     *
     * 因为合并之后 `category` 可能来自任一侧（`明德讲堂` 或 `人文讲座`），
     * 用它筛选会时灵时不灵；而期次号两侧都有、形态本身就能区分栏目。
     */
    @Test
    fun `栏目标签按期次号形态判定`() {
        val board = LectureBoard.build(
            events = listOf(
                event("M1167雁栖湖会场：明德讲座", "2026-09-20"),
                event("第213讲：艺术讲座", "2026-09-21"),
                event("科学前沿讲座：某主题", "2026-09-22"),
            ),
            notices = emptyList(),
            endedGroupKeys = emptySet(),
            now = now,
        )
        val byTitle = board.upcoming.associateBy { it.title }
        assertEquals(LectureType.MINGDE, byTitle.getValue("M1167雁栖湖会场：明德讲座").type)
        assertEquals(LectureType.ART_HUMANITY, byTitle.getValue("第213讲：艺术讲座").type)
        // 判不出栏目时给 null：它只在「全部」里出现，不会因为筛选而消失得没有解释。
        assertNull(byTitle.getValue("科学前沿讲座：某主题").type)
    }

    /**
     * 合并之后**不能**出现重复行。
     *
     * 一场讲座在预约系统里 3 个会场、学院网站 2 条通知（预告 + 报道）时，
     * 稍有不慎就会生成 5 行；这里锁住「3 行」这个正确答案。
     */
    @Test
    fun `多会场多通知不会产生重复行`() {
        val board = LectureBoard.build(
            events = listOf(
                event("M1167雁栖湖会场：主题", "2026-09-20", location = "雁栖湖"),
                event("M1167中关村会场：主题", "2026-09-20", location = "中关村"),
                event("M1167玉泉路会场：主题", "2026-09-20", location = "玉泉路"),
            ),
            notices = listOf(
                notice("主题", sessionNo = "M1167", kind = LectureNoticeKind.PREVIEW),
                notice("主题", sessionNo = "M1167", kind = LectureNoticeKind.REPORT, published = "2026-09-21"),
            ),
            endedGroupKeys = emptySet(),
            now = now,
        )
        assertEquals(3, board.upcoming.size)
        assertEquals(3, board.upcoming.map { it.key }.toSet().size)
    }

    /** 空输入不该炸，也不该产出条目。 */
    @Test
    fun `空输入返回空结果`() {
        val board = LectureBoard.build(emptyList(), emptyList(), emptySet(), now)
        assertEquals(emptyList<LectureEntry>(), board.upcoming)
        assertEquals(emptyList<LectureEntry>(), board.undated)
        assertEquals(emptyList<LectureEntry>(), board.past)
    }
}
