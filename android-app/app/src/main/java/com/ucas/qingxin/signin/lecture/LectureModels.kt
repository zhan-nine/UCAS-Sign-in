package com.ucas.qingxin.signin.lecture

import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * 人文讲座的栏目（= 数据源）。
 *
 * 每一个 [LectureType] 恰好对应人文学院网站上的一个通知栏目，因此**不需要**任何
 * 关键词分类：来源本身就是类型。这取代了此前「靠课程名猜讲座、再猜分类」的做法
 * （那套做法会把普通课程误判为讲座，是「抓到的是一门课程」的根因）。
 */
enum class LectureType(val label: String) {
    /** 明德讲堂（期次号形如 `M1167`）。 */
    MINGDE("明德讲堂"),

    /** 艺术与人文修养讲座系列（期次号是纯数字，如 `213`）。 */
    ART_HUMANITY("艺术与人文修养"),
}

/**
 * 一条通知的性质：预告、报道，还是没标记的通知。
 *
 * 刻意从标题里解析而不是靠日期推断：网站把两类文章发在同一个栏目里，
 * 标题里的「预告 / 报道」二字是最可靠的信号。
 *
 * **注意「没有标记」不等于「已讲完」**：实测大量条目根本不带任何标记
 * （艺术系列 10 条里 8 条如此），所以 [OTHER] 只能说明「无从判断」，
 * 界面上仍按「未讲完」对待（见 [LectureNotice.actionable]）。
 */
enum class LectureNoticeKind(val label: String) {
    PREVIEW("预告"),
    REPORT("报道"),

    /** 标题里没有任何会话标记，无从判断，按「未讲完」对待。 */
    OTHER("通知"),
}

/**
 * 一条人文讲座通知。
 *
 * ## 字段边界（重要）
 * 人文学院网站的通知**只提供**标题、期次号、发布日期与详情页链接。
 * 实测预告详情页里**没有**讲座的具体时间与会场（正文多为图片），
 * 因此本模型刻意不含 `beginTime` / `venue` 之类的字段 —— 与其给出一个猜的
 * 时间，不如老实承认「这里只有预告」。
 *
 * 时间与会场的权威来源是**校内的讲座预约系统**；预告的作用是更早地提醒用户去预约。
 */
