package com.ucas.qingxin.signin.lecture

/**
 * 讲座列表会话跳转 —— 对齐上游 UCAS-Desktop 的 `establishLectureSession`
 *（`patches/lecture-portal.ts` + `lecture-sep-workbench.patch`）。
 *
 * ## 为什么不能「登录 SEP 后直开栏目页」
 * 预约系统的 `SESSION` 不是 SEP 登录本身给的，而是：
 *
 * ```
 * SEP 工作台 →「选课系统」门户跳转（带票据）
 *           → xkgo 选课主页
 *           → 菜单「人文讲座 / 科学前沿讲座」下的「讲座预告」（再次 SSO）
 *           → xkcts 栏目页
 * ```
 *
 * 直开 `/subject/humanityLecture` 只会落到 `/redirect`，再被扔回 `/appStore`；
 * 在 WebView 里后者还经常渲染成裸 `{"code":401,...}`。桌面端的修复正是
 * **从当前菜单取会话跳转链接，不硬编码个人票据** —— 本对象把同一套规则
 * 收成可离线测试的纯函数 + 可注入的查找脚本。
 *
 * ## 隐私
 * - 不记录、不回传完整 URL（票据在路径里）；
 * - 允许的主机集合由调用方从本地配置注入，源码不写死校内主机名。
 */
object LecturePortal {

    /**
     * 当前页面对会话建立而言属于哪一类表面。
     *
     * 状态机只认这些结论，不拿原始 URL 做分支 —— 避免票据进日志/界面状态。
     */
    enum class Surface {
        /** SEP 登录表单（尚未登录）。 */
        SEP_LOGIN,

        /** SEP 工作台 / 应用商店（已登录，可找「选课系统」）。 */
        SEP_WORKBENCH,

        /** 新版选课主页（可找隐藏菜单里的「讲座预告」）。 */
        XKGO_MAIN,

        /** 预约系统会话失效跳转页。 */
        XKCTS_REDIRECT,

        /** 目标讲座栏目页（路径已对齐）。 */
        SCHEDULE,

        /** 其它（二次验证、中间跳转等）。 */
        OTHER,
    }

    /**
     * 把相对/绝对 href 解析成可导航地址；非法或越权主机返回 null。
     *
     * 规则与上游 `resolvePortalHref` 一致：
     * - 拒绝 `#` / `javascript:` / 带 userinfo 的地址；
     * - 只允许 [allowedHosts] 里的主机；
     * - SEP 主机上的 `http://` 升为 `https://`（新版菜单仍会吐出 http 链接）。
     */
    fun resolvePortalHref(href: String, baseUrl: String, allowedHosts: Set<String>): String? {
        val raw = href.trim()
        if (raw.isEmpty() || raw.startsWith("#") || raw.startsWith("javascript:", ignoreCase = true)) {
            return null
        }
        val resolved = runCatching {
            if (baseUrl.isBlank()) java.net.URI(raw) else java.net.URI(baseUrl).resolve(raw)
        }.getOrNull() ?: return null
        if (resolved.scheme != "http" && resolved.scheme != "https") return null
        if (!resolved.userInfo.isNullOrEmpty()) return null
        val host = resolved.host?.lowercase().orEmpty()
        if (host.isEmpty() || host !in allowedHosts) return null
        // 新版选课菜单仍会吐出 http 的 SEP 链接；升为 https（与上游 resolvePortalHref 一致）。
        if (resolved.scheme == "http") {
            return java.net.URI(
                "https",
                resolved.userInfo,
                resolved.host,
                resolved.port,
                resolved.path,
                resolved.query,
                resolved.fragment,
            ).toString()
        }
        return resolved.toString()
    }

    /**
     * 判定当前 URL 属于哪类表面。
     *
     * @param scheduleTarget 人文或科研栏目的完整目标地址
     * @param sepLoginUrl SEP 登录页（用来取 SEP 主机）
     * @param xkgoMainUrl 选课主页完整地址
     * @param xkctsBase 预约系统根地址
     */
    fun surfaceOf(
        url: String,
        scheduleTarget: String,
        sepLoginUrl: String,
        xkgoMainUrl: String,
        xkctsBase: String,
    ): Surface {
        val page = normalize(url)
        if (page.isEmpty()) return Surface.OTHER
        if (XkctsPage.isSessionRedirect(url, xkctsBase)) return Surface.XKCTS_REDIRECT
        if (XkctsPage.isSchedulePage(url, scheduleTarget)) return Surface.SCHEDULE

        val sepHost = hostOf(sepLoginUrl)
        val xkgoHost = hostOf(xkgoMainUrl)
        val host = hostOf(url)
        val path = pathOf(url)

        if (sepHost.isNotEmpty() && host == sepHost) {
            return when {
                path in SEP_WORKBENCH_PATHS -> Surface.SEP_WORKBENCH
                path.isEmpty() || path == "/" || path.startsWith("/slogin") ||
                    path.contains("login", ignoreCase = true) -> Surface.SEP_LOGIN
                else -> Surface.SEP_WORKBENCH // 已登录后的其它 SEP 页也当作可找入口
            }
        }
        if (xkgoHost.isNotEmpty() && host == xkgoHost) {
            val mainPath = pathOf(xkgoMainUrl)
            if (path == mainPath || path.startsWith("$mainPath/")) return Surface.XKGO_MAIN
            return Surface.XKGO_MAIN
        }
        return Surface.OTHER
    }

