package com.ucas.qingxin.signin.update

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 设置页「版本与更新」卡片上那几行文案。
 *
 * ## 为什么把文案抽成纯函数
 * 用户的抱怨是「看不出这个功能到底有没有在跑」，而这几行字就是**唯一的证据**：
 * 检查成功后卡片上必须留下「最新版本 X」与「上次检查 时间」。
 * 这两行一旦写错（比如永远显示「尚未查到」），功能就再次变成不可见，
 * 而且不会报任何错、也不会崩 —— 只能靠用例钉住。
 */
object UpdateSummary {

    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日 HH:mm")

    /** 与课程时间、课表等处保持一致：固定东八区。 */
    private val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

    /**
     * 「最新版本」这一行。
     *
     * [latest] 为 `null` 表示**从未成功检查过**（没网、或还没查过）。
     * 它必须与「已是最新」区分开：前者是「不知道」，后者是「确定没有更新」。
     * 混为一谈会让断网时的界面看起来像检查成功了。
     */
    fun latestLine(latest: UpdateRelease?, currentVersion: String): String {
        if (latest == null) return "最新版本：尚未查到（点下方「检查更新」）"
        val current = VersionTags.parse(currentVersion)
        val newer = current == null || latest.version > current
        return if (newer) {
            "最新版本 ${latest.version}（比当前新）"
        } else {
            "最新版本 ${latest.version}（已是最新）"
        }
    }

    /**
     * 「上次检查」这一行。
     *
     * [lastCheckedAtMs] 为 0 或负数表示从未检查过。
     */
    fun lastCheckedLine(lastCheckedAtMs: Long): String {
        if (lastCheckedAtMs <= 0L) return "上次检查：从未"
        return "上次检查：" + TIME.format(Instant.ofEpochMilli(lastCheckedAtMs).atZone(ZONE))
    }

    /** 检查成功后的状态行。 */
    fun statusLine(latest: UpdateRelease?, currentVersion: String): String {
        if (latest == null) return "检查完成：仓库里还没有可用的 Release"
        val current = VersionTags.parse(currentVersion)
        return if (current == null || latest.version > current) {
            "发现新版本 ${latest.version}，建议更新"
        } else {
            "已是最新版本 $currentVersion"
        }
    }
}