data class LectureNotice(
    val type: LectureType,
    /** 期次号：明德为 `M1167`，艺术系列为 `213`；解析不出时为空串。 */
    val sessionNo: String,
    /** 去掉栏目名与期次号前缀后的标题正文（见 [LectureNoticeParser.cleanTitle]）。 */
    val title: String,
    /** 发布日期（`yyyy-MM-dd`）。注意这是**发文日**，不是讲座举办日。 */
    val publishedDate: String,
    val kind: LectureNoticeKind = LectureNoticeKind.OTHER,
    /** 详情页的**相对路径**（如 `/index.php/rczp/.../57442-m1167`），见类注释的隐私说明。 */
    val detailUrl: String = "",
) {
    /**
     * 界面列表的稳定 key。
     *
     * 由「类型 + 期次号 + 发布日期 + 标题」派生确定性 UUID：同一条通知在多次刷新、
     * 进程重启后必须得到同一个 id，否则 `LazyColumn` 的 `key` 会失效（列表滚动位置
     * 与动画错乱），重复 key 更会直接抛异常闪退。
     *
     * 之所以把标题也算进去：期次号可能解析不出（为空串），只靠发布日期会让同一天
     * 的多条通知撞成同一个 id。
     */
    val id: String
        get() {
            val seed = "${type.name}|$sessionNo|$publishedDate|$title"
            return "n" + UUID.nameUUIDFromBytes(seed.toByteArray(StandardCharsets.UTF_8))
                .toString()
                .replace("-", "")
                .take(16)
        }

    /**
     * 「是否值得当作新讲座消息」。
     *
     * **注意它只排除明确标了「报道 / 纪要 / 综述」的条目**，不负责判断「这场讲完了没」——
     * 那是 [LectureSessions.endedKeys] 的事。二者不能合并成一个判断，
     * 因为「有没有报道」和「有没有标记」是两件事：
     * 一条**预告**完全可能对应一场**已经讲完**的讲座（报道还没发出来）。
     */
    val actionable: Boolean
        get() = kind != LectureNoticeKind.REPORT

    /**
     * 「是否已见过」的判定键，供新预告通知去重使用。
     *
     * 刻意**不含标题**：网站会修订标题里的错别字，若标题参与判定，
     * 一次改字就会让同一条通知被当成「新预告」再推一次。
     */
    val identityKey: String
        get() = buildString {
            append(publishedDate).append('|').append(type.name).append('|')
            if (sessionNo.isNotBlank()) append(sessionNo) else append(title)
        }

    /**
     * **归并键**：同一场讲座的多条通知（预告 + 报道）必须得到同一个归并键。
     *
     * 与 [identityKey] 的区别是本模型里最容易用错的一处，两者用途**相反**：
     * - [identityKey] 用于「这条通知见过没」，要求**区分**预告与报道（它们是两条不同文章）；
     * - [groupKey] 用于「这是不是同一场讲座」，要求**合并**二者（预告与报道说的是同一场）。
     *
     * 因此 [groupKey] 刻意**不含**会话性质与发布日期：
     * 优先用期次号（`明德讲堂M1167` 的预告与报道必然都带 `M1167`），
     * 解析不出版次号时才退化为「类型 + 日期 + 标题」。
     */
    val groupKey: String
        get() = buildString {
            append(type.name).append('|')
            if (sessionNo.isNotBlank()) append(sessionNo) else append(publishedDate).append('|').append(title)
        }

    /** 期次号的数字部分，仅用于排序（大的更新）；解析不出返回 -1。 */
    val sessionOrdinal: Int
        get() = SESSION_DIGITS.find(sessionNo)?.value?.toIntOrNull() ?: -1

    private companion object {
        val SESSION_DIGITS = Regex("""\d+""")
    }
}

/**
 * [LectureRepository] 的取数结果。
 *
 * 刻意**没有** `fromCache` 标记：讲座列表只有「刚从网页抓到」这一种来源，
 * 不再有缓存回退（理由见 [LectureRepository] 的说明）。
 *
 * 带上 [endedGroupKeys] 而不是让上层自己算：`notices` 已经按场次合并过了，
 * 「同一场次里同时有预告与报道」这个判据**只在合并前**成立。合并的职责在仓库，
 * 于是「哪些场次已结束」也必须由仓库一并给出，否则上层拿到的是残缺的输入。
 */
data class NoticeQueryResult(
    val notices: List<LectureNotice>,
    val message: String,
    /** 已确认结束的场次（[LectureNotice.groupKey]），见 [LectureSessions.endedKeys]。 */
    val endedGroupKeys: Set<String> = emptySet(),
)

/**
 * 讲座通知的解析器（纯函数，**不触网**）。
 *
 * 独立成对象而不是塞进抓取器：人文学院网站是第三方 Joomla 站点，板块结构随时可能
 * 调整；把解析隔离出来就能用真实报文离线回归，而不必真的去请求校园网。
 *
 * 两种报文都要支持（见 [LectureNoticeService]）：
 * - RSS（首选，UTF-8 XML，字段规整）；
 * - 栏目 HTML 列表页（回退，`<ul class="b-list">` 里的 `li` 结构）。
 */
internal object LectureNoticeParser {

    /**
     * 明德讲堂的期次号形态：`M1167`。
     *
     * 刻意**不用 `\b`**：`\b` 的含义取决于 `\w` 的字符集，而它在 Java 与 Python
     * 里的默认口径并不一致（前者 ASCII、后者 Unicode），一个「看起来一样」的正则
     * 会在两种环境里给出不同结果。这里改成显式的「后面不能再跟数字」，
     * 语义一目了然，也不会因为前面是汉字而失效。
     */
    private val MINGDE_SESSION = Regex("""M(\d{2,4})(?!\d)""")

