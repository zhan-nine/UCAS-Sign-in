package com.ucas.qingxin.signin

import com.ucas.qingxin.signin.update.VersionTag
import com.ucas.qingxin.signin.update.VersionTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [VersionTags] / [VersionTag] 的单测。
 *
 * 这个功能的失败方式很隐蔽：比较写错不会崩溃，只会**静默不提示**，
 * 或者更糟 —— 反复提示用户去装一个更旧的包。因此这里的用例围绕
 * 「真实仓库里出现过的 tag 形态」来写，而不是泛泛地测几个数字。
 */
class VersionTagTest {

    @Test
    fun `数字段按数值比较 不是字符串比较`() {
        // 这是整个功能存在的理由：字符串比较会认为 "1.10.0" < "1.9.9"。
        assertTrue(parse("1.10.0") > parse("1.9.9"))
        assertTrue(parse("1.2.10") > parse("1.2.9"))
        assertTrue(parse("2.0.0") > parse("1.99.99"))
    }

    @Test
    fun `容忍 v 前缀与大小写`() {
        assertEquals(parse("1.2.0"), parse("v1.2.0"))
        assertEquals(parse("1.2.0"), parse("V1.2.0"))
    }

    @Test
    fun `少于三段时缺省段补零`() {
        assertEquals(parse("1.2.0"), parse("1.2"))
        assertEquals(parse("2.0.0"), parse("2"))
        assertEquals(0, parse("1.2").compareTo(parse("v1.2.0")))
    }

    @Test
    fun `有后缀的排在同号正式版之下`() {
        // 仓库里真实存在 v1.1.13alpha，它是 1.1.13 的预发布。
        // 若把它判成大于 1.1.13，一个 alpha 就会盖过正式版。
        assertTrue(parse("1.1.13alpha") < parse("1.1.13"))
        assertTrue(parse("1.2.0alpha") < parse("1.2.0"))
        // 而它仍然大于上一版，这样「1.1.12 的用户」会被提示到 1.1.13alpha。
        assertTrue(parse("1.1.13alpha") > parse("1.1.12"))
    }

    @Test
    fun `后缀的分隔符写法都认`() {
        assertEquals(parse("1.2.0rc1"), parse("1.2.0-rc1"))
        assertEquals(parse("1.2.0rc1"), parse("1.2.0_rc1"))
        assertEquals("rc1", parse("1.2.0-RC1")?.suffix)
    }

    @Test
    fun `后缀之间按字典序`() {
        assertTrue(parse("1.2.0alpha") < parse("1.2.0beta"))
        assertTrue(parse("1.2.0beta") < parse("1.2.0rc"))
    }

    @Test
    fun `解析不出的 tag 返回 null`() {
        // 仓库里真实存在这两个代号 tag，它们只能靠 Release 标题里的版本号兜底。
        assertNull(VersionTags.parse("qingxin"))
        assertNull(VersionTags.parse("android"))
        assertNull(VersionTags.parse(""))
        assertNull(VersionTags.parse(null))
        assertNull(VersionTags.parse("发布"))
    }

    @Test
    fun `整串匹配 不从混合文本里截一段`() {
        // tag 形态的东西必须整串是版本号；`v1.2.0-hotfix-2` 属于未经约定，
        // 宁可解析失败（退回标题）也不要猜出一个 1.2.0。
        assertNull(VersionTags.parse("v1.2.0-hotfix-2"))
        assertNull(VersionTags.parse("release-1.2.0"))
    }

    @Test
    fun `lastInText 取标题里的最后一个版本号`() {
        assertEquals(parse("1.1.13"), VersionTags.lastInText("清新签到1.1.13"))
        assertEquals(parse("1.1.6"), VersionTags.lastInText("清新签到1.1.6"))
        assertNull(VersionTags.lastInText("清新签到"))
    }

    @Test
    fun `lastInText 不被期次号之类的编号干扰`() {
        // 期次号形如 M1167：左边的 `M` 是字母，因此整段不会命中版本号；
        // 标题里同时出现期次号与版本号时，仍应取版本号那一段。
        assertEquals(parse("1.2.0"), VersionTags.lastInText("明德讲堂 M1167 清新签到1.2.0"))
    }

    @Test
    fun `面向用户的写法不带 v 前缀`() {
        assertEquals("1.2.0", parse("v1.2.0").toString())
        assertEquals("1.1.13alpha", parse("v1.1.13alpha").toString())
        assertEquals("1.2.0", parse("1.2").toString())
    }

    /** 期望能解析成功的用例专用：解析失败时直接让用例挂掉，而不是靠后续断言才发现。 */
    private fun parse(raw: String?): VersionTag =
        requireNotNull(VersionTags.parse(raw)) { "应当能解析：$raw" }
}
