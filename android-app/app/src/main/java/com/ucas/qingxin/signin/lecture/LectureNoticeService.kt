package com.ucas.qingxin.signin.lecture

import com.ucas.qingxin.signin.BuildConfig
import com.ucas.qingxin.signin.network.ApiException
import com.ucas.qingxin.signin.util.HostScrub
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

/**
 * 人文讲座通知的数据源（人文学院网站）。
 *
 * ## 抓取入口的选择
 * 两个栏目各有两个可用入口，优先级不同：
 * 1. **RSS**（`<栏目地址>?format=feed&type=rss`）：UTF-8 XML，字段规整，
 *    是 Joomla 自带的正规输出 —— **首选**；
 * 2. **栏目 HTML 列表页**：只在 RSS 失败时回退（站点改配置把 RSS 关掉的情况）。
 *
 * 实测两个入口都**只认浏览器 UA**：不带 UA 的请求会被直接拒（HTTP 503），
 * 因此 [USER_AGENT] 是必需项而不是礼貌项。
 *
 * ## 隐私红线（务必保持）
 * 校内主机名与路径**不写进本文件**，而是在构建时由本地 `local.properties`
 * 注入 `BuildConfig`（与 iClass 端点同一套机制，见 `android-app/app/build.gradle.kts` 顶部说明）。
 * 因此本文件可以安全地公开：里面没有任何可用的校内端点。
 * 同理，[LectureNotice.detailUrl] 只保存**相对路径**。
 *
 * ## 数据边界
 * 这里拿不到讲座的具体时间与会场（见 [LectureNotice] 的说明），
 * 所以本数据源只用于「预告展示 + 新预告通知」，不参与任何签到流程。
 */
