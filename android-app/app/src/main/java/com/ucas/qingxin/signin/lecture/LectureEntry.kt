package com.ucas.qingxin.signin.lecture

import java.time.LocalDateTime

/** 一条讲座信息的来源。两者都要在界面上标出来，因为可信度与信息量不同。 */
enum class LectureOrigin(val label: String) {
    /** 讲座预约系统的讲座时间表：**有确切时间与场地**，但需要登录才读得到。 */
    SCHEDULE("预约系统"),

    /** 人文学院网站的通知：免登录、更新及时，但**只有标题没有时间场地**。 */
    NOTICE("学院网站"),
}

/**
 * 讲座预告页展示的一行 —— 把两个来源归一化之后的**唯一**展示模型。
 *
 * ## 为什么要归一化
 * 同一场讲座会同时出现在两个来源里：
 * - 预约系统里是「`M1167`雁栖湖会场：夯实科技发展的文化基础 + 时间 + 场地」；
 * - 学院网站里是「明德讲堂M1167预告：夯实科技发展的文化基础」。
 *
 * 若各展示一条，用户看到的是重复的两行，而且**都不知道该不该去**：
 * 一行有时间没详情，一行有详情没时间。因此这一层的职责就是**按期次号把它们并成一条**，
 * 时间场地取自预约系统、详情链接取自学院网站。
 *
 * ## `timed` 为什么不是从 `startsAt` 推出来的
 * 「有没有时间」是这一层最关键的分支依据，而它**不能**由 `startsAt` 反推：
 * 一条来自学院网站的通知即使解析出了日期也不该被当成「已排定时间」。
 * 因此用显式字段表意，而不是让读者去推断空串的含义。
 */
data class LectureEntry(
    val key: String,
    val title: String,
    /** 栏目名，如「明德讲堂」「科学前沿讲座」。 */
    val category: String,
    /** 期次号（`M1167` / `213`）；解析不出时为空串。 */
    val sessionNo: String = "",
    /** 讲座开始时刻（`yyyy-MM-dd HH:mm`）；无确切时间时为空串。 */
    val startsAt: String = "",
    /** 界面显示的时间标签（`09-16 15:30-17:30`）；无确切时间时为空串。 */
    val timeLabel: String = "",
    /** 场地；未知时为空串。 */
    val location: String = "",
    /** 是否有**确切**的讲座时间（即来自预约系统）。见类注释。 */
    val timed: Boolean = false,
    /** 是否已确认结束。判据见 [LectureBoard]。 */
    val ended: Boolean = false,
    /** 详情页相对路径（学院网站的文章）；仅用于跳转，不含校内端点。 */
    val detailUrl: String = "",
    val origin: LectureOrigin,
    /** 学院网站通知的发布日期（`yyyy-MM-dd`），用于「没有时间」时的排序；无则为空串。 */
    val publishedDate: String = "",
) {
    /**
     * 时间/场地一行里的摘要文本；没有任何可展示信息时为空串。
     *
     * 拼在一行而不是分两行：讲座行本来就窄，而「时间」和「场地」是同时要看的信息
     * （判断去不去得成必须两者一起看）。
     */
    val scheduleLabel: String
        get() = listOf(timeLabel, location).filter { it.isNotBlank() }.joinToString(" · ")

    /**
     * 这一条属于学院网站上的哪个栏目；判不出来时为 `null`。
     *
     * ## 为什么按**期次号形态**判，而不是按来源
     * 同一场讲座在两个来源里各有一条，会被 [LectureBoard] 合成同一条 ——
     * 于是这条记录的 `category` 可能来自任一侧（`明德讲堂` 或 `人文讲座`），
     * 用它来筛选会时灵时不灵。而**期次号是两侧共有的**：
     * 明德是 `M1167`，艺术系列是纯数字 `213`，形态本身就区分了栏目。
     *
     * 判不出来（例如科学前沿讲座、或标题里没写期次号的人文讲座）时返回 `null`：
     * 这类条目只在「全部」里出现，不会因为筛选条件而**消失得没有解释**。
     */
    val type: LectureType?
        get() {
            val key = sessionNo.trim()
            if (key.isEmpty()) return null
            return when {
                key.startsWith("M", ignoreCase = true) -> LectureType.MINGDE
                key.all { it.isDigit() } -> LectureType.ART_HUMANITY
                else -> null
            }
        }
}

