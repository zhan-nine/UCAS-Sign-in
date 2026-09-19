package com.ucas.qingxin.signin

import com.ucas.qingxin.signin.update.UpdateReleases
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [UpdateReleases] 的单测。
 *
 * fixture 用的是**真实仓库响应裁剪后的形状**：`tag_name` 里既有 `v1.1.8` 这种
 * 正常写法，也有 `qingxin` / `android` 这种代号；`name` 里才有真正的版本号。
 * 这正是解析必须处理回退的原因。
 */
class UpdateReleasesTest {

    @Test
    fun `取版本号最高的一条 而不是列表里最靠前的`() {
        // 列表按发布时间倒序，历史上发布顺序与版本号顺序并不一致。
        val json = """
            [
              {"tag_name":"v1.1.13alpha","name":"清新签到1.1.13","draft":false,"prerelease":false,"html_url":"https://example.invalid/r/1"},
              {"tag_name":"v1.1.8","name":"清新签到1.1.8","draft":false,"prerelease":false,"html_url":"https://example.invalid/r/2"},
              {"tag_name":"v1.1.10","name":"清新签到1.1.10","draft":false,"prerelease":false,"html_url":"https://example.invalid/r/3"}
            ]
        """.trimIndent()
        val best = requireNotNull(UpdateReleases.best(json))
        assertEquals("1.1.13alpha", best.version.toString())
        assertEquals("v1.1.13alpha", best.tag)
        assertEquals("https://example.invalid/r/1", best.pageUrl)
    }

    @Test
    fun `tag 是代号时回退到标题里的版本号`() {
        val json = """
            [
              {"tag_name":"qingxin","name":"清新签到1.1.6","draft":false,"prerelease":false,"html_url":"u6"},
              {"tag_name":"android","name":"清新签到1.1.7","draft":false,"prerelease":false,"html_url":"u7"}
            ]
        """.trimIndent()
        val best = requireNotNull(UpdateReleases.best(json))
        assertEquals("1.1.7", best.version.toString())
        // tag 原样保留：它是「不再提示」的匹配键。
        assertEquals("android", best.tag)
        assertEquals("u7", best.pageUrl)
    }

    @Test
    fun `跳过草稿与显式标记的预发布`() {
        val json = """
            [
              {"tag_name":"v9.9.9","name":"草稿","draft":true,"prerelease":false},
              {"tag_name":"v9.9.8","name":"预发布","draft":false,"prerelease":true},
              {"tag_name":"v1.2.0","name":"清新签到 1.2.0","draft":false,"prerelease":false,"html_url":"u"}
            ]
        """.trimIndent()
        assertEquals("1.2.0", requireNotNull(UpdateReleases.best(json)).version.toString())
    }

    @Test
    fun `空数组与不可用响应返回 null 而不是抛异常`() {
        // 「检查更新」是锦上添花的功能，它不该有能力让主页报错。
        assertNull(UpdateReleases.best("[]"))
        assertNull(UpdateReleases.best(""))
        assertNull(UpdateReleases.best("not json"))
        assertNull(UpdateReleases.best("{}"))
    }

    @Test
    fun `单条里既有草稿无版本号也有正常条目时 结论仍正确`() {
        val json = """
            [
              {"tag_name":"nightly","name":"无版本号","draft":false,"prerelease":false},
              {"tag_name":"v1.3.0","name":"清新签到 1.3.0","draft":false,"prerelease":false,"html_url":"u"}
            ]
        """.trimIndent()
        val best = requireNotNull(UpdateReleases.best(json))
        assertEquals("1.3.0", best.version.toString())
        assertEquals("清新签到 1.3.0", best.title)
    }

    @Test
    fun `全部条目都解析不出时返回 null`() {
        val json = """[{"tag_name":"qingxin","name":"清新签到","draft":false,"prerelease":false}]"""
        assertNull(UpdateReleases.best(json))
    }

    @Test
    fun `summary 取首个非空行并剥掉 Markdown 记号`() {
        val notes = "\n## 更新内容\n\n- 修复讲座页闪退\n- 新增更新提示\n"
        assertEquals("更新内容", UpdateReleases.summary(notes))
        assertEquals("修复讲座页闪退", UpdateReleases.summary("- 修复讲座页闪退"))
        assertEquals("", UpdateReleases.summary(""))
    }

    @Test
    fun `summary 过长时截断并加省略号`() {
        val long = "更".repeat(100)
        val out = UpdateReleases.summary(long, maxChars = 10)
        assertTrue(out.length == 11)
        assertTrue(out.endsWith("…"))
    }
}
