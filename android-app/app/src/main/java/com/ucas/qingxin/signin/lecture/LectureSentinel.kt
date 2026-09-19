package com.ucas.qingxin.signin.lecture

import android.content.Context
import com.ucas.qingxin.signin.attendance.AttendanceNotifier
import com.ucas.qingxin.signin.attendance.LectureNoticeWatcher
import com.ucas.qingxin.signin.network.ApiException
import com.ucas.qingxin.signin.util.HostScrub
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 一轮哨兵扫描的结果（给界面用）。 */
data class SentinelOutcome(
    val ok: Boolean,
    val message: String,
    /** 本轮新发现的讲座名称（已按「此前没报过」过滤）。 */
    val newSessions: List<String> = emptyList(),
)

/**
 * 「找下一场讲座」的哨兵。
 *
 * ## 它解决什么问题
 * 人文学院网站的预告要等老师发了才有，而**课程注册表领先它数月**
 * （实测：注册表已到 2026-09-16 的场次，公开快照还停在 2026-05-20）。
 * 因此哨兵的价值是「**比任何公开源更早**给出讲座名称」——代价是注册表
 * **不含日期与场地**，所以它只能回答「有没有新课、叫什么」，不能回答「几点」。
 *
 * ## 扫描口径：以本地「最后一场讲座」为基点向前扫
 * 注册表编号**只增不改**，因此下一场讲座的编号必然大于上一场。据此：
 * 1. 本地存下**最后一场讲座的编号**（基点）与**已见过的最大有名称编号**（前沿）；
 * 2. 每轮从「前沿 + 1」向前逐号扫，最多 [SentinelScan.EMPTY_LIMIT] 个连续无名即止；
 * 3. 同时**复查待查集** —— 这是必需的一步，不是冗余：
 *    实测条目会「先占号、后填名」（锚点页刚建好时正文 1.6 KB、无标题，
 *    之后才补成 8.6 KB），只往前扫会把这类晚填名的场次整段越过。
 *
 * 于是稳态下每轮只有几十次**只读**请求，而不是全库枚举的数千次。
 *
 * ## 只报「带期次号」的场次
 * 注册表里同时躺着讲座**场次**（`明德讲堂M1167雁栖湖会场：…`）与开学批量导入的
 * **课程容器**（`全球地缘与中国国情专题讲座` 同类同名成串 4–16 条）。
 * 判据与依据见 [LectureNaming]：只有带期次号的才算「一场讲座」。
 *
 * ## 隐私与只读
 * 端点在构建时注入（见 [LectureRegistryService]），且全程只有 GET、只读课程名。
 * 本对象**不参与任何签到流程** —— 注册表不提供可签到标识（已实测）。
 */
object LectureSentinel {

    /** 开关键存这里。与通知开关分开存：两者是独立能力，互相不该影响。 */
    private const val PREFS_NAME = "lecture_sentinel"
    private const val K_ENABLED = "enabled"

    private val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

    /** 因为懒加载：注册表服务只在真正扫描时才需要，不扫描的用户不该付出构造开销。 */
    private val service: LectureRegistryService by lazy { LectureRegistryService() }

    // ------------------------------------------------------------------ 开关

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(K_ENABLED, false)