    /** 艺术系列的期次号形态：`213讲` / `第207讲`（允许「第」与空格）。 */
    private val ART_SESSION = Regex("""第?\s*(\d{1,3})\s*讲""")

    private val KIND_PREVIEW = Regex("""预告""")
    private val KIND_REPORT = Regex("""报道|纪要|综述""")

    /**
     * 去掉「栏目名 + 期次号 + 预告/报道 + 冒号」这一整段前缀，只留标题正文。
     *
     * 列表行里期次号与会话性质已由徽标单独展示，若标题再重复一遍
     * （「明德讲堂M1167预告：夯实科技发展的文化基础」），一行里会挤进三次同样的信息。
     *
     * 保留兜底：前缀之外没有内容时（标题只写了期次号），原样返回而不是给空串。
     */
    fun cleanTitle(raw: String): String {
        val text = raw.trim()
        if (text.isEmpty()) return text
        val match = TAIL.find(text) ?: return text
        val tail = match.groupValues[1].trim().trimStart(':', '：', '-', '—', '·', ' ').trim()
        return tail.ifBlank { text }
    }

    /**
     * 匹配「到标题正文之前」的那一段。
     *
     * 用惰性 `.*?` 一口气吃掉栏目名与期次号，再可选地吃掉预告/报道与分隔符；
     * 期次号之后必须紧跟（可选的）会话词与冒号，因此不会误吃到标题正文里的数字。
     *
     * 数字部分用**占有量词**（`\d{2,4}+` / `\d{1,3}+`）而不是贪婪量词，这是必要的：
     * 对「明德讲堂M1167」这种**没有正文**的标题，贪婪量词会退让一位让 `(.+)` 匹配上
     * 区区一个 `7`，于是剥离出「7」这种垃圾标题；占有量词不回溯，整个匹配直接失败，
     * 由 [cleanTitle] 的兜底原样返回。
     */
    private val TAIL = Regex(
        """^.*?(?:M\d{2,4}+|\d{1,3}+\s*讲)\s*(?:预告|报道|纪要)?\s*[:：]?\s*(.+)$""",
        RegexOption.DOT_MATCHES_ALL,
    )

    /** 从标题里抽期次号；解析不出返回空串（界面据此不显示徽标）。 */
    fun sessionNo(rawTitle: String, type: LectureType): String {
        val text = rawTitle.trim()
        return when (type) {
            LectureType.MINGDE -> {
                val m = MINGDE_SESSION.find(text) ?: return ""
                "M" + m.groupValues[1]
            }

            LectureType.ART_HUMANITY -> {
                val m = ART_SESSION.find(text) ?: return ""
                m.groupValues[1]
            }
        }
    }

    /** 从标题里判定预告 / 报道。 */
    fun kindOf(rawTitle: String): LectureNoticeKind = when {
        KIND_PREVIEW.containsMatchIn(rawTitle) -> LectureNoticeKind.PREVIEW
        KIND_REPORT.containsMatchIn(rawTitle) -> LectureNoticeKind.REPORT
        else -> LectureNoticeKind.OTHER
    }

    /**
     * 解析 RSS（首选通道）。
     *
     * 不引入 XML 解析器而用正则：RSS 由 Joomla 统一生成，`item` 结构极其规整，
     * 而正则版本在 JVM 单测里不需要额外的 XML 依赖（Android 的 `org.w3c.dom`
     * 在 mockable `android.jar` 下行为与真机不完全一致，用它反而会让解析用例失真）。
     */
    fun parseRss(xml: String, type: LectureType): List<LectureNotice> {
        if (xml.isBlank()) return emptyList()
        val out = ArrayList<LectureNotice>()
        for (item in ITEM.findAll(xml)) {
            val block = item.value
            val rawTitle = tagValue(block, "title") ?: continue
            val link = tagValue(block, "link").orEmpty()
            val pubDate = tagValue(block, "pubDate").orEmpty()
            val notice = build(rawTitle, link, pubDate, type) ?: continue
            out += notice
        }
        return dedupe(out)
    }