/** [LectureBoard.build] 的结果：三段列表，界面直接照着渲染。 */
data class LectureBoard(
    /**
     * 即将开始：**有确切讲座日期**且尚未结束。按时间升序（最近要去的在最前）。
     *
     * 只有学院网站标题、没有举办日期的条目**不会**进这一段 —— 把它们标成「即将开始」
     * 等于谎称知道日期；它们单独放在 [undated]。
     */
    val upcoming: List<LectureEntry>,
    /**
     * 暂无举办日期：学院网站的预告/通知，且尚未出现「报道」。
     *
     * 按发布日期降序。它们不是「即将开始」—— 发布日 ≠ 讲座日，不能拿来判远近。
     */
    val undated: List<LectureEntry>,
    /** 往期（已结束的讲座与往期报道）。**降序**：刚讲完的在最前。 */
    val past: List<LectureEntry>,
) {
    companion object {
        /**
         * 把两个来源合成三段列表。
         *
         * ## 归并规则
         * 以**期次号**为同一场讲座的判据（`M1167` / `213`），解析不出版次号时才退化到
         * 「规范化标题完全相同」。之所以不用标题做主键：明德讲堂在预约系统里的行
         * 带会场前缀（`M1167雁栖湖会场：…`），在学院网站里没有（`夯实科技发展的文化基础`），
         * 标题根本对不上，而期次号两处都有。
         *
         * ## 「已结束」的判据（按来源分开，这是刻意的）
         * - 预约系统的条目**有确切时间** ⇒ 用时间比：`日期 + 结束时刻`（缺失时取当日 23:59）
         *   早于当前时刻即结束。这是**硬证据**。
         * - 学院网站的通知**没有时间** ⇒ 只能沿用 [LectureSessions.endedKeys]：
         *   同一期次号下出现过「报道」才算结束。这是**间接证据**，会滞后。
         *
         * 两者不能混用同一个判据：用时间比一条没有时间的通知，等于按发布日期猜。
         *
         * ## 为什么「无日期」要单独成段
         * 早先把无日期通知塞进「即将开始」，结果是几个月前的预告一直挂在最显眼处，
         * 用户会以为「日期判定坏了」。它们其实**没有日期可判** —— 诚实的做法是
         * 单独成段，等预约系统读到时间后再并进「即将开始 / 往期」。
         *
         * @param now 当前时刻，由调用方传入（而不是在函数内部取）——
         *   否则这段逻辑就无法离线测试「跨过讲座结束时刻」这类情形。
         */
        fun build(
            events: List<LectureEvent>,
            notices: List<LectureNotice>,
            endedGroupKeys: Set<String>,
            now: LocalDateTime,
        ): LectureBoard {
            val entries = ArrayList<LectureEntry>(events.size + notices.size)

            // 先放预约系统的条目：它们有时间，是列表的骨架。
            for (event in events) entries += entryOf(event, now)

            // 再用学院网站的通知去**补详情链接**，补不上的才新增条目。
            for (notice in notices) {
                val ended = notice.groupKey in endedGroupKeys
                val targets = matchingIndices(entries, notice)
                if (targets.isEmpty()) {
                    entries += entryOf(notice, ended, now)
                    continue
                }
                for (index in targets) {
                    val old = entries[index]
                    entries[index] = old.copy(
                        // 详情链接补到**该场次的每一个会场行**：文章讲的是这场讲座，
                        // 不是某个会场，用户在任意一行点进去都该看到同一篇。
                        detailUrl = old.detailUrl.ifBlank { notice.detailUrl },
                        sessionNo = old.sessionNo.ifBlank { notice.sessionNo },
                        // 已结束取「任一有证据」：预约系统按时间判定、学院网站按报道判定，
                        // 两者都是硬信号，任一成立即可。
                        ended = old.ended || ended,
                    )
                }
            }

            // 去重：同一 key 只留一条（一场讲座的多个会场共用 key 时不该重复展示）。
            val deduped = entries.distinctBy { it.key }

            return LectureBoard(
                upcoming = deduped.filter { it.timed && !it.ended }.sortedWith(UPCOMING),
                undated = deduped.filter { !it.timed && !it.ended }.sortedWith(UNDATED),
                past = deduped.filter { it.ended }.sortedWith(PAST),
            )
        }

        /**
         * 通知对应到哪些已有条目（预约系统的行）。
         *
         * 两级判据，**顺序不能反**：
         * 1. **期次号相同** —— 最可靠，两侧都带（`M1167` / `213`）；
         * 2. **标题包含** —— 只在期次号解析不出时兜底。实测学院网站的标题是
         *    `夯实科技发展的文化基础`，预约系统的是 `M1167雁栖湖会场：夯实科技发展的文化基础`，
         *    因此判「条目标题包含通知标题」而不是相等：会场前缀只出现在一侧。
         *    包含判定有误配风险，所以它**只做兜底**，绝不用来覆盖期次号判据。
         */
        private fun matchingIndices(entries: List<LectureEntry>, notice: LectureNotice): List<Int> {
            val session = notice.sessionNo.takeIf { it.isNotBlank() }
                ?: sessionKeyOf(notice.title)
            if (session != null) {
                val bySession = entries.indices.filter { entries[it].timed && entries[it].sessionNo == session }
                if (bySession.isNotEmpty()) return bySession
            }
            val normalized = normalizeTitle(notice.title)
            if (normalized.length < MIN_TITLE_CHARS) return emptyList()
            return entries.indices.filter {
                entries[it].timed && normalizeTitle(entries[it].title).contains(normalized)
            }
        }

        private fun entryOf(event: LectureEvent, now: LocalDateTime): LectureEntry = LectureEntry(
            key = event.key,
            title = event.title,
            category = event.kind.label,
            sessionNo = sessionKeyOf(event.title).orEmpty(),
            startsAt = event.startsAt,
            timeLabel = event.timeLabel,
            location = event.location,
            // 预约系统的行**可能**没有日期（站点偶有缺列），此时它并不是「有时间」的条目。
            timed = event.date.isNotBlank(),
            ended = LectureTableParser.isEnded(event, now),
            origin = LectureOrigin.SCHEDULE,
        )

        private fun entryOf(notice: LectureNotice, ended: Boolean, now: LocalDateTime): LectureEntry {
            // 无讲座日期时：发布已超过 [STALE_PREVIEW_DAYS] 天且仍无报道 ⇒ 视为已结束。
            // 发布日 ≠ 讲座日，但这是「只有通知」时唯一可用的保守信号；
            // 用户反馈当前能扫到的场次其实都已讲完，正是这类条目一直挂着不进往期。
            val stale = !ended && isStalePreview(notice, now)
            return LectureEntry(
                key = notice.id,
                title = notice.title,
                category = notice.type.label,
                sessionNo = notice.sessionNo,
                timed = false,
                ended = ended || stale,
                detailUrl = notice.detailUrl,
                origin = LectureOrigin.NOTICE,
                publishedDate = notice.publishedDate,
            )
        }

        /**
         * 预告发布是否已「过期」到足以默认进往期。
         *
         * 只对**没有报道、也没有预约系统时间**的通知生效。天数取 [STALE_PREVIEW_DAYS]：
         * 太短会把刚发的预告误送进往期；太长又让上学期的预告一直占着「暂无日期」。
         */
        private fun isStalePreview(notice: LectureNotice, now: LocalDateTime): Boolean {
            if (notice.kind == LectureNoticeKind.REPORT) return false
            val published = runCatching { java.time.LocalDate.parse(notice.publishedDate) }.getOrNull()
                ?: return false
            return published.isBefore(now.toLocalDate().minusDays(STALE_PREVIEW_DAYS))
        }

        private const val STALE_PREVIEW_DAYS = 14L

        /**
         * 通知对应的预约系统条目（见 [matchingIndices]）。
         *
         * 期次号是主判据；标题包含只作兜底，且**要求通知标题至少有 [MIN_TITLE_CHARS] 个字**，
         * 否则像「讲座」这种极短标题会命中一大片条目。
         */
        private const val MIN_TITLE_CHARS = 4

        /**
         * 从任意一侧的标题里取期次号，作为**同一场讲座的判据**。
         *
         * 依次按两个栏目的形态尝试：明德是 `M1167`，艺术系列是 `213讲`。
         * 两种形态不可能互相误匹配（一个必带 `M`，一个是纯数字且后面跟「讲」），
         * 因此顺序无关紧要，写成两级 `when` 只是为了让「解析不出」这个结果显式。
         */
        fun sessionKeyOf(title: String): String? {
            LectureNoticeParser.sessionNo(title, LectureType.MINGDE)
                .takeIf { it.isNotBlank() }
                ?.let { return it }
            return LectureNoticeParser.sessionNo(title, LectureType.ART_HUMANITY)
                .takeIf { it.isNotBlank() }
        }

        /**
         * 标题规范化：只留中日韩文字、字母与数字。
         *
         * 去掉标点与空白是必需的：两侧的标题分隔符不同（学院网站用中文冒号、
         * 预约系统用全角冒号或空格），不统一就永远匹配不上。
         */
        private fun normalizeTitle(raw: String): String =
            raw.filter { it.isLetterOrDigit() }

        /** 即将开始：有时间的按时间升序（最近要去的最前）。 */
        private val UPCOMING: Comparator<LectureEntry> =
            compareBy { it.startsAt }

        /** 暂无日期：按发布日期降序（最新发布的预告靠前）。 */
        private val UNDATED: Comparator<LectureEntry> =
            compareByDescending { it.publishedDate }

        /** 往期：刚结束的在最前（有时间按时间降序，无时间按发布日期降序）。 */
        private val PAST: Comparator<LectureEntry> =
            compareByDescending<LectureEntry> { if (it.timed) it.startsAt else "" }
                .thenByDescending { if (it.timed) "" else it.publishedDate }
    }
}