    /**
     * 打开 / 关闭哨兵。
     *
     * **默认关闭**：它会周期性访问校内后台，虽然只是几十次只读请求，
     * 也应当由用户明确同意后才开始。开启后与「新讲座通知」共用同一个周期任务
     * （见 [LectureNoticeWatcher.syncPeriodicWork]），不会叠加唤醒。
     */
    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(K_ENABLED, enabled).apply()
        LectureNoticeWatcher.syncPeriodicWork(context)
    }

    /** 是否具备运行条件（端点在构建时已注入 + 基点编号已配置）。 */
    fun isAvailable(): Boolean {
        val url = runCatching { com.ucas.qingxin.signin.BuildConfig.REGISTRY_URL.trim() }.getOrDefault("")
        val anchor = runCatching {
            com.ucas.qingxin.signin.BuildConfig.SENTINEL_ANCHOR_CID.trim()
        }.getOrDefault("")
        return url.isNotEmpty() && anchor.isNotEmpty()
    }

    // ------------------------------------------------------------------ 界面信息

    fun lastRunAt(context: Context): String = LectureSentinelStore(context).lastRunAt()

    fun lastSummary(context: Context): String = LectureSentinelStore(context).lastSummary()

    /** 上一次发现的讲座名称（最多几条，供界面展示）。 */
    fun lastFound(context: Context): List<String> = LectureSentinelStore(context).lastFound()

    // ------------------------------------------------------------------ 执行

    /**
     * 跑一轮扫描。
     *
     * **永不抛异常**：调用方是后台任务与界面按钮，两者都不该因为一次网络故障而崩。
     * 失败会如实写进 [SentinelOutcome.message] 并存档，供界面显示。
     */
    suspend fun run(context: Context): SentinelOutcome {
        val appContext = context.applicationContext
        if (!isEnabled(appContext)) return SentinelOutcome(false, "哨兵未启用")
        if (!isAvailable()) {
            return SentinelOutcome(false, "未配置课程注册表地址或基点编号，无法扫描")
        }
        val store = LectureSentinelStore(appContext)
        val anchor = store.anchor()
        if (anchor <= 0) return SentinelOutcome(false, "基点编号无效，无法扫描")

        val result = try {
            SentinelScan.run(
                anchor = anchor,
                frontier = store.frontier(),
                pending = store.pending(),
            ) { cId -> service.fetchName(cId) }
        } catch (e: ApiException) {
            val message = scrub(e.message ?: e.code)
            store.recordRun(stamp(), "扫描失败：$message", emptyList())
            return SentinelOutcome(false, message)
        } catch (e: Exception) {
            // 连接类异常已被服务层包成 ApiException，这里兜住其余意外（如解析异常）。
            val message = scrub(e.message ?: "未知错误")
            store.recordRun(stamp(), "扫描失败：$message", emptyList())
            return SentinelOutcome(false, message)
        }

        // 「本轮新增」= 本轮读到名称、且它的期次号此前没报过。
        val seen = store.seenSessions().toHashSet()
        val foundNow = result.lectureSessions.entries
            .mapNotNull { (cId, name) -> LectureNaming.sessionLabel(name)?.let { it to (cId to name) } }
        val fresh = foundNow.filterNot { it.first in seen }

        // 状态写回（必须在算完 fresh 之后、也不要在任何提前 return 之前漏掉）。
        store.setFrontier(result.highestNamed)
        store.setPending(result.stillNameless)
        store.setSeenSessions(store.seenSessions() + foundNow.map { it.first })
        // 基点推进到本轮遇到的最后一场讲座：它是「已知最后一场」，也是下轮的起始参照。
        result.lectureSessions.keys.maxOrNull()?.let { store.setAnchor(it) }

        val firstRun = !store.initialized()
        // 首次只记录不推送：否则一装上就会把注册表里的历史讲座全部推一遍。
        val toNotify = if (firstRun) emptyList() else fresh.map { it.second.second }
        store.setInitialized(true)

        val summary = buildString {
            append("扫了 ${result.requests} 个编号")
            append("；新增 ${toNotify.size} 场")
            if (result.highestNamed > 0) append("；前沿 ${result.highestNamed}")
        }
        store.recordRun(stamp(), summary, toNotify.ifEmpty { fresh.map { it.second.second } })

        if (toNotify.isNotEmpty()) {
            runCatching { AttendanceNotifier(appContext).notifySentinelLectures(toNotify) }
        }
        return SentinelOutcome(
            ok = true,
            message = if (firstRun) {
                "已建立基线（$summary）；此后只报新增场次"
            } else {
                summary
            },
            newSessions = toNotify,
        )
    }

    // ------------------------------------------------------------------ 内部

    private fun stamp(): String = LocalDateTime.now(ZONE).format(STAMP)

    /**
     * 去掉可能进入文案的主机名/端口。
     *
     * 网络异常的消息里常带完整地址，而这里的文案会落盘并显示在界面上 ——
     * 那等于把校内端点写进了用户可见的位置（也等于写进了 bug 报告与截图）。
     * 统一走 `HostScrub`：它同时处理 `scheme://authority` 与**裸主机名**
     * 两种形态（后者正是明文流量被拦时报出来的那种）。
     */
    private fun scrub(message: String): String = HostScrub.scrub(message)

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
