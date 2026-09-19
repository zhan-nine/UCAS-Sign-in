package com.ucas.qingxin.signin

import com.ucas.qingxin.signin.util.HostScrub
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * [HostScrub] 的单测。
 *
 * 这是隐私红线的**最后一道网**：一旦它失灵，校内主机名与端口就会顺着
 * 错误文案进入用户界面、bug 截图与用户复制出去的报告。
 * 因此这里的用例一半来自**真实踩过的坑**，而不是凭空构造。
 */
class HostScrubTest {

    @Test
    fun `真实踩过的坑 明文流量被拦的文案里是裸主机名`() {
        // 原文形如：CLEARTEXT communication to <某主机> not permitted by network security policy
        // 注意它既没有协议前缀，也不是 URL —— 原先只匹配 scheme://authority 的规则挡不住。
        val raw = "课程注册表连接失败：CLEARTEXT communication to iclass.example.edu.cn not permitted"
        val cleaned = HostScrub.scrub(raw)
        assertFalse("裸主机名必须被替换掉", cleaned.contains("iclass.example.edu.cn"))
        assertEquals("课程注册表连接失败：CLEARTEXT communication to <host> not permitted", cleaned)
    }

    @Test
    fun `带协议的地址整段替换 路径与查询串也不能留`() {
        // 只替换主机名是不够的：路径与查询串同样暴露了「有哪个接口」，
        // 而红线明确包含这部分内容。
        val raw = "连接 https://iclass.example.edu.cn:9999/ve/back/courseInfo.shtml?method=getStuDetail&cId=1 失败"
        assertEquals("连接 <url> 失败", HostScrub.scrub(raw))
    }

    @Test
    fun `URL 后的中文标点不会被吞掉`() {
        // 中文里「地址：https://…。详情…」很常见，逗号/句号应留在正文里。
        assertEquals(
            "见 <url>。详情另附",
            HostScrub.scrub("见 https://iclass.example.edu.cn/a/b。详情另附"),
        )
    }

    @Test
    fun `裸主机名带端口时端口一起去掉`() {
        // 端口也在「不泄露」之列，因此不能只替换主机名把 `:88` 留下。
        val cleaned = HostScrub.scrub("Failed to connect to iclass.example.edu.cn:88")
        assertEquals("Failed to connect to <host>", cleaned)
    }

    @Test
    fun `IP 形态同样被替换`() {
        // 内网 IP 是最典型的校内端点，不能漏。注意它没有字母，
        // 因此必须由 BARE_HOST 里的 IPv4 分支兜住。
        val cleaned = HostScrub.scrub("Failed to connect to /10.12.34.56:9999")
        assertEquals("Failed to connect to /<host>", cleaned)
    }

    @Test
    fun `四段版本号会被误伤 三段及以下不会`() {
        // 这是 IPv4 分支的已知代价：形态上 `5.6.7.8` 与 IP 无法区分，
        // 而漏掉一个内网 IP 的代价远大于抹掉一个版本号，因此选择误伤。
        assertEquals("版本 <host>", HostScrub.scrub("版本 5.6.7.8"))
        assertEquals("版本 2.4.7", HostScrub.scrub("版本 2.4.7"))
    }

    @Test
    fun `多段主机名整段替换 不留下尾部段落`() {
        // 贪婪量词的回归用例：用懒惰量词时只会匹配到 `a.b`，把 `.c.d` 漏在外面。
        val cleaned = HostScrub.scrub("host a.b.c.d unreachable")
        assertEquals("host <host> unreachable", cleaned)
    }

    @Test
    fun `版本号不会被误伤`() {
        // 规则 2 要求第一段含字母，否则 `2.4.7` 会被当成主机名替换掉 ——
        // 那会让「应用版本 2.4.7 初始化失败」这类文案变得不可读。
        assertEquals("版本 2.4.7 初始化失败", HostScrub.scrub("版本 2.4.7 初始化失败"))
        assertEquals("浮点 3.14", HostScrub.scrub("浮点 3.14"))
    }

    @Test
    fun `不含端点的文案原样返回`() {
        val raw = "当前不在签到时间内（第 3-4 节 08:30-10:05）"
        assertEquals(raw, HostScrub.scrub(raw))
    }

    @Test
    fun `同一文案里的多个端点全部替换 含带协议与裸地址混用`() {
        val cleaned = HostScrub.scrub(
            "从 a.example.edu.cn 跳到 https://b.example.edu.cn:443/x 再退回 10.1.2.3:88",
        )
        assertEquals("从 <host> 跳到 <url> 再退回 <host>", cleaned)
    }

    @Test
    fun `主机名紧跟路径时只替换主机名 但带扩展名的文件名也会被误伤`() {
        // 这条同时钉住两件事（见 HostScrub 的「已知的取舍」）：
        // 1. 规则 2 不吃路径，所以 `/ve/back/` 留在原位，文案仍可读；
        // 2. 但 `x.shtml` 这种「第一段含字母 + 有点号」的**文件名**在形态上与域名
        //    无法区分，因此会一并被替换 —— 这是有意接受的误伤：
        //    宁可抹掉一个文件名，也不放过一个可能的校内主机名。
        assertEquals(
            "<host>/ve/back/<host>",
            HostScrub.scrub("iclass.example.edu.cn/ve/back/x.shtml"),
        )
    }
}
