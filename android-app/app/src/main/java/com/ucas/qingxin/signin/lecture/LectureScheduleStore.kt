package com.ucas.qingxin.signin.lecture

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 讲座时间表（预约系统）最后一次成功读取的结果。
 *
 * ## 为什么**这个**可以落盘，而学院网站的通知不落盘
 * 两者对「缓存」的态度相反，而这不是不一致，是同一个标准得出的不同结论：
 *
 * - 学院网站是**免登录、随时可抓**的，缓存只会带来滞后（用户看到几天前的「最新通知」），
 *   因此那里明确不缓存；
 * - 预约系统**必须登录**，而登录态随时可能过期、过期后应用**无法自己恢复**
 *   （它不代持凭据，也不能替用户过验证码）。若不留盘，用户一旦掉线，
 *   整个讲座页就只剩「请重新登录」——连上次看到的讲座全没了。
 *   因此这里落盘，并且在界面上**显式标注读取时间**，让用户自己判断新不新。
 *
 * ## 存什么
 * 只存讲座**内容**（名称 / 时间 / 场地 / 栏目）。不存 Cookie、不存页面、不存任何
 * 与登录有关的东西 —— 会话完全由 WebView 自己管理（见 `LectureScheduleScreen`），
 * 本类甚至不持有它的句柄。
 *
 * 公开（非 `internal`）：它是 `QingxinApp` 这个公开依赖容器上的属性，
 * Kotlin 不允许公开属性暴露 internal 类型。本类自身只描述「讲座内容」，
 * 不含任何端点信息，因此放开可见性没有隐私代价。
 */
class LectureScheduleStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 上次成功读取的讲座（按名称+时间+场地去重后的原样列表）。 */
    fun events(): List<LectureEvent> {
        val raw = prefs.getString(K_EVENTS, null) ?: return emptyList()
        return decode(raw)
    }

    /** 上次成功读取的时间（`yyyy-MM-dd HH:mm`）；从未读过时为空串。 */
    fun readAt(): String = prefs.getString(K_READ_AT, "").orEmpty()

    /** 上次读取覆盖的栏目（用于告诉用户「这份数据包含哪几类讲座」）。 */
    fun kinds(): Set<LectureEventKind> {
        val raw = prefs.getString(K_KINDS, null) ?: return emptySet()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptySet()
        val out = LinkedHashSet<LectureEventKind>()
        for (i in 0 until array.length()) {
            kindOf(array.optString(i))?.let { out += it }
        }
        return out
    }

    /**
     * 保存一次读取结果。
     *
     * 只保存**合并后**的并集：调用的语义是「这一轮读到的全部讲座」，
     * 因此由调用方负责把两个栏目合并后再传进来（见 `LectureScheduleViewModel`）。
     */
    fun save(events: List<LectureEvent>, readAt: String) {
        val array = JSONArray()
        for (event in events) {
            array.put(
                JSONObject().apply {
                    put("t", event.title)
                    put("d", event.date)
                    put("s", event.startTime)
                    put("e", event.endTime)
                    put("l", event.location)
                    put("k", event.kind.name)
                },
            )
        }
        prefs.edit()
            .putString(K_EVENTS, array.toString())
            .putString(K_READ_AT, readAt)
            .putString(K_KINDS, JSONArray(events.map { it.kind.name }.distinct()).toString())
            .apply()
    }

    /** 清空（退出登录 / 换账号时调用：讲座内容虽不含隐私，但继承上一账号的列表并不合适）。 */
    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun decode(raw: String): List<LectureEvent> {
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = ArrayList<LectureEvent>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val title = item.optString("t").trim()
            if (title.isEmpty()) continue
            out += LectureEvent(
                title = title,
                date = item.optString("d"),
                startTime = item.optString("s"),
                endTime = item.optString("e"),
                location = item.optString("l"),
                // 栏目名解析不出时退化为人文讲座而不是丢弃整条：
                // 一份缺了栏目名的时间表仍然有用。
                kind = kindOf(item.optString("k")) ?: LectureEventKind.HUMANITY,
            )
        }
        return out
    }

    private fun kindOf(name: String): LectureEventKind? =
        LectureEventKind.entries.firstOrNull { it.name == name }

    private companion object {
        const val PREFS_NAME = "lecture_schedule_v1"
        const val K_EVENTS = "events"
        const val K_READ_AT = "read_at"
        const val K_KINDS = "kinds"
    }
}
