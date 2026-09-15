package com.ucas.qingxin.signin.network

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
        const val BASE_URL = "https://iclass.ucas.edu.cn:8181/app/"
        /** Server-side verification URL template embedded in login POST; client itself uses HTTPS. */
        const val MOBILE_CHECK_TEMPLATE =
            "http://iclass.ucas.edu.cn:88/ve/webservices/mobileCheck.shtml" +
                "?method=mobileLogin&username=\${0}&password=\${1}&lx=\${2}"
        const val USER_AGENT = "student_5.0.1.2_android_12_20_100000000000000_110000"
        const val USER_AGENT_LOGIN = "student_5.0.1.2_android_12_20__110000"

        /** Confirmed in original app: school-clock sample valid for 30s. */
        const val TIME_SYNC_TTL_MS = 30_000L
        /** Confirmed in original app: QR refresh uses min(5000, remaining sync TTL). */
        const val QR_REFRESH_CAP_MS = 5_000L
        /** 签到二维码自动锁定：开课前 25 分钟至下课前。 */
        const val SIGN_WINDOW_LEAD_MS = 25L * 60L * 1000L

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
     * Confirmed one-click sign:
     * GET course/stu_scan_sign.action?courseSchedId=&timestamp=&id=
     * Header sessionId required.
     */
    suspend fun submitAttendance(
        session: SchoolSession,
        courseSchedId: String,
        schoolTimestampMs: Long,
    ): SignResult = withContext(Dispatchers.IO) {
        val url = (BASE_URL + "course/stu_scan_sign.action").toHttpUrl().newBuilder()
            .addQueryParameter("courseSchedId", courseSchedId)
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
        val trimmed = courseIdOrUuid.trim()
        val compact = trimmed.replace("-", "")
        val (param, value) = when {
            Pattern.compile("[0-9]{7}").matcher(trimmed).matches() ->
                "courseSchedId" to trimmed
            Pattern.compile("[0-9a-fA-F]{32}").matcher(compact).matches() ->
                "timeTableId" to compact.uppercase(Locale.ROOT)
            else -> throw ApiException("COURSE_ID_INVALID", "请输入 7 位课程 ID 或 32 位 UUID")
        }
        return (BASE_URL + "course/stu_scan_sign.action").toHttpUrl().newBuilder()
            .addQueryParameter(param, value)
            .addQueryParameter("timestamp", schoolTimestampMs.toString())
            .build()
            .toString()
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
            )
        }
        return out
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
