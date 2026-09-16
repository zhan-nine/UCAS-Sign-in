package com.ucas.qingxin.signin.attendance

import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * 每节课「随机签到时刻」的内存态持有者。
 *
 * 设计要点（安全 + 省电）：
 *
 * 1. **绝不落盘**：随机值只活在进程内存里。设备上不会留下任何「固定签到时刻」的
 *    可疑痕迹；进程重启后会重新随机。
 * 2. **每一节课重新随机**：key 含课次 id 与日期，跨天 / 新的一节课必然重新取一次。
 * 3. **同进程内一旦取定即冻结**：闹钟、守护服务、WorkManager 三条触发路径必须看到
 *    **同一个**时刻。否则每次重算都会把下界推到 `now + 30s`，时刻永远往后滑，永不触发。
 * 4. **永不滑出签到窗口**：下界不低于 `开课前 15 分钟`，上界高于 `开课时刻 30 秒`；
 *    若两者已交叉（例如窗口即将结束）则退化为「立刻签到」。
 */
internal object AutoSignRandomizer {

    /** 签到窗口提前量：开课前 15 分钟。 */
    const val WINDOW_LEAD_MS = 15L * 60L * 1000L

    /** 随机时刻相对「当前时刻」的安全余量：不取一个取完就已经过去的时刻。 */
    private const val MIN_LEAD_MS = 30L * 1000L

    /** 随机上界与开课时刻之间留的余量，避免刚好卡在边界上。 */
    private const val TAIL_GUARD_MS = 30L * 1000L

    /** 终态失败判定：开课后这么久仍未成功则放弃重试。 */
    const val GRACE_MS = 20L * 60L * 1000L

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private val targets = ConcurrentHashMap<String, Long>()

    fun key(courseId: String, day: String): String = "$courseId@$day"

    fun todayKey(): String = LocalDate.now(zone).toString()

    fun deadlineMs(beginMs: Long): Long = beginMs + GRACE_MS

    /**
     * 取得该课次的随机签到时刻（epoch millis）。
     * 已存在则**原样返回**；否则在 `[开课前15分钟, 开课-30秒]` 内均匀随机。
     */
    fun targetMsFor(
        courseId: String,
        beginMs: Long,
        nowMs: Long,
        day: String = todayKey(),
    ): Long {
        val k = key(courseId, day)
        targets[k]?.let { return it }
        val lower = maxOf(beginMs - WINDOW_LEAD_MS, nowMs + MIN_LEAD_MS)
        val upper = beginMs - TAIL_GUARD_MS
        val value = if (upper <= lower) beginMs else lower + Random.nextLong(upper - lower)
        targets[k] = value
        return value
    }

    /** 只读查询：不产生新的随机值（用于判断是否需要重排）。 */
    fun peek(courseId: String, day: String = todayKey()): Long? = targets[key(courseId, day)]

    /** 某个课次已签到/已终结，丢弃其随机值。 */
    fun forget(courseId: String, day: String = todayKey()) {
        targets.remove(key(courseId, day))
    }

    /**
     * 跨天清理：丢弃所有非今日条目，避免 map 无限增长，
     * 并保证新的一天每节课都重新随机。
     */
    fun pruneBefore(todayKey: String = todayKey()) {
        targets.keys.filterNot { it.endsWith("@$todayKey") }.forEach { targets.remove(it) }
    }

    /** 用户关闭自动签到 / 退出登录时调用。 */
    fun clearAll() {
        targets.clear()
    }
}
