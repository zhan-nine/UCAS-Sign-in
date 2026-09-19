package com.ucas.qingxin.signin.lecture

import android.content.Context
import android.content.SharedPreferences
import com.ucas.qingxin.signin.BuildConfig

/**
 * 哨兵的本地状态。
 *
 * ## 为什么需要「基点 + 前沿 + 待查」三个编号
 * 注册表的编号**只增不改**，但条目可能**先占号、后填名**（实测：某场讲座的页面
 * 刚建好时正文 1.6 KB、没有标题，之后才补成 8.6 KB）。这一条事实决定了
 * 单靠一个「扫到哪儿了」的游标是不够的，会有两种漏采方式：
 *
 * | 状态 | 作用 | 少了它会怎样 |
 * | --- | --- | --- |
 * | [anchorCid] **基点** | 本地已知的**最后一场讲座**的编号 | 不知道从哪儿开始找（也是用户指定的口径：以最后一场讲座为基点向前扫） |
 * | [frontierCid] **前沿** | 已见过的**最大有名称编号** | 每轮都要从基点重扫，白扫一大段已分配的编号 |
 * | [pendingCids] **待查** | 扫过但**当时还没有名称**的编号 | 占号早、填名晚的场次会被整段越过 —— 这是最致命的漏采 |
 *
 * ## 为什么不做「只往前扫」
 * 只往前扫会漏掉 [pendingCids] 里那类条目：它们的编号在很早就被占下，
 * 名称却在讲座临近时才填上，此时前沿早已越过它们。所以每轮都要**重查待查集**，
 * 这是本设计的核心，不是冗余。
 *
 * ## 规模上界
 * [pendingCids] 与 [seenSessions] 都有硬上界（见 [MAX_PENDING] / [MAX_SEEN]），
 * 因此存储体积不会随时间无限增长 —— 一个只增不减的「待查集」迟早会变成性能问题。
 */
internal class LectureSentinelStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 基点：最后一次「确认存在」的讲座条目的编号。
     *
     * 初值取构建时注入的 `SENTINEL_ANCHOR_CID`（本学期第一场讲座的编号）。
     * 之所以落在本地配置而不是源码：编号随学期变化，新学期改一行配置即可，
     * 不必为了一个数字改代码发版。
     */
    fun anchor(): Int {
        val stored = prefs.getInt(K_ANCHOR, 0)
        if (stored > 0) return stored
        return BuildConfig.SENTINEL_ANCHOR_CID.trim().toIntOrNull() ?: 0
    }

    fun setAnchor(cId: Int) {
        if (cId <= 0) return
        if (cId > prefs.getInt(K_ANCHOR, 0)) {
            prefs.edit().putInt(K_ANCHOR, cId).apply()
        }
    }

    fun frontier(): Int = prefs.getInt(K_FRONTIER, 0)

    fun setFrontier(cId: Int) {
        if (cId <= prefs.getInt(K_FRONTIER, 0)) return
        prefs.edit().putInt(K_FRONTIER, cId).apply()
    }

    /** 待查编号：扫过、但当时还没有名称。升序返回。 */
    fun pending(): List<Int> = prefs.getString(K_PENDING, null)
        ?.split(SEPARATOR)
        ?.mapNotNull { it.trim().toIntOrNull() }
        ?.filter { it > 0 }
        ?.distinct()
        ?.sorted()
        ?: emptyList()

    /**
     * 覆盖待查集。
     *
     * 只保留**最大的** [MAX_PENDING] 个：长期无名的小编号大概率是被删掉的占位行，
     * 而不是「晚些时候会被填名」的场次 —— 保留它们只会让每轮白扫。
     * 之所以按编号大小（而不是时间）丢弃：编号越新越可能是尚未排期的场次。
     */
    fun setPending(cIds: List<Int>) {
        val kept = cIds.asSequence()
            .filter { it > 0 }
            .distinct()
            .sorted()
            .toList()
            .takeLast(MAX_PENDING)
        prefs.edit().putString(K_PENDING, kept.joinToString(SEPARATOR.toString())).apply()
    }

    /** 已经报过的期次标签（如 `M1167`），用于「只报新增」的去重。 */
    fun seenSessions(): List<String> = prefs.getString(K_SEEN, null)
        ?.split(SEPARATOR)
        ?.filter { it.isNotBlank() }
        ?: emptyList()

    fun setSeenSessions(labels: List<String>) {
        val kept = labels.distinct().takeLast(MAX_SEEN)
        prefs.edit().putString(K_SEEN, kept.joinToString(SEPARATOR.toString())).apply()
    }

    /**
     * 是否已经完成过一次扫描。
     *
     * 首次只记录、不推送：安装后第一轮会一次性「发现」历史里所有讲座，
     * 不区分的话用户一装上就被推十几条（与 [LectureNoticeWatcher] 同一处理）。
     */
    fun initialized(): Boolean = prefs.getBoolean(K_INITIALIZED, false)

    fun setInitialized(value: Boolean) {
        prefs.edit().putBoolean(K_INITIALIZED, value).apply()
    }

    fun lastRunAt(): String = prefs.getString(K_LAST_RUN, "").orEmpty()

    /** 上一次的结果摘要（给界面显示「上次扫描：新增 N 场」）。 */
    fun lastSummary(): String = prefs.getString(K_LAST_SUMMARY, "").orEmpty()

    /** 上一次发现的讲座名称，供界面列出（条数有上界）。 */
    fun lastFound(): List<String> = prefs.getString(K_LAST_FOUND, null)
        ?.split(SEPARATOR)
        ?.filter { it.isNotBlank() }
        ?: emptyList()

    fun recordRun(at: String, summary: String, found: List<String>) {
        prefs.edit()
            .putString(K_LAST_RUN, at)
            .putString(K_LAST_SUMMARY, summary)
            .putString(K_LAST_FOUND, found.take(MAX_FOUND_SHOWN).joinToString(SEPARATOR.toString()))
            .apply()
    }

    /**
     * 清空扫描状态（**不动开关**）。
     *
     * 状态与开关分两个 prefs 文件存放，正是为了这里能安全地「只清状态」：
     * 混在一起的话，一次「重置」会把用户明确打开过的哨兵悄悄关掉。
     */
    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val PREFS_NAME = "lecture_sentinel_state"
        const val K_ANCHOR = "anchor"
        const val K_FRONTIER = "frontier"
        const val K_PENDING = "pending"
        const val K_SEEN = "seen"
        const val K_INITIALIZED = "initialized"
        const val K_LAST_RUN = "last_run"
        const val K_LAST_SUMMARY = "last_summary"
        const val K_LAST_FOUND = "last_found"

        /**
         * 待查集上限。
         *
         * 分配末端通常只剩几十个占位行（实测：本学期密扫区里连续无名段约 30–50 个），
         * 300 已远超正常水位；真的超了说明扫描范围跑偏，此时**丢旧留新**是正确的兜底。
         */
        const val MAX_PENDING = 300

        /** 已报期次的上限。每学期几十场，200 条足够覆盖数年。 */
        const val MAX_SEEN = 200

        /** 界面只需列出最近的几条，存太多既没用也让文案难读。 */
        const val MAX_FOUND_SHOWN = 5

        const val SEPARATOR = '\n'
    }
}