class LectureNoticeService(
    private val client: OkHttpClient = defaultClient(),
    private val sources: List<Source> = defaultSources(),
) {

    /**
     * 并发抓取全部栏目并按发布日期倒序合并。
     *
     * **容错策略**：任一栏目成功就返回其并集；只有全部失败才抛异常
     * （调用方据此回退本地缓存）。两个栏目体量都很小（几十 KB），
     * 弱网下其中一个超时是常态，不该因此把另一份可用数据也丢掉。
     */
    suspend fun fetchAll(): List<LectureNotice> = withContext(Dispatchers.IO) {
        val results = sources.map { source ->
            async {
                try {
                    Result.success(fetchOne(source))
                } catch (e: CancellationException) {
                    // 协程取消（用户连点刷新、页面退出）不是「数据源失败」，
                    // 必须继续向上传播，否则刷新会停不下来。
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
        }.awaitAll()

        val merged = ArrayList<LectureNotice>()
        var firstFailure: ApiException? = null
        var succeeded = 0
        for (result in results) {
            val notices = result.getOrNull()
            if (notices != null) {
                merged += notices
                succeeded++
            } else {
                val cause = result.exceptionOrNull()
                val api = cause as? ApiException
                    ?: ApiException(
                        "NOTICE_UNAVAILABLE",
                        HostScrub.scrub(cause?.message ?: "讲座通知数据源暂时不可用"),
                    )
                if (firstFailure == null) firstFailure = api
            }
        }
        if (succeeded == 0) {
            throw firstFailure ?: ApiException("NOTICE_UNAVAILABLE", "讲座通知数据源暂时不可用")
        }
        merged.sortedWith(MOST_RECENT_FIRST)
    }

    /** 单个栏目：RSS 优先，HTML 列表页回退。 */
    private suspend fun fetchOne(source: Source): List<LectureNotice> {
        var lastError: ApiException? = null
        for (target in listOf(rssUrl(source.listUrl) to true, source.listUrl to false)) {
            val (url, isRss) = target
            try {
                val body = getText(url)
                val parsed = if (isRss) {
                    LectureNoticeParser.parseRss(body, source.type)
                } else {
                    LectureNoticeParser.parseListHtml(body, source.type)
                }
                // 解析成功但为空：视为失败并继续尝试下一个入口。
                // 站点改版后 HTML 正则可能「不报错但什么都匹配不到」，
                // 若把空结果当成功返回，用户看到的会是「一条讲座都没有」而不是回退错误。
                if (parsed.isNotEmpty()) return parsed
                lastError = ApiException("NOTICE_EMPTY", "讲座通知解析为空")
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                lastError = e
            }
        }
        throw lastError ?: ApiException("NOTICE_UNAVAILABLE", "讲座通知数据源暂时不可用")
    }

    /**
     * 读取响应体文本。
     *
     * 字符集按三级判定：HTTP 头 `charset` → 正文声明（XML `encoding=` / HTML `charset=`）→ UTF-8。
     * 之所以要自己判定而不是让 OkHttp 直接 `body.string()`：实测该站点的 HTML 声明为 UTF-8
     * 而 RSS 声明为 `utf-8`，看起来一致，但一旦站点切换为 GBK，`body.string()` 会静默
     * 产出乱码（而不是抛异常）——最后表现为「标题全变成问号」，很难排查。
     */
    private fun getText(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/rss+xml, application/xml, text/html;q=0.9, */*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("Cache-Control", "no-store")
            .get()
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw ApiException(
                        "NOTICE_HTTP_${response.code}",
                        "讲座通知请求失败 (${response.code})",
                    )
                }
                val bytes = response.body?.bytes() ?: ByteArray(0)
                decode(bytes, response.header("Content-Type"))
            }
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            // 连不上/超时同样归到「数据源不可用」，并统一成 ApiException，
            // 这样 fetchAll 的逐源容错与仓库的缓存回退都只需认识一种异常类型。
            // 文案要脱敏：`e.message` 可能带完整主机名（见 HostScrub）。
            throw ApiException(
                "NOTICE_IO",
                HostScrub.scrub("讲座通知连接失败：${e.message ?: "网络不可用"}"),
            )
        }
    }

    companion object {
        /**
         * 详情页的完整地址（仅在用户点「查看详情」时用于跳浏览器）。
         *
         * 主机名来自 `BuildConfig`（构建时由本地配置注入），因此本文件里
         * 仍然没有任何校内端点。未配置时返回空串，界面据此不显示可点击态。
         */
        fun detailPageUrl(relativePath: String): String {
            val path = relativePath.trim()
            if (path.isEmpty()) return ""
            val base = runCatching { BuildConfig.RENWEN_BASE_URL.trim().trimEnd('/') }
                .getOrDefault("")
            if (base.isEmpty()) return ""
            return base + if (path.startsWith("/")) path else "/$path"
        }

        /**
         * 默认抓取源：地址来自 `BuildConfig`，绝不硬编码。
         *
         * `runCatching` 是防御性的：单测环境下若未注入这些字段（例如只跑纯解析用例的
         * 变体构建），这里应退化成空列表而不是让类初始化直接抛异常。
         */
        fun defaultSources(): List<Source> = runCatching {
            listOf(
                Source(BuildConfig.RENWEN_MINGDE_URL, LectureType.MINGDE),
                Source(BuildConfig.RENWEN_ART_URL, LectureType.ART_HUMANITY),
            ).filter { it.listUrl.isNotBlank() }
        }.getOrDefault(emptyList())

        /** RSS 入口：Joomla 的通用 feed 参数。 */
        internal fun rssUrl(listUrl: String): String {
            val base = listUrl.trim()
            if (base.isEmpty()) return base
            val separator = if (base.contains('?')) '&' else '?'
            return "$base${separator}format=feed&type=rss"
        }

        /**
         * 浏览器 UA。
         *
         * **必需**：实测不带浏览器 UA 时站点直接返回 HTTP 503，
         * 带上后 200。这不是伪装成别的产品，而是该站点对非浏览器客户端的既定策略。
         */
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

        /**
         * 按发布日期**倒序**：最新发布的预告排在最前。日期缺失的排在最后。
         *
         * 实现集中在 [LectureSessions] 里（那里还要按期次号给「同一场次」排序），
         * 这里只是转发一个别名，避免两处排序口径各自漂移。
         */
        val MOST_RECENT_FIRST: Comparator<LectureNotice> = LectureSessions.MOST_RECENT_FIRST

        /** 默认客户端：站点在校园网内，但校外访问也可能很慢，读超时给足。 */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        /**
         * 字符集三级判定。见 [getText] 的说明。
         *
         * 解析失败一律退回 UTF-8：宁可能出现个别乱码，也不要因为一个未知的
         * 字符集名字让整页内容拿不到。
         */
        internal fun decode(bytes: ByteArray, contentType: String?): String {
            val charset = charsetFromHeader(contentType)
                ?: charsetFromBody(bytes)
                ?: Charsets.UTF_8
            return runCatching { String(bytes, charset) }.getOrElse { String(bytes, Charsets.UTF_8) }
        }

        private fun charsetFromHeader(contentType: String?): Charset? {
            val header = contentType.orEmpty()
            val match = CHARSET_PARAM.find(header) ?: return null
            return charsetOrNull(match.groupValues[1])
        }

        /** 只嗅探开头 1 KB：字符集声明一定在文档最前面，全量扫描没有意义。 */
        private fun charsetFromBody(bytes: ByteArray): Charset? {
            val head = String(bytes, 0, minOf(bytes.size, 1024), Charsets.ISO_8859_1)
            val match = CHARSET_PARAM.find(head) ?: return null
            return charsetOrNull(match.groupValues[1])
        }

        private fun charsetOrNull(name: String): Charset? =
            runCatching { Charset.forName(name.trim().trim('"', '\'')) }.getOrNull()

        /** 同时覆盖 HTTP 头的 `charset=utf-8` 与文档里的 `encoding="utf-8"` / `charset=utf-8`。 */
        private val CHARSET_PARAM = Regex("""(?:charset|encoding)\s*=\s*["']?([A-Za-z0-9_\-]+)""", RegexOption.IGNORE_CASE)
    }

    /**
     * 一个栏目：列表页地址 + 它对应的类型。
     *
     * [listUrl] 来自 `BuildConfig`（本地配置注入），本文件内不出现任何校内主机。
     */
    data class Source(val listUrl: String, val type: LectureType)
}