    /** 从已配置的地址收集允许导航的主机名（小写）。 */
    fun allowedHosts(sepLoginUrl: String, xkgoMainUrl: String, xkctsBase: String): Set<String> =
        listOf(sepLoginUrl, xkgoMainUrl, xkctsBase)
            .map { hostOf(it) }
            .filter { it.isNotEmpty() }
            .toSet()

    fun hostOf(url: String): String =
        runCatching { java.net.URI(url.trim()).host?.lowercase().orEmpty() }.getOrDefault("")

    fun pathOf(url: String): String {
        val raw = runCatching { java.net.URI(url.trim()).path.orEmpty() }.getOrDefault("")
        return raw.trimEnd('/')
    }

    private fun normalize(url: String): String {
        val text = url.trim()
        if (text.isEmpty()) return ""
        val cut = text.substringBefore('?').substringBefore('#').trimEnd('/')
        if (!cut.startsWith("http://") && !cut.startsWith("https://")) return ""
        return cut
    }

    /** SEP 已登录工作台路径（与上游 `logged_in` / `isAppStorePage` 对齐）。 */
    private val SEP_WORKBENCH_PATHS = setOf(
        "/sepCard/card",
        "/appStore",
        "/appStore/appIndex",
        "/appStoreStudent",
    )

    /**
     * 在当前文档里找「选课系统 / 人文讲座报名」入口。
     *
     * 返回 JSON 字符串：`{"href":"..."}` 或 `{"href":""}`。
     * 查找顺序与上游 `findPortalEntry` 一致。
     */
    const val FIND_PORTAL_SCRIPT: String = """
        (function () {
          function textOf(a) { return (a.textContent || '').replace(/\s+/g, ' ').trim(); }
          var links = Array.prototype.slice.call(document.querySelectorAll('a[href]')).map(function (a) {
            return { text: textOf(a), href: a.getAttribute('href') || '' };
          }).filter(function (x) { return !!x.href; });
          function pick(pred) {
            for (var i = 0; i < links.length; i++) if (pred(links[i])) return links[i].href;
            return '';
          }
          var href = pick(function (a) { return /人文讲座(?:报名|预约)/.test(a.text); })
            || pick(function (a) { return /\/subject\/humanity(?:Lecture|Notice)/.test(a.href); })
            || pick(function (a) { return a.text === '选课系统' || a.text === '选课'; });
          // 欢迎弹窗挡住点击时先关掉（上游点 #sepWelcomeOk）
          try {
            var ok = document.getElementById('sepWelcomeOk');
            if (ok) ok.click();
          } catch (e) {}
          return JSON.stringify({ href: href || '' });
        })()
    """

    /**
     * 在选课主页找指定栏目下的「讲座预告」桥接链接。
     *
     * @param science true = 科学前沿讲座；false = 人文讲座
     */
    fun findBridgeScript(science: Boolean): String {
        val heading = if (science) "科学前沿讲座" else "人文讲座"
        val pathHint = if (science) "/subject/lecture" else "/subject/humanityLecture"
        // 注意：heading / pathHint 都是我们控制的常量，不是用户输入。
        return """
            (function () {
              var headingText = ${jsonString(heading)};
              var pathHint = ${jsonString(pathHint)};
              function norm(s) { return (s || '').replace(/\s+/g, '').trim(); }
              var anchors = Array.prototype.slice.call(document.querySelectorAll('a[href]'));
              var heading = anchors.find(function (a) { return norm(a.textContent) === headingText; });
              var menu = heading && heading.closest('li');
              if (menu) {
                var target = Array.prototype.slice.call(menu.querySelectorAll('a[href]')).find(function (a) {
                  return /^(讲座预告|人文讲座报名|人文讲座预约)$/.test((a.textContent || '').trim());
                });
                if (target) return JSON.stringify({ href: target.getAttribute('href') || '' });
              }
              var fallback = anchors.find(function (a) {
                return (a.getAttribute('href') || '').indexOf(pathHint) >= 0;
              });
              return JSON.stringify({ href: fallback ? (fallback.getAttribute('href') || '') : '' });
            })()
        """.trimIndent()
    }

    /** 当前页是否已出现讲座时间表（表头同时含「讲座时间」「讲座名称」）。 */
    const val HAS_SCHEDULE_TABLE_SCRIPT: String = """
        (function () {
          var heads = Array.prototype.slice.call(document.querySelectorAll('table th'))
            .map(function (th) { return (th.textContent || '').replace(/\s+/g, ''); });
          var ok = heads.some(function (t) { return t.indexOf('讲座时间') >= 0; })
            && heads.some(function (t) { return t.indexOf('讲座名称') >= 0; });
          return JSON.stringify({ ok: !!ok });
        })()
    """

    /** 从 FIND_* 脚本的返回值里取出 href。 */
    fun parseHrefResult(raw: String?): String {
        val text = unquote(raw)
        if (text.isEmpty()) return ""
        return runCatching {
            org.json.JSONObject(text).optString("href").trim()
        }.getOrDefault("")
    }

    fun parseOkResult(raw: String?): Boolean {
        val text = unquote(raw)
        if (text.isEmpty()) return false
        return runCatching { org.json.JSONObject(text).optBoolean("ok") }.getOrDefault(false)
    }

    private fun jsonString(value: String): String =
        org.json.JSONObject.quote(value)

    private fun unquote(raw: String?): String {
        val text = raw?.trim().orEmpty()
        if (text.length >= 2 && text.first() == '"' && text.last() == '"') {
            return text.substring(1, text.length - 1)
                .replace("\\\"", "\"")
                .replace("\\n", "\n")
                .replace("\\\\", "\\")
        }
        return text
    }
}
