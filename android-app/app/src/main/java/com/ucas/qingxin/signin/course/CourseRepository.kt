package com.ucas.qingxin.signin.course

import android.content.Context
import com.ucas.qingxin.signin.auth.AuthRepository
import com.ucas.qingxin.signin.data.Course
import com.ucas.qingxin.signin.data.CourseQueryResult
import com.ucas.qingxin.signin.network.QingxinApiService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class CourseRepository(
    context: Context,
    private val api: QingxinApiService,
    private val auth: AuthRepository,
) {
    private val prefs = context.applicationContext.getSharedPreferences("course_cache", Context.MODE_PRIVATE)
    private val _courses = MutableStateFlow<CourseQueryResult?>(null)
    val courses: StateFlow<CourseQueryResult?> = _courses.asStateFlow()

    /**
     * 抓取「今天」的课表，并把它发布到 [courses]。
     *
     * 这是**唯一**会写 [courses] 的入口。签到二维码、桌面小部件、自动签到引擎
     * 都直接消费这条 StateFlow，因此它必须始终是「今天」的课表 ——
     * 一旦被别的日期污染，守护进程会拿另一天的课去签（见 [browseDay] 的说明）。
     */
    suspend fun loadToday(date: LocalDate = LocalDate.now(ZONE)): CourseQueryResult {
        val result = fetchDay(date)
        _courses.value = result
        return result
    }

    /**
     * 抓取任意一天的课表，**只读浏览用**。
     *
     * ## 为什么不复用 [loadToday]
     * [loadToday] 会把结果写进 [courses]，而那条 StateFlow 是「今天」的唯一真值源：
     * - 桌面小部件按它渲染「今日课程」；
     * - 自动签到引擎在本地数据新鲜时会直接取它，然后按今天的随机时刻去签。
     *
     * 若浏览「明天」也写进去，那么用户在 10 分钟新鲜窗口内切到别的日期看一眼课表，
     * 守护进程下一次唤醒就会把**明天的课**当成今天的课去签到。因此这里的
     * 「不写 [courses]」是硬约束，不是实现细节。
     *
     * 代价只是：浏览页与首页各持有自己那份列表（本来也应如此 —— 两处看的是不同日期）。
     *
     * 网络路径与今天完全一致（同一个接口、同一份本地缓存），因此缓存命中时
     * 离线也能浏览之前看过的日期。
     */
    suspend fun browseDay(date: LocalDate): CourseQueryResult {
        val result = fetchDay(date)
        // 周课表回退时，网络层返回的是**整周**课程（对首页是合理的兜底：至少还能
        // 看到点东西），但那对「按日期看课表」是错的 —— 标题写着 9 月 20 日，
        // 列表却是整周的课。这里裁回请求的那一天；结果为空就说明那天真的没课，
        // 因为周数据已经权威地回答了这个问题，不需要再猜。
        val onDay = dedupe(coursesOn(result, date.format(DAY_FMT)))
        return result.copy(
            courses = onDay,
            // 空结果不给文案：上层返回的句子是写给首页的（会提「切换学号」），
            // 而这里下面那张占位卡片已经明确说了「这天没有课程」，再说一遍是噪音。
            message = if (onDay.isEmpty()) "" else result.message,
        )
    }

    /**
     * 去掉完全相同的课程行。
     *
     * 界面用「节次 id + 日期 + 开始时间」作 `LazyColumn` 的 key，重复行会让
     * 同一个 key 出现两次并**直接抛异常闪退**（项目此前就因重复 key 崩过一次）。
     * 服务端偶有重复条目，而两行在所有展示字段上都一样时丢掉一行毫无损失。
     */
    private fun dedupe(courses: List<Course>): List<Course> {
        if (courses.size < 2) return courses
        val seen = HashSet<String>(courses.size)
        return courses.filter { seen.add("${it.id}|${it.day}|${it.beginTime}") }
    }

    /**
     * 取某一天的课表：先联网，失败则回退本地缓存。
     *
     * 刻意**不碰** [courses]：由调用方（[loadToday] / [browseDay]）决定是否发布。
     */
    private suspend fun fetchDay(date: LocalDate): CourseQueryResult {
        val session = auth.requireSession()
        val dateStr = date.format(DAY_FMT)
        return try {
            val result = api.getTodayCourses(session, dateStr)
            // 只把**请求的那一天**写进缓存：缓存键 `c_<学号>_<yyyyMMdd>` 的字面意思
            // 就是「那一天的课」。周课表回退时 result 里带着整周，若原样落盘，
            // 下次读缓存就会把整周的课当成那一天的内容显示出来。
            cache(session.studentNo, dateStr, coursesOn(result, dateStr), result.message)
            result
        } catch (e: Exception) {
            val cached = readCache(session.studentNo, dateStr)
            if (cached != null) {
                cached.copy(fromCache = true, message = "网络不可用，显示缓存课程")
            } else {
                throw e
            }
        }
    }

    /**
     * 取出结果中属于 [dateKey] 那一天的部分。
     *
     * 非周课表回退的结果本身就是单日数据，原样返回（不做多余的过滤，
     * 避免因 `day` 字段缺失而把课程全部误删）。
     */
    private fun coursesOn(result: CourseQueryResult, dateKey: String): List<Course> =
        if (!result.fromWeeklyFallback) {
            result.courses
        } else {
            result.courses.filter { normalizeDay(it.day) == dateKey }
        }

    fun clearAccountCache(studentNo: String?) {
        if (studentNo.isNullOrBlank()) {
            prefs.edit().clear().apply()
        } else {
            prefs.all.keys.filter { it.startsWith("c_$studentNo") || it.startsWith("m_$studentNo") }
                .forEach { prefs.edit().remove(it).apply() }
        }
        _courses.value = null
    }

    /**
     * 当前课：开课前 25 分钟 ~ 下课前；时间重合时取排序后的前序课。
     * 下一节：当前课之后、课程名称不同的下一门（同名连排课跳过）。
     * 若无当前课：取 begin > now 的第一节。
     */
    fun currentAndNext(
        list: List<Course>,
        nowMs: Long = System.currentTimeMillis(),
        today: LocalDate = LocalDate.now(ZONE),
    ): Pair<Course?, Course?> {
        val sorted = todayCoursesSorted(list, today)
        val current = sorted.firstOrNull { isCurrentCourse(it, nowMs) }
        val currentBegin = current?.let { parseBeginMs(it) }
        val currentName = current?.name?.trim().orEmpty()
        val next = sorted.firstOrNull { course ->
            if (current != null && course.id == current.id) return@firstOrNull false
            // 同名课（连排/重复条目）跳过，取时间不同的下一门
            if (current != null && course.name.trim() == currentName) return@firstOrNull false
            val begin = parseBeginMs(course) ?: return@firstOrNull false
            if (currentBegin != null) begin > currentBegin else begin > nowMs
        }
        return current to next
    }

    /**
     * 签到目标：当前课（开课前25分钟~下课前）；优先未签到。
     * 无当前课时回退到窗口内第一节。
     */
    fun findQrLockCourse(
        list: List<Course>,
        nowMs: Long = System.currentTimeMillis(),
        today: LocalDate = LocalDate.now(ZONE),
    ): Course? {
        val (current, _) = currentAndNext(list, nowMs, today)
        if (current != null) return current
        val sorted = todayCoursesSorted(list, today)
        return sorted.firstOrNull { !it.signed && isWithinQrLockWindow(it, nowMs) }
            ?: sorted.firstOrNull { isWithinQrLockWindow(it, nowMs) }
    }

    fun isWithinQrLockWindow(course: Course, nowMs: Long): Boolean {
        val begin = parseBeginMs(course) ?: return false
        val end = parseEndMs(course) ?: return false
        return nowMs >= begin - QingxinApiService.SIGN_WINDOW_LEAD_MS && nowMs < end
    }

    /**
     * 只读某一天的本地缓存，**不走网络**。
     *
     * 两个用途：
     * - 后台自动签到复用今天已有的数据，避免每次唤醒都发请求；
     * - 课表浏览页先用缓存立刻渲染，再等网络结果覆盖（见 `ScheduleViewModel`）。
     *
     * 参数虽叫 `studentNo` + `date`，但日期不限于今天：缓存本来就是按日期分片的
     * （键 `c_<学号>_<yyyyMMdd>`），因此浏览任意已看过的日期都能命中。
     */
    fun cachedToday(
        studentNo: String,
        date: LocalDate = LocalDate.now(ZONE),
    ): CourseQueryResult? = runCatching {
        if (studentNo.isBlank()) null else readCache(studentNo, date.format(DAY_FMT))
    }.getOrNull()

    /** 当前课窗口 = 开课前 25 分钟至下课前（含提前显示）。 */
    fun isCurrentCourse(course: Course, nowMs: Long): Boolean = isWithinQrLockWindow(course, nowMs)

    private fun todayCoursesSorted(list: List<Course>, today: LocalDate): List<Course> {
        val todayKey = today.format(DAY_FMT)
        val todayCourses = list.filter { normalizeDay(it.day) == todayKey }.ifEmpty { list }
        return todayCourses.sortedWith(
            compareBy<Course> { parseBeginMs(it) ?: Long.MAX_VALUE }
                .thenBy { parseEndMs(it) ?: Long.MAX_VALUE }
                .thenBy { it.name },
        )
    }

    fun isInSignWindow(course: Course, schoolOrLocalNowMs: Long): Boolean {
        if (course.signed) return false
        return isWithinQrLockWindow(course, schoolOrLocalNowMs)
    }

    /** 是否已正式上课（不含提前 25 分钟）。 */
    fun isInProgress(course: Course, nowMs: Long): Boolean {
        val begin = parseBeginMs(course) ?: return false
        val end = parseEndMs(course) ?: return false
        return nowMs >= begin && nowMs < end
    }

    fun parseBeginMs(course: Course): Long? = parseCourseInstant(course.day, course.beginTime)
    fun parseEndMs(course: Course): Long? = parseCourseInstant(course.day, course.endTime)

    fun normalizeDay(day: String): String {
        val d = day.trim().replace("-", "").replace("/", "")
        return if (d.length >= 8) d.take(8) else d
    }

    private fun cache(studentNo: String, dateStr: String, courses: List<Course>, message: String) {
        prefs.edit()
            .putString("c_${studentNo}_$dateStr", CourseCacheCodec.encode(courses))
            .putString("m_${studentNo}_$dateStr", message)
            .apply()
    }

    private fun readCache(studentNo: String, dateStr: String): CourseQueryResult? {
        val raw = prefs.getString("c_${studentNo}_$dateStr", null) ?: return null
        // 解码失败（缓存损坏）与「空课表」必须区分：说成「当天无课」会误导用户。
        val list = CourseCacheCodec.decode(raw) ?: return null
        return CourseQueryResult(
            courses = list,
            fromWeeklyFallback = false,
            message = prefs.getString("m_${studentNo}_$dateStr", "缓存课程") ?: "缓存课程",
            fromCache = true,
        )
    }

    companion object {
        val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
        val DAY_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

        /**
         * 对齐原版 np0.a：
         * - `"2026-09-15 08:00:00"` / 含 T → LocalDateTime
         * - 纯时刻 + day(yyyyMMdd 或 yyyy-MM-dd) → LocalDate.atTime
         * 额外兼容 `8:00`、中文冒号、小数秒。
         */
        fun parseCourseInstant(day: String, timeRaw: String): Long? {
            if (timeRaw.isBlank()) return null
            return try {
                val trimmed = timeRaw.trim().replace('：', ':')
                // 原版：空格替换为 T（不要先删光空白，否则日期与时刻会粘连）
                val withT = trimmed.replace(' ', 'T')
                val dateTime: LocalDateTime = if (withT.contains('T')) {
                    parseDateTimeFlexible(withT)
                } else {
                    val date = parseDayFlexible(day) ?: LocalDate.now(ZONE)
                    date.atTime(parseTimeFlexible(withT))
                }
                dateTime.atZone(ZONE).toInstant().toEpochMilli()
            } catch (_: Exception) {
                null
            }
        }

        private fun parseDayFlexible(day: String): LocalDate? {
            val d = day.trim()
            if (d.isEmpty()) return null
            return try {
                when {
                    d.contains('-') -> LocalDate.parse(d.substringBefore('T').substringBefore(' '))
                    d.length >= 8 && d.take(8).all { it.isDigit() } ->
                        LocalDate.parse(d.take(8), DAY_FMT)
                    else -> null
                }
            } catch (_: Exception) {
                null
            }
        }

        private fun parseDateTimeFlexible(raw: String): LocalDateTime {
            val s = raw.trim()
            return try {
                LocalDateTime.parse(s)
            } catch (_: Exception) {
                // 截断小数秒 / 时区后缀
                val cleaned = s
                    .replace(Regex("""\.\d+"""), "")
                    .removeSuffix("Z")
                    .substringBefore('+')
                try {
                    LocalDateTime.parse(cleaned)
                } catch (_: Exception) {
                    // 最后手段：拆成日期 + 时刻
                    val parts = cleaned.split('T', limit = 2)
                    require(parts.size == 2)
                    val date = LocalDate.parse(parts[0])
                    date.atTime(parseTimeFlexible(parts[1]))
                }
            }
        }

        private fun parseTimeFlexible(raw: String): LocalTime {
            val t = raw.trim()
                .replace('：', ':')
                .replace('．', '.')
                .replace(Regex("""\.\d+"""), "") // 去掉小数秒
            // H:mm / HH:mm / HH:mm:ss，小时可一位
            Regex("""^(\d{1,2}):(\d{2})(?::(\d{2}))?$""").matchEntire(t)?.let { m ->
                val h = m.groupValues[1].toInt()
                val min = m.groupValues[2].toInt()
                val sec = m.groupValues[3].ifEmpty { "0" }.toInt()
                return LocalTime.of(h, min, sec)
            }
            // 0800 / 080000
            when {
                t.matches(Regex("\\d{3,4}")) -> {
                    val p = t.padStart(4, '0')
                    return LocalTime.of(p.substring(0, 2).toInt(), p.substring(2, 4).toInt())
                }
                t.matches(Regex("\\d{6}")) -> {
                    return LocalTime.of(
                        t.substring(0, 2).toInt(),
                        t.substring(2, 4).toInt(),
                        t.substring(4, 6).toInt(),
                    )
                }
            }
            return LocalTime.parse(t)
        }
    }
}
