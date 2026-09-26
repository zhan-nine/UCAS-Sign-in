package com.ucas.qingxin.signin

import com.ucas.qingxin.signin.update.UpdateRelease
import com.ucas.qingxin.signin.update.UpdateSummary
import com.ucas.qingxin.signin.update.VersionTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * 设置页「版本与更新」卡片上那几行文案的单测。
 *
 * ## 为什么值得钉住
 * 功能的失败方式是**不可见**的：如果「最新版本」那行永远显示「尚未查到」，
 * 或者「上次检查」永远显示「从未」，界面看起来一样正常、不报错、不崩溃，
 * 但用户就再次无法判断检测到底有没有跑 —— 这正是最初那次反馈的来源。
 */
class UpdateSummaryTest {

    private fun release(tag: String) = UpdateRelease(
        tag = tag,
        version = requireNotNull(VersionTags.parse(tag)),
        title = "清新签到 $tag",
        notes = "更新内容",
        pageUrl = "https://example.invalid/releases/$tag",
    )

    @Test
    fun `从未查到过时 明确说「尚未查到」而不是「已是最新」`() {
        // 「不知道」与「确定没有更新」必须能区分开：断网时若显示「已是最新」，
        // 用户会以为检查成功了。
        val line = UpdateSummary.latestLine(latest = null, currentVersion = "1.2.1")
        assertTrue(line, line.contains("尚未查到"))
        assertTrue(line, !line.contains("已是最新"))
    }

    @Test
    fun `远端版本与当前持平 显示已是最新`() {
        // 这是用户当前的真实状态：装的 1.2.1，仓库最新也是 v1.2.1。
        // 旧实现在这种情形下**什么都不显示**，于是看起来像功能没实现。
        val line = UpdateSummary.latestLine(release("v1.2.1"), currentVersion = "1.2.1")
        assertTrue(line, line.contains("1.2.1"))
        assertTrue(line, line.contains("已是最新"))
    }

    @Test
    fun `远端版本更高 显示比当前新`() {
        val line = UpdateSummary.latestLine(release("v1.2.2"), currentVersion = "1.2.1")
        assertTrue(line, line.contains("比当前新"))
    }

    @Test
    fun `从未检查过时 上次检查显示从未`() {
        assertEquals("上次检查：从未", UpdateSummary.lastCheckedLine(0L))
        assertEquals("上次检查：从未", UpdateSummary.lastCheckedLine(-1L))
    }

    @Test
    fun `上次检查时间按东八区显示`() {
        // 2026-09-26T01:51Z == 北京时间 09:51。若时区写错（比如漏了 ZoneId），
        // 这里会变成 01:51，一眼可辨。
        val epochMs = Instant.parse("2026-09-26T01:51:00Z").toEpochMilli()
        assertEquals("上次检查：9月26日 09:51", UpdateSummary.lastCheckedLine(epochMs))
    }

    @Test
    fun `状态行区分 没有可用 Release 与 已是最新`() {
        assertTrue(
            UpdateSummary.statusLine(null, "1.2.1").contains("还没有可用的 Release"),
        )
        assertTrue(
            UpdateSummary.statusLine(release("v1.2.1"), "1.2.1").contains("已是最新版本 1.2.1"),
        )
        assertTrue(
            UpdateSummary.statusLine(release("v1.2.2"), "1.2.1").contains("发现新版本 1.2.2"),
        )
    }
}
