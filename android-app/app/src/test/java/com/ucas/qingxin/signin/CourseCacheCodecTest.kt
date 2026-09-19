package com.ucas.qingxin.signin

import com.ucas.qingxin.signin.course.CourseCacheCodec
import com.ucas.qingxin.signin.data.Course
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 课表本地缓存编解码的单元测试。
 *
 * 这层之所以值得单测，是因为它出错的**表现极其隐蔽**：少写一个字段不会报错、
 * 不会崩溃，只会让依赖该字段的功能静默失效。历史上 `courseId` / `courseNum`
 * 被漏掉，直接导致「长期不打卡」名单对不上键 —— 用户看到开关是开的，
 * 那门课却照签不误。因此下面用一整组用例把这两个字段钉死。
 */
class CourseCacheCodecTest {

    @Test
    fun `往返后所有字段都保持不变`() {
        val original = listOf(
            course(
                id = "88001",
                uuid = "a".repeat(32),
                name = "高等数学",
                teacher = "张三",
                beginTime = "08:00",
                endTime = "09:40",
                day = "20260917",
                signed = true,
                courseId = "12345",
                courseNum = "MATH101",
            ),
            course(
                id = "88002",
                name = "大学英语",
                teacher = "李四",
                beginTime = "10:00",
                endTime = "11:40",
                signed = false,
            ),
        )

        val restored = CourseCacheCodec.decode(CourseCacheCodec.encode(original))
        assertEquals(original, restored)
    }

    @Test
    fun `课程层标识必须落盘_否则不打卡名单会静默失效`() {
        // 这是本文件存在的核心理由：AutoSignExclusions.courseKey 优先用 courseId，
        // 而自动签到在多数唤醒里走的是**缓存**路径。缓存里没有 courseId，
        // 键就会退化成 `s:<节次id>`，与用户设置时写入的 `c:<courseId>` 对不上。
        val original = listOf(course(id = "88001", name = "高等数学", courseId = "12345", courseNum = "MATH101"))

        val restored = CourseCacheCodec.decode(CourseCacheCodec.encode(original))!!.single()
        assertEquals("12345", restored.courseId)
        assertEquals("MATH101", restored.courseNum)
    }

    @Test
    fun `旧缓存缺少课程层标识时退化成空串而不是解析失败`() {
        // 升级前落盘的条目没有这两个键。若因为「字段不全」就整份作废，
        // 用户会发现升级后离线时课表是空的 —— 比字段缺失本身更糟。
        val legacy = """
            [{"id":"88001","uuid":"u1","name":"高等数学","teacher":"张三",
              "begin":"08:00","end":"09:40","day":"20260917","signed":false}]
        """.trimIndent()

        val restored = CourseCacheCodec.decode(legacy)!!
        assertEquals(1, restored.size)
        assertEquals("高等数学", restored[0].name)
        assertEquals("", restored[0].courseId)
        assertEquals("", restored[0].courseNum)
    }

    @Test
    fun `空的课程列表往返后仍是空列表`() {
        // 「当天没有课」与「缓存不存在」必须区分：前者要显示成「这天没有课」，
        // 所以空列表是一个**合法且有含义**的值，不能退化成 null。
        val restored = CourseCacheCodec.decode(CourseCacheCodec.encode(emptyList()))
        assertEquals(emptyList<Course>(), restored)
    }

    @Test
    fun `损坏的报文返回 null 而不是空列表`() {
        // 返回空列表会让调用方以为「那天没课」，把损坏伪装成了正常结果。
        assertNull(CourseCacheCodec.decode("不是 JSON"))
        assertNull(CourseCacheCodec.decode("{\"courses\":[]}"))
    }

    @Test
    fun `数组里的非对象元素被跳过_其余照常解析`() {
        val mixed = """[null,"x",{"id":"1","name":"高等数学","day":"20260917"}]"""
        val restored = CourseCacheCodec.decode(mixed)!!
        assertEquals(1, restored.size)
        assertTrue(restored[0].name == "高等数学")
    }

    private fun course(
        id: String = "1",
        uuid: String = "",
        name: String = "课程",
        teacher: String = "",
        beginTime: String = "08:00",
        endTime: String = "09:40",
        day: String = "20260917",
        signed: Boolean = false,
        courseId: String = "",
        courseNum: String = "",
    ) = Course(
        id = id,
        uuid = uuid,
        name = name,
        teacher = teacher,
        beginTime = beginTime,
        endTime = endTime,
        day = day,
        signed = signed,
        courseId = courseId,
        courseNum = courseNum,
    )
}
