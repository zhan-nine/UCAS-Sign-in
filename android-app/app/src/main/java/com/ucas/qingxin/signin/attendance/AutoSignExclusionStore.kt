package com.ucas.qingxin.signin.attendance

import android.content.Context
import android.content.SharedPreferences
import com.ucas.qingxin.signin.data.Course

/**
 * 自动签到「不打卡课程」的判定与键构造（纯逻辑，便于单测）。
 *
 * ## 为什么需要它
 * 自动签到的默认语义是「今天的每节课都替你签」。但现实里总有例外：
 * 这节课老师不查考勤、今天请假、讲座类课程本来就不需要打卡……
 * 没有排除能力时，用户只能关掉整个自动签到，等于放弃了其余所有课。
 *
 * ## 两种排除（语义刻意不同）
 * - **当天临时**：只对今天生效，跨天自动失效。用于「就今天不想签」。
 * - **长期黑名单**：一直生效，直到用户手动取消。用于「这门课永远不用签」。
 *
 * 两者用同一个键空间（[courseKey]），因此界面上一门课的两个开关互不干扰。
 */
object AutoSignExclusions {

    /**
     * 课程的排除键。
     *
     * **优先用课程层标识**（`courseId` / `courseNum`）：`id` / `uuid` 是**节次级**
     * 标识，同一门课在不同日期/周次上并不相同，拿它做长期黑名单会导致
     * 「今天排除了，明天它换个 id 又自己签上了」。
     *
     * 回退阶梯：`courseId` → `courseNum` → 节次 id/uuid → 「课程名|教师」。
     * 每一级都带前缀（`c:` / `n:` / `s:` / `x:`），避免不同来源的键意外撞车。
     *
     * 最后的「课程名|教师」兜底只在课程层标识缺失时才会用到（旧缓存、部分接口），
     * 它比节次 id 稳定，但不如 `courseId` 精确 —— 宁可略宽，也不要让排除失效。
     */
    fun courseKey(course: Course): String = when {
        course.courseId.isNotBlank() -> "c:" + course.courseId.trim()
        course.courseNum.isNotBlank() -> "n:" + course.courseNum.trim()
        course.id.isNotBlank() -> "s:" + course.id.trim()
        course.uuid.isNotBlank() -> "s:" + course.uuid.trim()
        else -> "x:" + course.name.trim() + "|" + course.teacher.trim()
    }

    /**
     * 剔除被排除的课程。
     *
     * 这是**唯一**的排除判定入口：实签路径（`runLocked` / `dueNow`）与闹钟排程
     * （`nextDue`）都从 `AutoSignEngine` 取过滤后的课程列表，
     * 因此在这里生效一次即可覆盖所有路径 —— 尤其是 `nextDue` 那条**绕过**
     * `pending()` 的分支，它若不过滤，就会在被排除的课上白白唤醒一次。
     */
    fun filter(
        courses: List<Course>,
        permanent: Set<String>,
        todayOnly: Set<String>,
    ): List<Course> {
        if (courses.isEmpty()) return courses
        if (permanent.isEmpty() && todayOnly.isEmpty()) return courses
        return courses.filter { course ->
            val key = courseKey(course)
            key !in permanent && key !in todayOnly
        }
    }
}

/**
 * 「不自动打卡」名单的**落盘**。
 *
 * 刻意用一个独立的 SharedPreferences，而不是并入 `user_settings`：
 * 后者被 [AttendanceScheduler] 的开关与保活状态共用，混进去一旦读错
 * 会**直接改变自动签到的行为**；而排除表最坏被读错也只是多签/少签一节，
 * 两者的风险等级不该共用一条读写路径。
 *
 * 只存**今天**那一天：用户不可能在今天就决定「下周三不签」，
 * 因此没有必要维护一张按日期分片的表——日期一变，旧记录自然作废（见 [todayKeys]）。
 */
class AutoSignExclusionStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ 长期黑名单

    /** 长期不打卡的课程键。返回副本，调用方随便用。 */
    fun permanentKeys(): Set<String> = readKeySet(K_PERMANENT)

    fun isPermanent(key: String): Boolean = key.isNotBlank() && key in permanentKeys()

    fun setPermanent(key: String, excluded: Boolean) {
        if (key.isBlank()) return
        val next = permanentKeys().toMutableSet()
        if (excluded) next += key else next -= key
        prefs.edit().putStringSet(K_PERMANENT, next).apply()
    }

    // ------------------------------------------------------------------ 当天临时

    /**
     * 今天被临时跳过的课程键。
     *
     * 存的是「某一天 + 那天的键集合」而不是「键 → 日期」：
     * 读到记录的日期与 [today] 不一致时直接返回空集并顺势清掉，
     * 于是**跨天自动失效**这件事不需要任何定时任务来维护。
     */
    fun todayKeys(today: String): Set<String> {
        if (today.isBlank()) return emptySet()
        val storedDay = prefs.getString(K_DAY, null)
        if (storedDay != today) {
            // 昨天的记录已经没有意义：既是过期数据，也是隐私上的冗余。
            if (storedDay != null) clearToday()
            return emptySet()
        }
        return readKeySet(K_DAY_KEYS)
    }

    fun isExcludedToday(key: String, today: String): Boolean =
        key.isNotBlank() && key in todayKeys(today)

    fun setExcludedToday(key: String, today: String, excluded: Boolean) {
        if (key.isBlank() || today.isBlank()) return
        val storedDay = prefs.getString(K_DAY, null)
        // 日期变了就从空集重新开始，绝不把昨天的键带到今天。
        val next = if (storedDay == today) readKeySet(K_DAY_KEYS).toMutableSet() else mutableSetOf()
        if (excluded) next += key else next -= key
        prefs.edit().putString(K_DAY, today).putStringSet(K_DAY_KEYS, next).apply()
    }

    /** 清空当天名单（保留长期黑名单）。 */
    fun clearToday() {
        prefs.edit().remove(K_DAY).remove(K_DAY_KEYS).apply()
    }

    /** 清空全部（退出登录 / 切换账号时调用）。 */
    fun clearAll() {
        prefs.edit().clear().apply()
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 读取一个字符串集合。
     *
     * 返回**新的可变副本**：`getStringSet` 返回的是 SharedPreferences 内部实例，
     * 直接改它会造成「未提交却已生效」的假象（Android 官方文档明确警告）。
     */
    private fun readKeySet(name: String): Set<String> =
        prefs.getStringSet(name, null)?.toMutableSet() ?: emptySet()

    private companion object {
        /**
         * 独立于 `user_settings` 的一张表。
         *
         * 名字里带 `v1`：将来若把「当天」升级成「按周/按日期」的多天模型，
         * 直接换键名即可让旧数据自然失效，不会读到语义不同的旧结构。
         */
        private const val PREFS_NAME = "auto_sign_exclusions_v1"
        private const val K_PERMANENT = "permanent"
        private const val K_DAY = "day"
        private const val K_DAY_KEYS = "day_keys"
    }
}
