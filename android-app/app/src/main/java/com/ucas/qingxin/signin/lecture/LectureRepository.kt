package com.ucas.qingxin.signin.lecture

/**
 * 人文讲座通知仓库。
 *
 * ## 为什么**不**做本地缓存
 * 这个数据源的价值全在「新」：讲座通知一旦滞后就毫无意义（用户要据此安排去不去现场）。
 * 而本地缓存恰恰是滞后性的来源 —— 网络一失败就把上一次的结果端上来，
 * 用户看到的「最新通知」可能是几天前的，界面上却只有一行小字提示，
 * 很容易被当成当前信息。
 *
 * 因此这里刻意**只有一条路径**：抓网页。抓不到就如实报错，由界面提示重试。
 * 空白 + 明确的错误，比一份说不清有多旧的列表更诚实。
 *
 * 代价要说清楚：**断网时这一页不可用**。这是有意接受的取舍 ——
 * 讲座通知不是离线场景下需要的内容，而「看起来有数据但其实是旧的」危害更大。
 *
 * ## 与「已见集合」的区别
 * 新预告通知的去重状态仍然落盘（见 `LectureNoticeWatcher`），
 * 但那是**通知去重用的元数据**，不参与列表展示，因此不受此处约束。
 */
class LectureRepository(
    private val service: LectureNoticeService = LectureNoticeService(),
) {
    /**
     * 拉取最新通知。
     *
     * 返回前先按**场次**合并（见 [LectureSessions.dedupe]）：同一场讲座的
     * 「预告」与「报道」是两条文章，但界面上不该并排出现两次。
     *
     * 失败时**不**回退任何本地数据，直接把异常抛给上层（界面据此显示错误并提供重试）。
     */
    suspend fun loadNotices(): NoticeQueryResult {
        val found = service.fetchAll()
        val merged = LectureSessions.dedupe(found)
        return NoticeQueryResult(
            notices = merged,
            message = if (merged.size == found.size) {
                "已更新 ${merged.size} 条通知"
            } else {
                // 把「合并了几条」明确报出来：否则用户会以为网站少发了内容。
                "已更新 ${merged.size} 场讲座（合并了 ${found.size - merged.size} 条重复/后续报道）"
            },
            // 必须在**合并前**的 found 上算：判据是「同一场次里同时有预告与报道」。
            endedGroupKeys = LectureSessions.endedKeys(found),
        )
    }
}
