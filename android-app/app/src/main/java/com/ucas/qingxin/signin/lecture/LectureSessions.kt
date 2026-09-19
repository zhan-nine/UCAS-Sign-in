package com.ucas.qingxin.signin.lecture

/**
 * 把「通知」看成「场次」的纯函数集合。
 *
 * ## 为什么需要这一层
 * 人文学院网站是**按文章**发布的：同一场讲座可能先发「预告」，讲完再发「报道 / 纪要」，
 * 那就是**两条通知**。而用户关心的是**场次**——「这场讲完了没、最近有什么要去的」。
 * 这两个视角的差集正好就是本对象存在的理由：
 * - 列表展示要**去重**（同一场的预告与报道不该并排出现两次）；
 * - 「是否已结束」要**跨条**判断（靠同一场次里有没有「报道」）。
 *
 * ## 「已结束」的判定及其诚实的边界
 * 判据是：**同一场次下存在至少一条「报道 / 纪要 / 综述」** ⇒ 这场已经讲完。
 *
 * 这个判据只依赖站内信息，不需要任何日期 —— 这是刻意的，因为
 * **通知里只有发布日，没有讲座举办日**（已实测：预告详情页正文里日期命中 0）。
 * 所以它不能回答「讲座是几号几点」，只能回答「这场是否已经结束」。
 *
 * 由此得到的边界必须说清楚：
 * - **否定不成立**：只有预告、没有报道 ⇒ 可能结束了而报道还没写（实测报道常在
 *   讲座后数日到数周才发布），所以这里**不会**把它标成「已结束」；
 * - 因此界面上「已结束」是**只增不减**的确定性标记，而「未标记」不等于「还没讲」。
 *
 * 换句话说：与其按当前日期猜一个「应该讲完了吧」，不如只用有证据的那一半。
 * 猜错的代价是用户以为讲座还没开始、白跑一趟。
 */
internal object LectureSessions {

    /**
     * 按 [LectureNotice.groupKey] 合并同一场次的多条通知，并按发布时间倒序返回。
     *
     * 代表条目取「发布日期最新」的那一条：对已讲完的场次，报道比预告新，
     * 于是代表条目自然就是报道，界面上「已结束」与内容一致。
     * 同为最新时优先取 [LectureNoticeKind.REPORT]，让代表条目的性质反映最终状态。
     *
     * 返回顺序：发布日期倒序（新的在前），同日内期次号大的在前 —— 与用户
     * 「最近一场是哪个」的预期一致；解析不出日期/期次号的排在后面。
     */
    fun dedupe(all: List<LectureNotice>): List<LectureNotice> {
        if (all.size < 2) return all
        return all.groupBy { it.groupKey }
            .values
            .mapNotNull { group -> group.maxWithOrNull(REPRESENTATIVE) }
            .sortedWith(MOST_RECENT_FIRST)
    }

    /**
     * 已确认结束的场次（[LectureNotice.groupKey] 集合）。
     *
     * **必须传全量通知**（去重之前）：判定要靠「同一场次里同时存在预告与报道」，
     * 拿去重后的列表来算会丢掉报道那条，于是永远算不出「已结束」。
     */
    fun endedKeys(all: List<LectureNotice>): Set<String> =
        all.filter { it.kind == LectureNoticeKind.REPORT }
            .mapTo(HashSet()) { it.groupKey }

    /**
     * 代表条目的选择规则：发布日期新的优先，同日期时报道优先（报道即最终状态）。
     *
     * `compareBy` 让 `maxWithOrNull` 选出的就是「最大」的那条，因此顺序不能反。
     */
    private val REPRESENTATIVE: Comparator<LectureNotice> =
        compareBy<LectureNotice> { it.publishedDate }
            .thenBy { if (it.kind == LectureNoticeKind.REPORT) 1 else 0 }

    /**
     * 发布日期**倒序**：最新发布的排在最前。日期缺失的排在最后。
     *
     * 这是「最近 → 最远」的排序口径（历史版本曾因顺序反了而把最旧的排在最前）。
     * 同日再按期次号倒序：期次号越大越新（`M1167` > `M1166`）。
     */
    val MOST_RECENT_FIRST: Comparator<LectureNotice> =
        compareByDescending<LectureNotice> { it.publishedDate.length == DATE_LENGTH }
            .thenByDescending { it.publishedDate }
            .thenByDescending { it.sessionOrdinal }
            .thenBy { it.title }

    private const val DATE_LENGTH = 10
}
