package com.ucas.qingxin.signin.lecture

import com.ucas.qingxin.signin.BuildConfig
import com.ucas.qingxin.signin.network.ApiException
import com.ucas.qingxin.signin.util.HostScrub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

/**
 * 课程注册表的**只读**读取器：按编号取课程名。
 *
 * ## 它读的是什么东西
 * 校内 iClass 后台有一个匿名可访问的课程目录页：给出一个 5 位编号，它返回该课程的
 * 基本页面（课程名、复合编码、成员入口等）。讲座在该目录里也是普通课程条目，
 * 因此「上一场讲座之后还有没有新讲座、叫什么」可以直接从这里读到 ——
 * 这是目前**唯一**能在讲座开出来之前就发现它的公开通道。
 *
 * ## 隐私与只读红线（改动本文件前务必先读）
 * 1. 主机、端口、页面路径、查询参数名与取值**全部不写在本文件里**，由构建时从本地
 *    `local.properties` 注入（见 `app/build.gradle.kts`）。因此本文件可以安全公开。
 * 2. **只取课程名**：响应正文里还有课程成员名单等与本人无关的内容，本类一律不解析、
 *    不返回、不落盘、不打印 —— 只留状态码与长度用于诊断。
 * 3. **只发 GET，不调任何写操作**（改人 / 删人的动作见 `tools/endpoints.local.json`
 *    的 `blockedOps`，本类里根本不存在这些路径）。
 *
 * ## 和签到的关系
 * **没有关系。** 注册表不提供 7 位 `courseSchedId` / 32 位 `timeTableId`
 * （已实测：那三个页面上这类编号命中 0），所以哨兵只能发现「有新课、叫什么」，
 * 不能拿来签到。详见 `docs/lecture-signin-research.md` §10.5、§10.11。
 */
class LectureRegistryService(
    private val client: OkHttpClient = defaultClient(),
) {

    /**
     * 读一个编号的课程名；该编号尚无名称（占位行）或不存在时返回 `null`。
     *
     * 把「无名称」与「请求失败」区分开是本方法的关键契约：
     * - 返回 `null` ⇒ 这次请求**成功了**，只是这个编号还没有名称（可安全记为「待查」）；
     * - 抛 [ApiException] ⇒ 这次请求**没成功**，不能据此认为该编号没有名称。
     *
     * 混淆二者会让哨兵把一次网络故障记成「这批编号都是空的」，
     * 于是把前沿钉在错误的位置上。调用方务必按这个契约处理。
     */
    suspend fun fetchName(cId: Int): String? = withContext(Dispatchers.IO) {
        val base = BuildConfig.REGISTRY_URL.trim()
        if (base.isEmpty()) {
            throw ApiException("REGISTRY_UNCONFIGURED", "未配置课程注册表地址")
        }
        val methodParam = BuildConfig.REGISTRY_METHOD_PARAM.trim()
        val methodValue = BuildConfig.REGISTRY_DETAIL_METHOD.trim()
        if (methodParam.isEmpty() || methodValue.isEmpty()) {
            throw ApiException("REGISTRY_UNCONFIGURED", "未配置课程注册表查询方式")
        }
        val url = base.toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter(methodParam, methodValue)
            ?.addQueryParameter(ID_PARAM, cId.toString())
            ?.build()
            ?: throw ApiException("REGISTRY_URL_INVALID", "课程注册表地址无效")

        val request = Request.Builder()
            .url(url)
            .header("Cache-Control", "no-store")
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw ApiException(
                        "REGISTRY_HTTP_${response.code}",
                        "课程注册表请求失败 (${response.code})",
                    )
                }
                val bytes = response.body?.bytes() ?: ByteArray(0)
                // 只解析课程名，正文其余部分不保留（见类注释的只读红线）。
                NAME_PATTERN.find(decode(bytes, response.header("Content-Type")))
                    ?.groupValues
                    ?.get(1)
                    ?.trim()
                    ?.ifBlank { null }
            }
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            // 连不上/超时同样归到「数据源不可用」。**必须脱敏**：
            // 这里的 `e.message` 会落盘并显示在界面上，而系统的网络异常文案里
            // 常带完整主机名（明文被拦时就是 `CLEARTEXT communication to <host> not permitted`），
            // 不处理等于把校内端点写进了用户可见的位置。
            throw ApiException(
                "REGISTRY_IO",
                HostScrub.scrub("课程注册表连接失败：${e.message ?: "网络不可用"}"),
            )
        }
    }

    companion object {
        /**
         * 课程名的取法。
         *
         * 这是一个 HTML 片段正则，**不是**端点信息（不含主机、端口、路径）——
         * 所以它可以留在源码里，而「怎么问」那部分（参数名与取值）必须外置。
         * 页面结构变化时只需要改这一处。
         */
        private val NAME_PATTERN = Regex("""class="check-title clearfloat"[^>]*>([^<]+)<""")

        /**
         * 编号的查询参数名。
         *
         * 单独留着而不是外置：`cId` 是个通用名字，不含端点信息，
         * 且它是本类唯一与「编号」相关的约定。
         */
        private const val ID_PARAM = "cId"

        /**
         * 解码响应正文。
         *
         * 该页实测为 **GBK**，因此把 GBK 作为兜底字符集（而不是常见的 UTF-8）：
         * 若页面自己声明了字符集就按声明来，否则按 GBK 解 —— 反过来做的话，
         * 一旦页面不声明字符集，中文课程名会静默变成乱码而不是报错，极难排查。
         */
        internal fun decode(bytes: ByteArray, contentType: String?): String {
            val declared = declaredCharset(bytes, contentType)
            if (declared != null) {
                runCatching { String(bytes, declared) }.onSuccess { return it }
            }
            return runCatching { String(bytes, GBK) }.getOrElse { String(bytes, Charsets.UTF_8) }
        }

        /** 从 HTTP 头或正文开头的声明里取字符集；没有声明时返回 null。 */
        private fun declaredCharset(bytes: ByteArray, contentType: String?): Charset? {
            val fromHeader = CHARSET_PARAM.find(contentType.orEmpty())?.groupValues?.get(1)
            // 只嗅探开头 1 KB：字符集声明一定在文档最前面。
            val head = String(bytes, 0, minOf(bytes.size, 1024), Charsets.ISO_8859_1)
            val fromBody = CHARSET_PARAM.find(head)?.groupValues?.get(1)
            val name = fromHeader ?: fromBody ?: return null
            return runCatching { Charset.forName(name.trim().trim('"', '\'')) }.getOrNull()
        }

        private val CHARSET_PARAM =
            Regex("""(?:charset|encoding)\s*=\s*["']?([A-Za-z0-9_\-]+)""", RegexOption.IGNORE_CASE)

        private val GBK: Charset = Charset.forName("GBK")

        /**
         * 默认客户端。
         *
         * 单次请求很轻（几 KB 的页面），但哨兵会连着发几十次，因此**连接复用**是必需的：
         * 实测该站点每次新建连接的固定开销极高（约 12.6 秒），复用后降到 0.1–0.5 秒。
         * OkHttp 的连接池默认就会复用，这里只需把超时收紧 —— 单个请求慢不该拖住整轮扫描。
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}
