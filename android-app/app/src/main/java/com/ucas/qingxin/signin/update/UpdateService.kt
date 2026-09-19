package com.ucas.qingxin.signin.update

import com.ucas.qingxin.signin.BuildConfig
import com.ucas.qingxin.signin.network.ApiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 更新检查：读本仓库在 GitHub 上的 Releases 列表，取版本号最高的一条。
 *
 * ## 隐私说明（本文件为什么可以公开仓库地址）
 * 本项目的隐私红线是「**校内**端点的主机 / 端口 / 路径不得入库」，
 * 因此 iClass、讲座源、注册表那些地址一律走 `BuildConfig`（本地 `local.properties` 注入）。
 * GitHub 的仓库坐标不属于这一类：
 * - 仓库本身就是公开的，地址也早已写在 README 与安装说明里；
 * - 它的主机固定且通用（`api.github.com`），与使用者所在的校园网无关，
 *   不构成任何可被利用的内部信息。
 *
 * 因此这里把 owner / repo 写成常量，而不是再加两个 `local.properties` 键 ——
 * 加了反而会让「本地配置」这份东西失去「只放校内端点」的单一语义。
 *
 * ## 请求特征
 * - 只发一次 GET，不携带任何身份（没有 token，也没有设备标识）；
 * - 请求头只说明自己是应用本身（`User-Agent`），便于万一被限流时能对上日志；
 * - 未认证的 GitHub API 限流是 60 次/小时，而调用方有 12 小时节流，
 *   正常使用远低于上限。因此**不处理**限流，遇到 403 就静默失败，
 *   由设置页的「检查失败」文案告诉用户稍后重试即可。
 */
class UpdateService(
    private val client: OkHttpClient = defaultClient(),
) {

    /**
     * 拉取 Release 列表并返回版本号最高的一条；没有任何可用条目时返回 `null`。
     *
     * 抛 [ApiException] 表示**这次请求没成功**（网络不通 / 非 2xx），
     * 与「成功但已是最新」严格区分：前者不该更新「上次检查时间」，
     * 也不该清掉上一次的结论 —— 否则一次地铁里的断网就会让已知的新版本提示消失。
     */
    suspend fun fetchLatest(): UpdateRelease? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(RELEASES_API)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "$USER_AGENT_PREFIX${BuildConfig.VERSION_NAME}")
            .header("Cache-Control", "no-store")
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw ApiException(
                        "UPDATE_HTTP_${response.code}",
                        "检查更新失败（服务返回 ${response.code}），请稍后重试",
                    )
                }
                UpdateReleases.best(response.body?.string().orEmpty())
            }
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            // 刻意不把 e.message 带出来：系统异常文案里可能出现主机的 IP / 域名，
            // 而这条消息会显示在设置页、也会出现在用户的截图里。
            // 这里只需要「失败了，可重试」这一个信息。
            throw ApiException("UPDATE_IO", "检查更新失败，请检查网络后重试")
        }
    }

    companion object {
        /**
         * 本项目的 GitHub 仓库坐标。
         *
         * 与 `README.md` 里给出的下载地址必须保持一致；改仓库名时两处一起改，
         * 否则更新提示会静默失效（请求 404 → 静默失败）。
         */
        const val REPO_OWNER = "zhan-nine"
        const val REPO_NAME = "UCAS-Sign-in"

        private const val RELEASES_API =
            "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases?per_page=30"

        /** 应用自报家门的前缀，实际发送时会拼上当前版本号。 */
        private const val USER_AGENT_PREFIX = "qingxin-signin/"

        /**
         * 默认客户端。
         *
         * 超时刻意收紧到 8 秒：这是进入主页时顺带做的一件事，
         * 让用户为它多等十几秒是不合理的；失败就失败，下次再来。
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .build()
    }
}
