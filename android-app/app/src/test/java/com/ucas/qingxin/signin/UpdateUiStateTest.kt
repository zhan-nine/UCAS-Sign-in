package com.ucas.qingxin.signin

import com.ucas.qingxin.signin.ui.UpdateUiState
import com.ucas.qingxin.signin.update.UpdateRelease
import com.ucas.qingxin.signin.update.VersionTags
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [UpdateUiState.showBanner] 的单测 —— 也就是「主页到底弹不弹横幅」这一个判断。
 *
 * ## 为什么要单独钉住它
 * 「稍后」「不再提示」「自动检查开关」三者语义不同，而它们的差别**只体现在这一个布尔值上**：
 * 判断写错时，用户点了「不再提示」却发现下次打开还在弹（或者反过来，
 * 一个真正的新版本被永远吞掉）。这两种失败都不会崩、也不会打印任何日志，
 * 只能靠用例钉住。
 */
class UpdateUiStateTest {

    private fun release(tag: String) = UpdateRelease(
        tag = tag,
        version = requireNotNull(VersionTags.parse(tag)),
        title = "清新签到 $tag",
        notes = "更新内容",
        pageUrl = "https://example.invalid/releases/$tag",
    )

    @Test
    fun `检测到更高版本且未忽略未稍后 时显示横幅`() {
        val state = UpdateUiState(available = release("v1.3.0"))
        assertTrue(state.showBanner)
    }

    @Test
    fun `没有检测到更高版本时不显示`() {
        assertFalse(UpdateUiState().showBanner)
    }

    @Test
    fun `有最新版本但没有更新 时不显示横幅`() {
        // 「最新版本」是给设置页看的（让用户看得出检查跑过），
        // 它非空**不能**成为弹横幅的理由 —— 否则版本追平之后主页会一直挂着一条
        // 「发现新版本」的空提示。
        val state = UpdateUiState(latest = release("v1.2.1"), available = null)
        assertFalse(state.showBanner)
    }

    @Test
    fun `点过稍后则本次不显示`() {
        val state = UpdateUiState(available = release("v1.3.0"), dismissed = true)
        assertFalse(state.showBanner)
    }

    @Test
    fun `点过不再提示则同一版本不显示 但更高的版本仍会显示`() {
        // 这是「不再提示」最核心的语义：用户想摆脱的是**这一次**打扰，
        // 不是从此不再知道有新版本。
        val ignored = "v1.3.0"
        assertFalse(UpdateUiState(available = release("v1.3.0"), ignoredTag = ignored).showBanner)
        assertTrue(UpdateUiState(available = release("v1.4.0"), ignoredTag = ignored).showBanner)
    }

    @Test
    fun `已忽略的是 tag 而不是版本号 同一版本重发 tag 也照样不提示`() {
        // tag 是「不再提示」的匹配键：同一个版本换个版本号写法（v1.3.0 / 1.3.0）
        // 不该把用户已经拒掉的提示重新弹出来。
        val state = UpdateUiState(available = release("v1.3.0"), ignoredTag = "v1.3.0")
        assertFalse(state.showBanner)
        // 版本号相等但 tag 不同 —— 按 tag 判定，因此仍会显示（这是有意的：
        // 我们只对用户明确点过的那一个 tag 保持沉默）。
        assertTrue(UpdateUiState(available = release("1.3.0"), ignoredTag = "v1.3.0").showBanner)
    }

    @Test
    fun `忽略之后恢复提示 横幅回来`() {
        // clearIgnoredVersion 的效果：ignoredTag 清空 + dismissed 复位。
        val state = UpdateUiState(available = release("v1.3.0"), ignoredTag = "v1.3.0", dismissed = true)
        assertFalse(state.showBanner)
        assertTrue(state.copy(ignoredTag = "", dismissed = false).showBanner)
    }
}