    /**
     * 解析栏目列表页 HTML（回退通道）。
     *
     * 目标结构（实测）：
     * ```
     * <li>
     *   <span class="m-date"> 2026-09-14 </span>
     *   <a href="/index.php/rczp/<栏目>/<id>-<slug>" target="_black">标题</a>
     * </li>
     * ```
     * 找不到任何条目时返回空列表 —— 由调用方决定是否算失败。
     */
    fun parseListHtml(html: String, type: LectureType): List<LectureNotice> {
        if (html.isBlank()) return emptyList()
        val out = ArrayList<LectureNotice>()
        for (m in LIST_ITEM.findAll(html)) {
            val published = m.groupValues[1].trim()
            val href = m.groupValues[2].trim()
            val rawTitle = unescape(m.groupValues[3])
                .replace(HTML_TAGS, " ")
                .trim()
            val notice = build(rawTitle, href, published, type) ?: continue
            out += notice
        }
        return dedupe(out)
    }

    /** 统一构造：三处入口共用，保证「解析出的字段口径」只有一份。 */
    private fun build(
        rawTitle: String,
        rawLink: String,
        rawPubDate: String,
        type: LectureType,
    ): LectureNotice? {
        val title = cleanTitle(rawTitle)
        if (title.isBlank()) return null
        val published = normalizeDate(rawPubDate)
        return LectureNotice(
            type = type,
            sessionNo = sessionNo(rawTitle, type),
            title = title,
            publishedDate = published,
            kind = kindOf(rawTitle),
            // 只留相对路径：详情页 URL 与抓取入口是同一个校内主机，
            // 把它整条存进模型等于在内存与缓存里留下校内端点（见 LectureNoticeService 的隐私说明）。
            detailUrl = toRelativePath(rawLink),
        )
    }

    /**
     * `Mon, 14 Sep 2026 02:21:28 +0800` / `2026-09-14` → `yyyy-MM-dd`。
     *
     * 解析不出时返回空串，而不是替一个「今天」：发布日期参与列表排序与
     * 「已见集合」的去重键，猜一个日期会让同一条通知被反复当成新的。
     */
    fun normalizeDate(raw: String): String {
        val text = raw.trim()
        if (text.isEmpty()) return ""
        ISO_DATE.find(text)?.let { return it.value }
        val m = RFC822_DATE.find(text) ?: return ""
        val day = m.groupValues[1].padStart(2, '0')
        val month = MONTHS.indexOfFirst { it.equals(m.groupValues[2], ignoreCase = true) } + 1
        if (month <= 0) return ""
        return "%04d-%02d-%02d".format(m.groupValues[3].toInt(), month, day.toInt())
    }

