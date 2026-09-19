package com.ucas.qingxin.signin.update

import org.json.JSONArray
import org.json.JSONObject

/**
 * 一次「发现新版本」的结果：只保留界面需要的那几项。
 *
 * 刻意不持有整个 Release JSON：接口返回的 `assets` / `author` / `body` 等字段
 * 里含有本功能用不到、也不该缓存的内容（体积还大）。这里只留 tag、版本、标题、
 * 说明与 Release 页地址。
 */
data class UpdateRelease(
    /** 原始 tag（如 `v1.2.0`），用于「已忽略」的匹配键：同一版本重发 tag 也不会重新提示。 */
    val tag: String,
    val version: VersionTag,
    /** Release 标题（如 `清新签到 1.2.0`）；缺失时回退成 tag。 */
    val title: String,
    /** Release 说明正文（可能很长，界面上只取首行做副标题）。 */
    val notes: String,
    /** Release 网页地址，用于「去 GitHub 更新」。 */
    val pageUrl: String,
)

/**
 * GitHub Releases 列表的解析。
 *
 * ## 为什么读「列表」而不是 `/releases/latest`
 * `latest` 只返回**最新发布**的那一条（按发布时间），而不是版本号最大的那一条。
 * 仓库历史里发布顺序与版本号顺序并不一致（`v1.1.10` 比 `v1.1.8` 晚发、
 * 但 `qingxin` 这个 tag 又插在中间），因此按发布时间取会挑错。
 * 列表接口一次给 30 条，代价可以忽略，判定标准却变成了明确的「版本号最大」。
 *
 * ## 跳过什么
 * - `draft`（草稿）；
 * - `prerelease = true`（发布者显式标了预发布）。
 *   注意仓库现有的 `v1.1.13alpha` 虽然名字带 alpha，但**没有**勾选 prerelease，
 *   因此它仍会被纳入比较 —— 这是对的：它已经面向用户发布过，
 *   用户手上的包可能正是它。版本号里的 `alpha` 后缀负责让它排在 `1.1.13` 之下。
 */
object UpdateReleases {

    /**
     * 从 Releases 列表 JSON 里挑出版本号最高的那一条；没有可用条目时返回 `null`。
     *
     * 解析失败（不是数组、不是 JSON）同样返回 `null` 而不抛异常：
     * 「检查更新」是一个后台的锦上添花功能，它不该有能力让主页报错。
     */
    fun best(rawJson: String): UpdateRelease? {
        val array = runCatching { JSONArray(rawJson) }.getOrNull() ?: return null
        var best: UpdateRelease? = null
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            if (item.optBoolean("draft", false)) continue
            if (item.optBoolean("prerelease", false)) continue
            val release = from(item) ?: continue
            val currentBest = best
            if (currentBest == null || release.version > currentBest.version) {
                best = release
            }
        }
        return best
    }

    private fun from(item: JSONObject): UpdateRelease? {
        val tag = item.optString("tag_name").trim()
        val name = item.optString("name").trim()
        // tag 可能是代号（`qingxin` / `android`），此时退到标题里找版本号。
        val version = VersionTags.parse(tag) ?: VersionTags.lastInText(name) ?: return null
        return UpdateRelease(
            tag = tag.ifBlank { "v$version" },
            version = version,
            title = name.ifBlank { tag.ifBlank { "v$version" } },
            notes = item.optString("body").orEmpty(),
            pageUrl = item.optString("html_url").trim(),
        )
    }

    /**
     * 取说明正文的首个非空行，用作横幅的副标题。
     *
     * 说明正文里常带 Markdown 结构与多段内容，直接铺在横幅里会把主页顶得很长；
     * 只取一行既能给出「这版干了什么」的线索，又把细节留给 Release 页。
     */
    fun summary(notes: String, maxChars: Int = 60): String {
        val line = notes.lineSequence()
            .map { it.trim().trimStart('#', '-', '*', ' ', '>') }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()
        return if (line.length <= maxChars) line else line.take(maxChars) + "…"
    }
}
