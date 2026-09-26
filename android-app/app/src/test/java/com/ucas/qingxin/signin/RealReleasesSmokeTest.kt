package com.ucas.qingxin.signin

import com.ucas.qingxin.signin.update.UpdateReleases
import com.ucas.qingxin.signin.update.VersionTags
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 对着**真实** GitHub 响应跑一遍解析与比较的冒烟测试（可选，默认跳过）。
 *
 * ## 为什么需要它
 * [UpdateReleasesTest] 用的是手工构造的 fixture，它只能证明「我以为的响应长这样」。
 * 而这个功能的失败方式是**静默**的：字段改名、tag 换一种写法、接口开始要求认证 ——
 * 任何一条都会让检测永远返回「已是最新」，界面上不会有任何异常。
 * 真机上又很难验证：能连上 GitHub 的环境里，仓库当前版本往往就比 Release 高，
 * 看到的正常结果与「解析彻底坏掉」长得一模一样。
 *
 * ## 怎么用（不会在 CI 上自动跑）
 * 先抓一份真实响应到构建目录（`build/` 不入库），再跑单测：
 *
 * ```bash
 * curl -s "https://api.github.com/repos/zhan-nine/UCAS-Sign-in/releases?per_page=30" \
 *   -o android-app/app/build/real-releases.json
 * ./gradlew :app:testDebugUnitTest
 * ```
 *
 * 没有这个文件时用例自动跳过（`assumeTrue`），因此它不会让 CI 变红，
 * 也不会因为 GitHub 不可达而失败。判定结论同时写到
 * `build/real-releases-result.txt`，方便直接查看（不依赖 Gradle 是否打印测试输出）。
 */
class RealReleasesSmokeTest {

    private val dir = File("build")
    private val fixture = File(dir, "real-releases.json")

    @Test
    fun `真实响应能被解析 且比较方向正确`() {
        assumeTrue("未提供真实响应快照，跳过（见类注释）", fixture.isFile)
        val json = fixture.readText()

        val best = UpdateReleases.best(json)
        assertNotNull("真实响应里应当至少有一条可解析的 Release", best)
        requireNotNull(best)

        // 「取版本号最大的一条」这条规则必须能从真实数据上看出来，
        // 而不是只在构造的 fixture 上成立。
        val tags = TAG_NAME.findAll(json)
            .mapNotNull { VersionTags.parse(it.groupValues[1]) }
            .toList()
        if (tags.isNotEmpty()) {
            val max = tags.max()
            assertTrue(
                "best=${best.version} 小于真实 tag 里的最大版本 $max",
                best.version >= max,
            )
        }

        val current = VersionTags.parse(CURRENT_VERSION)
        val verdict = when {
            current == null -> "当前版本号解析失败（$CURRENT_VERSION）"
            best.version > current -> "会提示更新：${best.version} > $CURRENT_VERSION"
            else -> "不提示（已是最新）：${best.version} <= $CURRENT_VERSION"
        }
        File(dir, "real-releases-result.txt").writeText(
            buildString {
                appendLine("best.tag   = ${best.tag}")
                appendLine("best.ver   = ${best.version}")
                appendLine("best.title = ${best.title}")
                appendLine("pageUrl    = ${best.pageUrl}")
                appendLine("verdict    = $verdict")
            },
        )
    }

    private companion object {
        /**
         * 当前版本号，直接从 `app/build.gradle.kts` 的 `versionName` 读出来。
         *
         * 这里曾经硬编码成 `"1.2.0"`，而应用早已是 1.2.1 —— 于是这个用例一直报告
         * 「会提示更新：1.2.1 > 1.2.0」，一个真实使用中根本不成立的结论。
         * 这种「测试自己骗自己」比没有测试更糟：它让一个已经不再提示更新的功能
         * 看起来一切正常。版本号是会变的，读出来才不会再次脱节。
         */
        val CURRENT_VERSION: String by lazy {
            val candidates = listOf(File("build.gradle.kts"), File("app/build.gradle.kts"))
            val text = candidates.firstOrNull { it.isFile }?.readText().orEmpty()
            VERSION_NAME.find(text)?.groupValues?.get(1) ?: "(未能从 build.gradle.kts 读出)"
        }

        val VERSION_NAME = Regex("versionName\\s*=\\s*\"([^\"]+)\"")

        val TAG_NAME = Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"")
    }
}
