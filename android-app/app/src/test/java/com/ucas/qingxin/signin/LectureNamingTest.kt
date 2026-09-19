package com.ucas.qingxin.signin

import com.ucas.qingxin.signin.lecture.LectureNaming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「讲座场次」判定的回归测试。
 *
 * 用例里的课程名**全部取自真实注册表数据**（本机全库枚举留档，6968 条有名称条目），
 * 不是编出来的样例 —— 这套判定的价值完全取决于它能否在真实命名上站住，
 * 而这个数据集里最反直觉的一点是：**明德讲堂的条目名不含「明德讲堂」四个字**。
 * 自己造的干净样例恰好会把这个问题掩盖掉，所以必须用真名。
 */
class LectureNamingTest {

    /** 真实场次：注意其中三条**完全不含任何讲座关键词**，这正是判据不能依赖关键词的原因。 */
    private val realSessions = listOf(
        "M1167中关村会场：夯实科技发展的文化基础",
        "M1167雁栖湖会场：夯实科技发展的文化基础",
        "M1167玉泉路会场：夯实科技发展的文化基础",
        "明德讲堂M1156玉泉路分会场；未来学：研究“从今往后”的学问",
        "M1118中关村分会场：夏河丹尼索瓦人与史前人类探索",
        "M1155雁栖湖主校区：中国国际科技合作战略的演变",
    )

    /**
     * 真实的**课程容器**：学期初批量导入的占位，同名成串出现。
     * 它们带「讲座/论坛」等关键词，却都不是具体场次 —— 关键词在这里正好是反例。
     */
    private val containers = listOf(
        "全球地缘与中国国情专题讲座",
        "发现生命奥秘专题讲座",
        "环境与人类健康专题讲座",
        "化学与社会专题讲座",
        "材料科学与工程讲座",
        "雁栖分子科学论坛",
        "电子信息工程系列讲座",
        "企业家系列讲座（技术创新及产业化）",
        "艺术与人文修养系列讲座",
    )

    @Test
    fun `真实明德场次全部判为场次（包括不含关键词的那三条）`() {
        for (name in realSessions) {
            assertTrue("应识别为场次：$name", LectureNaming.isLectureSession(name))
        }
    }

    @Test
    fun `不含任何讲座关键词的条目也能被认出`() {
        // 这条是整个判据的关键：靠关键词会漏掉它，靠期次号才能认出它。
        val bare = "M1167雁栖湖会场：夯实科技发展的文化基础"
        assertFalse("它确实不含「讲座/讲堂/明德/论坛」任何一词", bare.contains("讲座"))
        assertFalse(bare.contains("明德"))
        assertEquals("M1167", LectureNaming.sessionLabel(bare))
        assertTrue(LectureNaming.isLectureSession(bare))
    }

    @Test
    fun `明德期次号解析正确`() {
        assertEquals("M1167", LectureNaming.sessionLabel("M1167中关村会场：夯实科技发展的文化基础"))
        assertEquals("M1156", LectureNaming.sessionLabel("明德讲堂M1156玉泉路分会场；未来学：…"))
        assertEquals("M1118", LectureNaming.sessionLabel("M1118中关村分会场：夏河丹尼索瓦人与史前人类探索"))
    }

    @Test
    fun `期次号的边界与歧义处理`() {
        // 实测的三种真实排布都能取到：紧接汉字、紧接空格、行首。
        assertEquals("M1167", LectureNaming.sessionLabel("M1167中关村会场：夯实科技发展的文化基础"))
        assertEquals("M1167", LectureNaming.sessionLabel("M1167 2026年秋季 雁栖湖"))
        assertEquals("M1156", LectureNaming.sessionLabel("明德讲堂M1156玉泉路分会场；未来学：…"))

        // 歧义时**不猜**：`M11671` 既可能是 M1167 也可能是 M11671，
        // 与其赌一个，不如不报 —— 报错一个期次号会让去重表把两场当成同一场。
        assertNull(LectureNaming.sessionLabel("M11671：某个主题"))

        // `(?<![A-Za-z])`：英文编号里的 M+数字 不算（如 csM1234）。
        assertNull(LectureNaming.sessionLabel("csM1234：某门课"))
    }

    @Test
    fun `艺术系列的期次号形态：带第与不带第都要认`() {
        // 真实标题（取自 renwen RSS 实测，2026-09-17）：
        // `艺术与人文修养讲座系列213讲：科学、艺术、人生` —— **没有「第」字**。
        // 通知解析器早就是「第可选」，注册表侧的判据必须与它一致，
        // 否则同一场讲座在两个数据源里会得到不同的期次号。
        assertEquals("第213讲", LectureNaming.sessionLabel("艺术与人文修养讲座系列213讲：科学、艺术、人生"))
        assertEquals("第212讲", LectureNaming.sessionLabel("艺术与人文修养讲座212讲：理想的追寻——红色经典如何讲故事"))
        // 带「第」的形态同时存在，也要认。
        assertEquals("第215讲", LectureNaming.sessionLabel("艺术与人文修养讲座系列第215讲：京剧的人物塑造与情感表达"))
        assertEquals("第9讲", LectureNaming.sessionLabel("雁栖大讲堂 第 9 讲：引力波"))
    }

    @Test
    fun `学期初批量导入的课程容器不算场次`() {
        for (name in containers) {
            assertNull("不带期次号，不是一场具体讲座：$name", LectureNaming.sessionLabel(name))
            assertFalse("因此不该被当成新场次：$name", LectureNaming.isLectureSession(name))
        }
    }

    @Test
    fun `普通课程不会被误判为讲座`() {
        val ordinary = listOf(
            "微积分I习题",
            "低年级研讨课I（物理学）",
            "材料结构分析（材料与化工）",
            "博士学位英语-高级读写R4",
            "大学英语读写",
            "思想政治理论课",
        )
        for (name in ordinary) {
            assertFalse("普通课程不该命中：$name", LectureNaming.isLectureSession(name))
            assertNull(LectureNaming.sessionLabel(name))
        }
    }

    @Test
    fun `空名称与非场次边界`() {
        assertNull(LectureNaming.sessionLabel(""))
        assertNull(LectureNaming.sessionLabel("   "))
        assertFalse(LectureNaming.isLectureSession(""))
        // 系列名本身（没有期次号）不是场次。
        assertNull(LectureNaming.sessionLabel("明德讲堂"))
        assertFalse(LectureNaming.isLectureSession("明德讲堂"))
    }
}
