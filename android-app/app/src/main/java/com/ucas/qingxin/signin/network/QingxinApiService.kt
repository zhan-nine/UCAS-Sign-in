package com.ucas.qingxin.signin.network

import com.ucas.qingxin.signin.BuildConfig
import com.ucas.qingxin.signin.data.Course
import com.ucas.qingxin.signin.data.CourseQueryResult
import com.ucas.qingxin.signin.data.SchoolSession
import com.ucas.qingxin.signin.data.SignOutcome
import com.ucas.qingxin.signin.data.SignResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Confirmed UCAS iClass / 轻新课堂 endpoints from original RememberSignIn APK.
 * Do not invent additional APIs.
 */
class QingxinApiService(
    private val client: OkHttpClient = defaultClient(),
) {
    companion object {
        /**
         * 后端端点一律来自 [BuildConfig]（由本地 local.properties 注入），
         * **不得在此硬编码主机或端口**：仓库为公开仓库，端点不应入库。
         */
        val BASE_URL: String = BuildConfig.API_BASE_URL

        /** 登录请求体内嵌入的校方校验地址模板（由本地配置注入）。 */
        val MOBILE_CHECK_TEMPLATE: String = BuildConfig.VERIFY_URL_TEMPLATE

        /** 7 位节次 ID（`courseSchedId`）。 */
        private val SEVEN_DIGIT_ID = Regex("""\d{7}""")

        /** 32 位课表 UUID（`timeTableId`，比较前会先去掉连字符）。 */
        private val HEX32_ID = Regex("""[0-9a-fA-F]{32}""")

        const val USER_AGENT = "student_5.0.1.2_android_12_20_100000000000000_110000"
        const val USER_AGENT_LOGIN = "student_5.0.1.2_android_12_20__110000"

        /** Confirmed in original app: school-clock sample valid for 30s. */
        const val TIME_SYNC_TTL_MS = 30_000L
        /** Confirmed in original app: QR refresh uses min(5000, remaining sync TTL). */
        const val QR_REFRESH_CAP_MS = 5_000L
        /** 签到二维码自动锁定：开课前 25 分钟至下课前。 */
        const val SIGN_WINDOW_LEAD_MS = 25L * 60L * 1000L

        /** `yyyyMMdd` 的长度，用于判定日期字段是否可用。 */
        private const val DAY_KEY_LENGTH = 8

        fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
    }

    suspend fun login(email: String, password: String): SchoolSession = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("phone", email.trim())
            .add("password", password)
            .add("verificationType", "1")
            .add("verificationUrl", MOBILE_CHECK_TEMPLATE)
            .add("userLevel", "1")
            .build()
        val json = executeJson(
            requestBuilder("user/login.action", session = null)
                .header("User-Agent", USER_AGENT_LOGIN)
                .post(body)
                .build(),
        )
        parseSession(json)
    }

    /** Resume with identity only (verificationType=2), as in original app. */
    suspend fun resumeSession(identity: String): SchoolSession = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("phone", identity.trim())
            .add("password", "")
            .add("verificationType", "2")
            .add("verificationUrl", "")
            .add("userLevel", "1")
            .build()
        val json = executeJson(
            requestBuilder("user/login.action", session = null)
                .header("User-Agent", USER_AGENT_LOGIN)
                .post(body)
                .build(),
        )
        parseSession(json)
    }

    /** Confirmed: POST common/get_timestamp.do?id=0 → {STATUS, timestamp} */
    suspend fun getServerTimestampMs(): Long = withContext(Dispatchers.IO) {
        val json = executeJson(
            requestBuilder("common/get_timestamp.do?id=0", session = null)
                .post(FormBody.Builder().build())
                .build(),
            timeoutSec = 6,
        )
        if (json.optString("STATUS") != "0") {
            throw ApiException("TIME_SYNC_BAD_RESPONSE", "学校校时数据异常，请重试")
        }
        val ts = json.opt("timestamp")
        if (ts !is Number) {
            throw ApiException("TIME_SYNC_BAD_RESPONSE", "学校校时数据异常，请重试")
        }
        val value = ts.toLong()
        if (value !in 1_000_000_000_000L until 8_640_000_000_000_001L) {
            throw ApiException("TIME_SYNC_BAD_RESPONSE", "学校校时数据异常，请重试")
        }
        value
    }

    suspend fun getTodayCourses(session: SchoolSession, dateStr: String): CourseQueryResult =
        withContext(Dispatchers.IO) {
            val form = FormBody.Builder()
                .add("id", session.userId)
                .add("dateStr", dateStr)
                .build()
            val dayJson = executeJson(
                requestBuilder("course/get_stu_course_sched.action", session)
                    .post(form)
                    .build(),
            )
            val dayCourses = parseSchedArray(dayJson.optJSONArray("result"), dateStr)
            if (dayJson.optString("STATUS") == "0" && dayCourses.isNotEmpty()) {
                return@withContext CourseQueryResult(dayCourses, false, "已更新当天课程")
            }

            val weekJson = executeJson(
                requestBuilder("course/get_stu_course_sched_week.action", session)
                    .post(form)
                    .build(),
            )
            if (weekJson.optString("STATUS") == "0") {
                throw ApiException("SCHEDULE_REJECTED", "学校暂未返回可用课表，请重新查询")
            }
            val result = weekJson.optJSONArray("result") ?: JSONArray()
            val all = mutableListOf<Course>()
            val today = mutableListOf<Course>()
            for (i in 0 until result.length()) {
                val dayObj = result.optJSONObject(i) ?: continue
                val rawDate = dayObj.optString("dateStr").replace("-", "")
                val list = parseSchedArray(dayObj.optJSONArray("schedData"), rawDate)
                all += list
                if (rawDate == dateStr) today += list
            }
            when {
                today.isNotEmpty() -> CourseQueryResult(today, false, "已从周课表更新当天课程")
                all.isNotEmpty() -> CourseQueryResult(all, true, "当天没有课程，已显示本周课程")
                else -> CourseQueryResult(emptyList(), false, "当天及本周暂无课程，可检查日期或切换学号")
            }
        }

    /**
     * 抓取 [anchorDateStr] 所在周的**全部**课程（所有日期），用于讲座的多日扫描。
     *
     * 讲座散布在多个日期上，逐日查询代价过高；周课表一次请求覆盖整周，
     * 调用方再按日期范围自行裁剪（见 `LectureRepository.loadRange`）。
     * [Course.day] 取各天的 `dateStr` 并统一成 `yyyyMMdd`，
     * 使调用方不必再关心学校返回的是 `2026-09-15` 还是 `20260915`。
     */
    suspend fun getWeekCourses(session: SchoolSession, anchorDateStr: String): List<Course> =
        withContext(Dispatchers.IO) {
            val form = FormBody.Builder()
                .add("id", session.userId)
                .add("dateStr", anchorDateStr)
                .build()
            val json = executeJson(
                requestBuilder("course/get_stu_course_sched_week.action", session)
                    .post(form)
                    .build(),
            )
            val result = json.optJSONArray("result")
            // 本接口的 STATUS 约定在既有代码里并不一致（getTodayCourses 的分支把 STATUS=="0"
            // 当作「无课表」而抛错）。这里改以「是否真的拿到 result 数组」为准：
            // 只要有数据就解析，确实没有数据才按失败上报，
            // 免得因状态码约定不清而丢掉本该展示的讲座或误报登录失效。
            if (result == null) {
                if (json.optString("STATUS") == "0") return@withContext emptyList()
                throw ApiException("SCHEDULE_REJECTED", "学校暂未返回可用课表，请重新查询")
            }
            val out = ArrayList<Course>()
            for (i in 0 until result.length()) {
                val dayObj = result.optJSONObject(i) ?: continue
                val dayKey = normalizeDayKey(dayObj.optString("dateStr"))
                // 日期缺失或异常的整天都无法参与范围过滤，直接跳过而不是猜一个日期。
                if (dayKey.length != DAY_KEY_LENGTH) continue
                out += parseSchedArray(dayObj.optJSONArray("schedData"), dayKey)
            }
            out
        }

    /**
     * Confirmed one-click sign:
     * GET course/stu_scan_sign.action?<idParam>=&timestamp=&id=
     * Header sessionId required.
     *
     * @param courseIdOrUuid 7 位节次 ID（`courseSchedId`）**或** 32 位课表 UUID
     *   （`timeTableId`）。学校这个接口对两种标识各有一个查询参数，参数名取决于
     *   标识形态，因此这里统一接收「二选一」的原始输入，再按形态派发 ——
     *   若只接受 `courseSchedId`，那些只有 UUID 的课程（以及手动录入 UUID 的讲座，
     *   见 [com.ucas.qingxin.signin.ui.LectureViewModel.signManual]）会带着空参数
     *   发出必然失败的请求。
     */
    suspend fun submitAttendance(
        session: SchoolSession,
        courseIdOrUuid: String,
        schoolTimestampMs: Long,
    ): SignResult = withContext(Dispatchers.IO) {
        val (idParam, idValue) = signIdParameter(courseIdOrUuid)
        val url = (BASE_URL + "course/stu_scan_sign.action").toHttpUrl().newBuilder()
            .addQueryParameter(idParam, idValue)
            .addQueryParameter("timestamp", schoolTimestampMs.toString())
            .addQueryParameter("id", session.userId)
            .build()
        val json = executeJson(
            Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Cache-Control", "no-store")
                .header("sessionId", session.sessionId)
                .get()
                .build(),
            timeoutSec = 10,
        )
        parseSignResult(json)
    }

    /** Build QR content URL exactly as original ez0.d(). */
    fun buildSignQrUrl(courseIdOrUuid: String, schoolTimestampMs: Long): String {
        val (param, value) = signIdParameter(courseIdOrUuid)
        return (BASE_URL + "course/stu_scan_sign.action").toHttpUrl().newBuilder()
            .addQueryParameter(param, value)
            .addQueryParameter("timestamp", schoolTimestampMs.toString())
            .build()
            .toString()
    }

    /**
     * 「7 位节次 ID / 32 位课表 UUID」→ 学校接口的查询参数名与取值。
     *
     * 这是全项目**唯一**的标识形态判定点：一键签到（[submitAttendance]）与
     * 二维码内容构造（[buildSignQrUrl]）都走这里，避免两处规则漂移 ——
     * 一旦漂移，就会出现「扫同一个码能签、点按钮不能签」这类极难排查的问题。
     */
    private fun signIdParameter(courseIdOrUuid: String): Pair<String, String> {
        val trimmed = courseIdOrUuid.trim()
        val compact = trimmed.replace("-", "")
        return when {
            SEVEN_DIGIT_ID.matches(trimmed) -> "courseSchedId" to trimmed
            HEX32_ID.matches(compact) -> "timeTableId" to compact.uppercase(Locale.ROOT)
            else -> throw ApiException("COURSE_ID_INVALID", "请输入 7 位课程 ID 或 32 位 UUID")
        }
    }

    private fun parseSession(json: JSONObject): SchoolSession {
        if (json.optString("STATUS") != "0") {
            throw ApiException("LOGIN_REJECTED", "学校账号验证失败，请重新输入密码")
        }
        val result = json.optJSONObject("result")
            ?: throw ApiException("LOGIN_BAD_RESPONSE", "学校登录返回不完整")
        val id = result.optString("id").trim()
        val sessionId = result.optString("sessionId").trim()
        val studentNo = result.optString("studentNo").trim()
        if (id.isEmpty() || sessionId.isEmpty() || studentNo.isEmpty()) {
            throw ApiException("LOGIN_BAD_RESPONSE", "学校登录未返回完整身份，已停止操作")
        }
        return SchoolSession(id, sessionId, studentNo)
    }

    private fun parseSchedArray(array: JSONArray?, day: String): List<Course> {
        if (array == null) return emptyList()
        val out = ArrayList<Course>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            var name = o.optString("courseName").trim()
            if (name.isEmpty()) name = "未命名课程"
            out += Course(
                id = o.optString("id").trim(),
                uuid = o.optString("uuid").trim(),
                name = name,
                teacher = o.optString("teacherName").trim(),
                beginTime = o.optString("classBeginTime").trim(),
                endTime = o.optString("classEndTime").trim(),
                day = day,
                signed = o.optString("signStatus") == "1",
                // 课程层标识：讲座发现的回退阶梯与课程注册表桥接都依赖它们。
                // 学校返回的可能是数字也可能是字符串，optString 一并接受。
                courseId = o.optText("courseId"),
                courseNum = o.optText("courseNum"),
            )
        }
        return out
    }

    /**
     * 读取字符串字段，并把「显式 null / 缺失」统一成空串。
     *
     * 为什么不用裸 `optString`：学校偶尔把 `courseId` 这类字段发成 JSON `null`，
     * 而 `optString` 会把它变成字面量 `"null"`，下游拿去做 id 匹配就会莫名其妙地对不上。
     */
    private fun JSONObject.optText(key: String): String {
        val raw = optString(key).trim()
        return if (raw == "null") "" else raw
    }

    /** `dateStr` 可能是 `yyyy-MM-dd` 或 `yyyyMMdd`，统一成 `yyyyMMdd` 供比较。 */
    private fun normalizeDayKey(raw: String): String {
        val d = raw.trim().replace("-", "").replace("/", "")
        return if (d.length >= DAY_KEY_LENGTH) d.take(DAY_KEY_LENGTH) else d
    }

    private fun parseSignResult(json: JSONObject): SignResult {
        val result = json.optJSONObject("result") ?: JSONObject()
        val status = json.optString("STATUS")
        val errCode = json.optString("ERRCODE").take(64)
        var msg = result.optString("msg")
        if (msg.isBlank()) msg = json.optString("ERRMSG")
        if (msg.isBlank()) msg = json.optString("msg")
        if (msg.isBlank()) msg = json.optString("message")
        val stuSignStatus = result.optString("stuSignStatus")
        val expired = Pattern.compile(
            "(二维码|签到码).*(失效|过期)|timestamp.*(invalid|expired)",
            Pattern.CASE_INSENSITIVE or Pattern.DOTALL,
        ).matcher(msg).find()
        val outside = Pattern.compile("未在上课时间|不在.*签到时间|不是上课时间|未选.*课|不属于.*课|已签到|重复签到")
            .matcher(msg).find()

        val outcome = when {
            expired -> SignOutcome.QR_EXPIRED
            status == "0" && (errCode.isBlank() || errCode == "0") &&
                stuSignStatus == "1" &&
                (!json.has("success") || json.optBoolean("success", true)) -> SignOutcome.SIGNED
            outside || !(status == "0" && stuSignStatus.isNotBlank() && stuSignStatus != "1") ->
                SignOutcome.OUTSIDE_SIGN_WINDOW
            else -> SignOutcome.SIGN_RESULT_UNKNOWN
        }
        val message = when (outcome) {
            SignOutcome.SIGNED -> "签到成功"
            SignOutcome.QR_EXPIRED -> "学校提示签到码已失效，请刷新后重试"
            SignOutcome.OUTSIDE_SIGN_WINDOW -> when {
                msg.contains("已签到") || msg.contains("重复签到") -> "学校提示已签到，请刷新课程状态"
                msg.contains("未选") || msg.contains("不属于") -> "学校提示当前身份未选此课程"
                else -> "学校未完成签到，请确认上课时间和课程状态"
            }
            SignOutcome.SIGN_RESULT_UNKNOWN ->
                "学校未明确确认签到完成，请查询课程状态后再决定是否重试"
        }
        return SignResult(
            outcome = outcome,
            message = message,
            status = status,
            errCode = errCode,
            stuSignId = result.optString("stuSignId"),
        )
    }

    private fun requestBuilder(path: String, session: SchoolSession?): Request.Builder {
        val builder = Request.Builder()
            .url(BASE_URL + path)
            .header("User-Agent", USER_AGENT)
            .header("Cache-Control", "no-store")
        if (session != null) {
            builder.header("sessionId", session.sessionId)
        }
        return builder
    }

    private fun executeJson(request: Request, timeoutSec: Long = 15): JSONObject {
        val callClient = if (timeoutSec == 15L) {
            client
        } else {
            client.newBuilder()
                .callTimeout(timeoutSec, TimeUnit.SECONDS)
                .readTimeout(timeoutSec, TimeUnit.SECONDS)
                .build()
        }
        callClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw ApiException("HTTP_${response.code}", "网络请求失败 (${response.code})")
            }
            return JSONObject(body.ifBlank { "{}" })
        }
    }
}

class ApiException(val code: String, message: String) : Exception(message)
