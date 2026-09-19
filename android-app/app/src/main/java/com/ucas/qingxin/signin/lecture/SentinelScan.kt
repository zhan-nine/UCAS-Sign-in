package com.ucas.qingxin.signin.lecture

/**
 * 前进扫描的终止状态机。
 *
 * 抽成独立类是为了**可单测**：整轮扫描的行为几乎全由这里的三条停止规则决定，
 * 而它们用假的取值函数就能完整驱动，不需要网络。
 *
 * ## 三条停止规则（缺一不可）
 * | 规则 | 作用 |
 * | --- | --- |
 * | [budget] 请求预算 | 单轮耗时与请求量有硬上界，后台任务不会被拖成无限循环 |
 * | `stopAfterEmpty` 连续无名 | 编号只增不改，编号连续一长串都没有名称 ⇒ 已经越过分配末端，再往前是未分配的空白区 |
 * | `MAX_CID` 编号上限 | 兜底：编号是 5 位，越过它就是配置或数据出了异常 |
 *
 * 注意「连续」是必需的：分配区里**夹杂**着尚未填名的占位行是常态
 * （占号早、填名晚），遇到一个空编号就停会把整个扫描卡死在分配区中间。
 *
 * ## 用法（顺序不可颠倒）
 * ```
 * while (true) {
 *     val cid = walk.next() ?: break   // 取下一个编号（同时消耗预算）
 *     val name = fetch(cid)
 *     walk.accept(name)                // 必须紧跟 next() 报告结果
 * }
 * ```
 */
internal class ForwardWalk(
    from: Int,
    private val budget: Int,
    private val stopAfterEmpty: Int,
) {
    private var cursor = from.coerceAtLeast(1)
    private var emptyRun = 0
    private var halt: String? = null

    /** 已取走的编号个数，等于调用方应发的请求数。 */
    var requests: Int = 0
        private set

    /** 停止原因，用于在界面上解释「为什么只扫到这儿」。 */
    val haltReason: String get() = halt ?: if (requests >= budget) REASON_BUDGET else REASON_END

    /** 是否已经越过分配末端。与「预算用尽」是不同的状态，界面提示也不同。 */
    val reachedAllocationEnd: Boolean get() = halt == REASON_END

    /**
     * 下一个待扫编号；已停时返回 `null`。
     *
     * 取号即消耗预算 —— 这样「预算用尽」不会出现「取了号却没发请求」的边界偏差。
     */
    fun next(): Int? {
        if (halt != null) return null
        if (requests >= budget) {
            halt = REASON_BUDGET
            return null
        }
        if (cursor > MAX_CID) {
            halt = REASON_MAX_CID
            return null
        }
        requests++
        return cursor++
    }

    /**
     * 报告一个编号的扫描结果。必须紧跟 [next] 调用。
     *
     * `name == null` 表示**请求成功但该编号还没有名称**（见 `LectureRegistryService.fetchName`
     * 的契约）；请求失败应当向上抛异常，绝不能传 `null` 进来，否则会把一次网络故障
     * 误记成「分配末端」。
     */
    fun accept(name: String?) {
        if (halt != null) return
        if (name.isNullOrBlank()) {
            emptyRun++
            if (emptyRun >= stopAfterEmpty) halt = REASON_END
        } else {
            emptyRun = 0
        }
    }

    companion object {
        /** 编号是 5 位，越过它就是配置或数据异常。 */
        const val MAX_CID = 99999

        const val REASON_END = "已到分配末端（连续多个编号无名称）"
        const val REASON_BUDGET = "本轮请求预算用尽，下轮继续"
        const val REASON_MAX_CID = "到达编号上限（配置或数据异常）"
    }
}

/**
 * 一轮扫描的完整结果（纯数据，便于单测断言）。
 */
internal data class SentinelScanResult(
    /** 本轮读到名称的编号 → 名称。 */
    val named: Map<Int, String>,
    /** 本轮结束后仍然没有名称的编号（下一轮继续复查）。 */
    val stillNameless: List<Int>,
    /** 本轮扫到的讲座**场次**（编号 → 名称），判据见 [LectureNaming.isLectureSession]。 */
    val lectureSessions: Map<Int, String>,
    /** 本轮实际发出的请求数。 */
    val requests: Int,
    /** 本轮新达到的最大有名称编号（不超过已有前沿）。 */
    val highestNamed: Int,
    /** 停止原因，用于如实告知用户。 */
    val haltReason: String,
)

/**
 * 扫描策略（**不碰 SharedPreferences，也不碰网络**）。
 *
 * 「怎么扫」这件事全部集中在这里，因此可以拿假的取值函数做穷尽单测；
 * [LectureSentinel] 只负责读状态、调它、写回状态。
 */
internal object SentinelScan {

    /**
     * 单个编号没有名称时，连续遇到多少个就认为「已经越过分配末端」。
     *
     * 取 30 的依据：实测本学期分配区末尾的连续无名段约 30–50 个，
     * 而分配区**内部**的占位行是零星夹杂的（不会连成 30 个）。
     * 取小了会在分配区中间误停，取大了每轮白扫几十次请求。
     */
    const val EMPTY_LIMIT = 30

    /**
     * 单轮请求预算。
     *
     * 稳态下一轮只需「复查待查集（约 30–60 个）+ 向前走 30 个」，远用不到 200；
     * 上限是留给**久未运行后追赶**的场景（期间学校注册了几百门课），
     * 到顶就如实报「下轮继续」而不是硬扫完 —— 前沿只前进不回退，慢但不会漏。
     */
    const val PER_RUN_LIMIT = 200

    /**
     * 执行一轮扫描。
     *
     * @param anchor 基点：本地已知的最后一场讲座的编号。
     * @param frontier 前沿：已见过的最大有名称编号（为 0 表示还没扫过）。
     * @param pending 待查编号（扫过但当时没有名称）。
     * @param budget 本轮请求预算。
     * @param fetch 取一个编号的课程名；**请求失败必须抛异常**（契约见 [ForwardWalk.accept]）。
     */
    suspend fun run(
        anchor: Int,
        frontier: Int,
        pending: List<Int>,
        budget: Int = PER_RUN_LIMIT,
        fetch: suspend (Int) -> String?,
    ): SentinelScanResult {
        val named = LinkedHashMap<Int, String>()
        val nameless = ArrayList<Int>()
        var requests = 0

        // 阶段一：复查待查集。
        //
        // 这里**不早停**：待查集里的编号本来就全是「上次没有名称」的，
        // 拿连续无名当停止条件会让第一阶段一个都扫不到、直接空转结束。
        for (cId in pending) {
            if (requests >= budget) break
            requests++
            val name = fetch(cId)
            if (name.isNullOrBlank()) nameless += cId else named[cId] = name
        }

        // 阶段二：自前沿向前走（前沿为 0 时退回基点，即首次运行）。
        val from = maxOf(frontier, anchor) + 1
        val walk = ForwardWalk(from, budget - requests, EMPTY_LIMIT)
        while (true) {
            val cId = walk.next() ?: break
            val name = fetch(cId)
            walk.accept(name)
            if (name.isNullOrBlank()) nameless += cId else named[cId] = name
        }

        val highestNamed = named.keys.maxOrNull() ?: 0
        return SentinelScanResult(
            named = named,
            stillNameless = nameless,
            lectureSessions = named.filterKeys { LectureNaming.isLectureSession(named.getValue(it)) }
                .toSortedMap(),
            requests = requests + walk.requests,
            highestNamed = maxOf(frontier, highestNamed),
            haltReason = walk.haltReason,
        )
    }
}
