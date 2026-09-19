package com.ucas.qingxin.signin.course

import com.ucas.qingxin.signin.data.Course
import org.json.JSONArray
import org.json.JSONObject

/**
 * 课表本地缓存的编解码。
 *
 * 独立成对象（而不是留在 `CourseRepository` 的私有方法里）有两个原因：
 * 1. `CourseRepository` 需要 `Context`，JVM 单测里没法直接构造，于是缓存的
 *    往返正确性一直测不到 —— 而它恰恰是最容易「少写一个字段」的地方；
 * 2. 编码/解码是一对必须同进同退的规则，放在一起才看得出是否对称。
 *
 * ## 为什么必须落盘 `courseId` / `courseNum`（曾经的 bug）
 * 这两个字段是**课程层**标识，而自动签到的「不打卡名单」正要靠它们做键
 * （见 `AutoSignExclusions.courseKey`：优先级 `courseId` → `courseNum` → 节次 id → 名称|教师）。
 *
 * 早先这里只写了 7 个字段，把两个课程层标识漏掉了。后果不是「少显示一行」，
 * 而是**静默漏签失效**：用户基于联网课表（`courseId = "12345"`）设了「长期不打卡」，
 * 键是 `c:12345`；此后自动签到在多数唤醒里走的是**本地缓存**路径，
 * 读回来的 `courseId` 是空串，键退化成 `s:<节次id>` 或 `x:<课程名|教师>`，
 * 与名单里的 `c:12345` 对不上 —— 于是那门课照签不误，而界面上开关明明是打开的。
 *
 * 这种「设置看着生效、实际不生效」的故障无法靠界面自查发现，必须靠落盘字段完整来保证。
 *
 * ## 兼容性
 * 旧缓存条目没有这两个键，[JSONObject.optString] 会给出空串，于是退化成
 * 早先的行为（而不是解析失败、整份缓存作废）。缓存键名没有变化，
 * 因此**不需要**清缓存：用户下一次联网刷新时自然会被补齐。
 */
internal object CourseCacheCodec {

    /** 序列化成紧凑 JSON 数组。字段名沿用短名（`begin`/`end`），与既有缓存保持一致。 */
    fun encode(courses: List<Course>): String {
        val arr = JSONArray()
        courses.forEach { c ->
            arr.put(
                JSONObject()
                    .put("id", c.id)
                    .put("uuid", c.uuid)
                    .put("name", c.name)
                    .put("teacher", c.teacher)
                    .put("begin", c.beginTime)
                    .put("end", c.endTime)
                    .put("day", c.day)
                    .put("signed", c.signed)
                    // 见 KDoc：这两个是「不打卡名单」的键来源，绝不能省。
                    .put("courseId", c.courseId)
                    .put("courseNum", c.courseNum),
            )
        }
        return arr.toString()
    }

    /**
     * 反序列化。
     *
     * @return 报文损坏（不是 JSON 数组）时返回 null，交给调用方决定是当作
     *   「没有缓存」还是「缓存不可用」；结构完好的空数组返回空列表。
     *   两者刻意区分：把损坏也说成「当天无课」会让用户以为课表是空的。
     */
    fun decode(raw: String): List<Course>? = runCatching {
        val arr = JSONArray(raw)
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
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
                        courseId = o.optString("courseId"),
                        courseNum = o.optString("courseNum"),
                    ),
                )
            }
        }
    }.getOrNull()
}
