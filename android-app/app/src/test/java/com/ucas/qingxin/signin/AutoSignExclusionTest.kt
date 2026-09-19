package com.ucas.qingxin.signin

import com.ucas.qingxin.signin.attendance.AutoSignExclusions
import com.ucas.qingxin.signin.data.Course
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 「不自动打卡」判定的单元测试。
 *
 * 只测纯逻辑（键构造 + 过滤），不碰 SharedPreferences：落盘那部分由
 * [AutoSignExclusionStore] 负责，其行为是「读写一个集合」，没有可测的算法，
 * 而它对正确性的影响全部经由这里的键与过滤体现出来。
 */
class AutoSignExclusionTest {

    // ------------------------------------------------------------------ filter

    @Test
    fun `两份名单都为空时原样返回`() {
        val courses = listOf(course(id = "1"), course(id = "2"))
        val kept = AutoSignExclusions.filter(courses, permanent = emptySet(), todayOnly = emptySet())
        assertEquals(courses, kept)
    }

    @Test
    fun `长期黑名单里的课被剔除_其余保留`() {
        val a = course(id = "1", courseId = "C1", name = "高等数学")
        val b = course(id = "2", courseId = "C2", name = "大学英语")
        val kept = AutoSignExclusions.filter(
            listOf(a, b),
            permanent = setOf(AutoSignExclusions.courseKey(a)),
            todayOnly = emptySet(),
        )
        assertEquals(listOf(b), kept)
    }

    @Test
    fun `当天临时名单里的课被剔除_其余保留`() {
        val a = course(id = "1", courseId = "C1", name = "高等数学")
        val b = course(id = "2", courseId = "C2", name = "大学英语")
        val kept = AutoSignExclusions.filter(
            listOf(a, b),
            permanent = emptySet(),
            todayOnly = setOf(AutoSignExclusions.courseKey(b)),
        )
        assertEquals(listOf(a), kept)
    }

    @Test
    fun `两份名单同时生效_命中任一即剔除`() {
        val a = course(id = "1", courseId = "C1", name = "高等数学")
        val b = course(id = "2", courseId = "C2", name = "大学英语")
        val c = course(id = "3", courseId = "C3", name = "大学物理")
        val kept = AutoSignExclusions.filter(
            listOf(a, b, c),
            permanent = setOf(AutoSignExclusions.courseKey(a)),
            todayOnly = setOf(AutoSignExclusions.courseKey(b)),
        )
        assertEquals(listOf(c), kept)
    }

    @Test
    fun `名单里多余的键不会误伤任何课程`() {
        val courses = listOf(course(id = "1", courseId = "C1"), course(id = "2", courseId = "C2"))
        val kept = AutoSignExclusions.filter(
            courses,
            permanent = setOf("c:不存在的课"),
            todayOnly = setOf("n:另一门不存在的课"),
        )
        assertEquals(courses, kept)
    }

    @Test
    fun `空课表不会因为过滤抛异常`() {
        assertEquals(
            emptyList<Course>(),
            AutoSignExclusions.filter(emptyList(), setOf("c:1"), setOf("c:2")),
        )
    }

    // ------------------------------------------------------------------ courseKey

    @Test
    fun `优先用课程层标识_而不是每天都会变的节次标识`() {
        // 这是长期黑名单能生效的前提：节次 id 每天不同，用它做键会导致「今天排除了明天又签上」。
        val c = course(id = "1234567", uuid = "a".repeat(32), courseId = "C0001", courseNum = "N0001")
        assertEquals("c:C0001", AutoSignExclusions.courseKey(c))
    }

    @Test
    fun `没有 courseId 时退回 courseNum`() {
        val c = course(id = "1234567", courseNum = "N0001")
        assertEquals("n:N0001", AutoSignExclusions.courseKey(c))
    }

    @Test
    fun `课程层标识都缺失时退回节次标识`() {
        assertEquals("s:1234567", AutoSignExclusions.courseKey(course(id = "1234567")))
        assertEquals("s:abc", AutoSignExclusions.courseKey(course(id = "", uuid = "abc")))
    }

    @Test
    fun `全都没有时退回课程名与教师`() {
        val c = course(id = "", uuid = "", name = " 高等数学 ", teacher = " 张三 ")
        assertEquals("x:高等数学|张三", AutoSignExclusions.courseKey(c))
    }

    @Test
    fun `不同命名空间的相同字符串不会撞键`() {
        // 若不加前缀，courseId 与节次 id 恰好相同就会互相污染：
        // 排除一节课会连带排除掉另一门课。
        val byCourseId = course(id = "1234567", courseId = "1234567")
        val bySessionId = course(id = "1234567")
        assertNotEquals(
            AutoSignExclusions.courseKey(byCourseId),
            AutoSignExclusions.courseKey(bySessionId),
        )
    }

    // ------------------------------------------------------------------ 夹具

    private fun course(
        id: String,
        uuid: String = "",
        name: String = "课程",
        teacher: String = "教师",
        courseId: String = "",
        courseNum: String = "",
    ) = Course(
        id = id,
        uuid = uuid,
        name = name,
        teacher = teacher,
        beginTime = "08:00",
        endTime = "09:40",
        day = "20260916",
        signed = false,
        courseId = courseId,
        courseNum = courseNum,
    )
}
