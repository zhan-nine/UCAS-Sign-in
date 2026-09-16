package com.ucas.qingxin.signin.course

import android.content.Context
import com.ucas.qingxin.signin.auth.AuthRepository
import com.ucas.qingxin.signin.data.Course
import com.ucas.qingxin.signin.data.CourseQueryResult
import com.ucas.qingxin.signin.network.QingxinApiService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
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

    suspend fun loadToday(date: LocalDate = LocalDate.now(ZONE)): CourseQueryResult {
        val session = auth.requireSession()
        val dateStr = date.format(DAY_FMT)
        return try {
            val result = api.getTodayCourses(session, dateStr)
            cache(session.studentNo, dateStr, result)
            _courses.value = result
            result
        } catch (e: Exception) {
            val cached = readCache(session.studentNo, dateStr)
            if (cached != null) {
                val withFlag = cached.copy(fromCache = true, message = "网络不可用，显示缓存课程")
                _courses.value = withFlag
                withFlag
            } else {
                throw e
            }
        }
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
     * 只读今日本地缓存，**不走网络**。
     * 供后台自动签到复用已有数据，避免每次唤醒都发请求。
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

    private fun cache(studentNo: String, dateStr: String, result: CourseQueryResult) {
        val arr = JSONArray()
        result.courses.forEach { c ->
            arr.put(
                JSONObject()
                    .put("id", c.id)
                    .put("uuid", c.uuid)
                    .put("name", c.name)
                    .put("teacher", c.teacher)
                    .put("begin", c.beginTime)
                    .put("end", c.endTime)
                    .put("day", c.day)
                    .put("signed", c.signed),
            )
        }
        prefs.edit()
            .putString("c_${studentNo}_$dateStr", arr.toString())
            .putString("m_${studentNo}_$dateStr", result.message)
            .apply()
    }

    private fun readCache(studentNo: String, dateStr: String): CourseQueryResult? {
        val raw = prefs.getString("c_${studentNo}_$dateStr", null) ?: return null
        val arr = JSONArray(raw)
        val list = buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(
                    Course(
                        id = o.optString("id"),
                        uuid = o.optString("uuid"),
                        name = o.optString("name"),
                        teacher = o.optString("teacher"),
                        beginTime = o.optString("begin"),
                        endTime = o.optString("end"),
                        day = o.optString("day"),
                        signed = o.optBoolean("signed"),
                    ),
                )
            }
        }
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
