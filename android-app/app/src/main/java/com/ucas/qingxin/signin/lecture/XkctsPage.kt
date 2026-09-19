package com.ucas.qingxin.signin.lecture

/**
 * 判断 WebView 当前停在哪个页面 —— **纯函数**，因此可以离线把「登录跳转的各种落点」
 * 全部走一遍。
 *
 * ## 为什么需要它，而不是在界面里直接比对 URL
 * 登录流程会把用户带到好几个地方（工作台、SSO、栏目页、可能还有二次验证页），
 * 界面需要据此说清「现在能不能读」。而这个判断如果写在 Composable 里，
 * 就只能靠真机手动点着试 —— 而它恰恰是**最容易出错**的一环
 * （判错了表现成「永远是未登录」或者「读了半天什么都没有」）。
 *
 * ## 隐私
 * 这些函数只做**布尔判断**，不回传、不记录 URL 本身。
 * SSO 跳转链接里带一次性票据，把它存进状态或写进日志都是泄露。
 * 因此调用方只应把结果（是否在栏目页）放进界面状态，**不要**把 URL 放进去。
 */
internal object XkctsPage {

    /**
     * 当前 URL 是否就是那个讲座栏目的页面。
     *
     * 比较时**忽略查询串与锚点、忽略末尾斜杠**，但**保留大小写**：
     * 路径在服务端通常是大小写敏感的，而 `?ticket=...` 这类参数必须忽略 ——
     * 否则带票据的落地地址会被判成「不在栏目页」，用户明明登录成功了却读不了。
     */
    fun isSchedulePage(url: String, target: String): Boolean {
        val left = normalize(url)
        val right = normalize(target)
        return left.isNotEmpty() && left == right
    }

    /**
     * 当前 URL 属于哪个栏目；都不匹配时返回 `null`。
     *
     * 两个栏目各自是一个独立页面，读取时必须知道自己读的是哪一个 ——
     * 否则人文讲座会被标成科学前沿讲座（或者反过来）。
     */
    fun kindOf(url: String, humanity: String, science: String): LectureEventKind? = when {
        isSchedulePage(url, humanity) -> LectureEventKind.HUMANITY
        isSchedulePage(url, science) -> LectureEventKind.SCIENCE
        else -> null
    }

    /**
     * 是否停在预约系统的「会话失效」跳转页（`…/redirect`）。
     *
     * 未登录直开栏目页会 302 到这里；页面里的脚本会再等 3 秒跳到 SEP `/appStore`。
     * 那一步在 WebView 里经常变成**裸 401 JSON**（见 [isAuthFailurePayload]），
     * 因此本页一出现就该立刻改去 SEP 登录页，不要等那 3 秒。
     */
    fun isSessionRedirect(url: String, xkctsBase: String): Boolean {
        val page = normalize(url)
        val base = normalize(xkctsBase)
        if (page.isEmpty() || base.isEmpty()) return false
        return page == "$base/redirect"
    }

    /**
     * 页面正文是否就是 SEP 返回的「未登录」JSON。
     *
     * 实测形态：`{"code":401,"msg":"未登录或会话已过期","toUrl":"/"}`。
     * 它只在请求被当成 API（`Accept: application/json` 或 `X-Requested-With`）时出现；
     * WebView 一旦把它当主文档渲染，用户就只看到这一行，进不了登录表单。
     *
     * 判定刻意宽松：只认 `code:401` + 「未登录 / 会话」关键字，不依赖字段顺序。
     */
    fun isAuthFailurePayload(body: String): Boolean {
        val text = body.trim()
        if (text.length !in 20..400) return false
        if (!text.startsWith("{") || !text.contains("401")) return false
        return text.contains("未登录") || text.contains("会话已过期") || text.contains("会话已失效")
    }

    /**
     * 去掉查询串、锚点与末尾斜杠；无法解析时返回空串。
     *
     * 返回空串（而不是原串）是有意的：一个解析不出主机的地址不该被拿去和栏目地址
     * 做字符串比较 —— 那只会碰巧相等或碰巧不等，两种结果都不可靠。
     */
    private fun normalize(url: String): String {
        val text = url.trim()
        if (text.isEmpty()) return ""
        val cut = text.substringBefore('?').substringBefore('#').trimEnd('/')
        // 必须带协议前缀才算一个完整地址；`about:blank`、`data:...` 这类页面会被挡掉。
        if (!cut.startsWith("http://") && !cut.startsWith("https://")) return ""
        return cut
    }
}