    /**
     * 去掉上游**逐字重复**的条目（RSS 偶有整条重复）。
     *
     * ## 为什么键里必须有 [LectureNotice.kind]
     * 这里踩过一次真实的坑：原来的键是「类型 + 期次号 + 标题」，而 `title` 已经过
     * [`cleanTitle`] 剥掉了前缀 —— 于是同一场讲座的**预告与报道会得到同一个键**，
     * 只要它们的正文相同就被当成重复行丢掉一条。实测 `明德讲堂M1165` 正是这种情况：
     *
     * ```
     * M1165报道：熔铸与融合：巴蜀地区汉代铁工业与西南社会变迁
     * M1165预告：熔铸与融合：巴蜀地区汉代铁工业与西南社会变迁   ← 正文逐字相同
     * ```
     *
     * 那次没出错**纯属顺序侥幸**：RSS 是最新在前，报道比预告新，因此先出现的报道被留下。
     * 一旦上游改成旧的在前，被丢掉的就是报道，而「已结束」恰恰只认报道 ——
     * 整个判定会静默失效（表现成「一场都没结束」，与「真的都还没讲」无法区分）。
     *
     * 所以这里的职责要收窄：只挡**整条重复**（类型 + 期次号 + 发布日期 + 性质 + 标题全相同）。
     * 而「同一场次的预告与报道要不要合并」是 [`LectureSessions.dedupe`] 的职责，
     * 它按场次归并并且**必须看到两条**才能判出「已结束」。
     */
    private fun dedupe(list: List<LectureNotice>): List<LectureNotice> {
        if (list.size < 2) return list
        val seen = HashSet<String>(list.size)
        val out = ArrayList<LectureNotice>(list.size)
        for (notice in list) {
            val key = buildString {
                append(notice.type.name).append('|')
                append(notice.sessionNo).append('|')
                append(notice.publishedDate).append('|')
                append(notice.kind.name).append('|')
                append(notice.title)
            }
            if (seen.add(key)) out += notice
        }
        return out
    }

    /**
     * 取某个标签的文本内容，并做基本清洗。
     *
     * RSS 里字段可能被 `CDATA` 包裹（Joomla 的 `title` 就是如此），
     * 也可能带首尾空白（`<pubDate>` 前有制表符），两者都要吃掉。
     * CDATA 必须显式剥掉：它中间的中文与标点都原样保留，
     * 若留在标题里，界面上就会出现 `夯实科技发展的文化基础]]>` 这种尾巴。
     */
    private fun tagValue(block: String, tag: String): String? {
        val m = Regex("<$tag[^>]*>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL).find(block)
            ?: return null
        return unescape(stripCdata(m.groupValues[1])).trim().ifBlank { null }
    }

    /** `<![CDATA[xxx]]>` → `xxx`；不是 CDATA 时只做 trim。 */
    private fun stripCdata(raw: String): String {
        val text = raw.trim()
        if (!text.startsWith(CDATA_START)) return text
        return text.removePrefix(CDATA_START).removeSuffix(CDATA_END).trim()
    }

    private const val CDATA_START = "<![CDATA["
    private const val CDATA_END = "]]>"

    /** XML/HTML 实体还原（`&amp;` `&quot;` `&#39;` 与常见的 `&nbsp;`）。 */
    private fun unescape(raw: String): String = raw
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")

    /** `/index.php/...` 之外的一切（scheme / 主机 / 查询串 / 锚点）都丢掉，见 [toRelativePath]。 */
    private fun toRelativePath(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        val withoutScheme = if (trimmed.contains("://")) {
            val afterScheme = trimmed.substringAfter("://")
            "/" + afterScheme.substringAfter('/', "")
        } else {
            trimmed
        }
        val path = withoutScheme.substringBefore('?').substringBefore('#')
        if (path.isEmpty()) return ""
        return if (path.startsWith("/")) path else "/$path"
    }

    private val ITEM = Regex("""<item[ >].*?</item>""", RegexOption.DOT_MATCHES_ALL)

    private val LIST_ITEM = Regex(
        """<li>\s*<span class="m-date">\s*([0-9]{4}-[0-9]{2}-[0-9]{2})\s*</span>\s*<a[^>]+href="([^"]+)"[^>]*>(.*?)</a>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )

    private val HTML_TAGS = Regex("""<[^>]+>""")

    private val ISO_DATE = Regex("""\d{4}-\d{2}-\d{2}""")

    private val RFC822_DATE = Regex(
        """(?:Mon|Tue|Wed|Thu|Fri|Sat|Sun)?,?\s*(\d{1,2})\s+([A-Za-z]{3})\s+(\d{4})""",
    )

    private val MONTHS = listOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun",
        "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
    )
}
