package com.ucas.qingxin.signin.update

/**
 * 版本号（只实现本项目需要的那一部分语义）。
 *
 * ## 为什么需要它，而不是直接比较字符串
 * 字符串比较会把 `1.10.0` 判成小于 `1.9.9`（`'1' < '9'`），从而**静默漏掉**新版本；
 * 而漏掉新版本是这个功能唯一不能犯的错误 —— 用户不会收到任何提示，
 * 也不会有人发现。因此这里把版本号拆成数字段来比。
 *
 * ## 兼容的写法
 * 仓库历史上出现过 `v1.1.8`、`v1.1.10`、`v1.1.13alpha`、`qingxin`、`android`
 * 这几种 tag。因此解析要能容忍：
 * - 可选的前缀 `v` / `V`；
 * - 少于三段（`1.2` 等价于 `1.2.0`）；
 * - 后缀（`1.1.13alpha`、`1.2.0-rc1`、`1.2.0_beta`）；
 * - 完全解析不出时返回 `null`（`qingxin` / `android` 这类 tag 会被跳过，
 *   由 [UpdateReleases] 回退去读 Release 的标题）。
 */
data class VersionTag(
    val major: Int,
    val minor: Int,
    val patch: Int,
    /** 预发布后缀，如 `alpha` / `rc1`；正式版为空串。比较时统一转小写。 */
    val suffix: String = "",
) : Comparable<VersionTag> {

    /** 是否为正式版（无预发布后缀）。 */
    val isStable: Boolean get() = suffix.isEmpty()

    /**
     * 排序规则：先比三段数字；数字相同时，**有后缀的排在同号正式版之下**。
     *
     * `1.1.13alpha < 1.1.13 < 1.2.0` 这条顺序是刻意的：`v1.1.13alpha` 是仓库里
     * 已有的一条 Release，而它是 1.1.13 的预发布；若把带后缀的排在正式版之上，
     * 一个 `1.2.0alpha` 就会盖过 `1.2.0`，用户会被反复提示去装一个更旧的包。
     *
     * 两个后缀之间按字典序比（`alpha < beta < rc`）。这是一个刻意的简化：
     * 它无法把 `rc10` 排在 `rc2` 之后，但本项目的后缀只有 `alpha` 一种，
     * 为它写一套「字母前缀 + 数字」的解析属于过度设计。
     */
    override fun compareTo(other: VersionTag): Int {
        val numeric = compareValuesBy(
            this,
            other,
            { it.major },
            { it.minor },
            { it.patch },
        )
        if (numeric != 0) return numeric
        return when {
            suffix == other.suffix -> 0
            suffix.isEmpty() -> 1
            other.suffix.isEmpty() -> -1
            else -> suffix.compareTo(other.suffix)
        }
    }

    /** 面向用户的写法（不带 `v` 前缀）：`1.2.0`、`1.1.13alpha`。 */
    override fun toString(): String = buildString {
        append(major).append('.').append(minor).append('.').append(patch)
        if (suffix.isNotEmpty()) append(suffix)
    }
}

/** [VersionTag] 的解析器。与数据类分开放，便于单测直接引用而不构造实例。 */
object VersionTags {

    /**
     * 一个版本号片段的形态。
     *
     * 三处细节都不能省：
     * - `(?<![0-9A-Za-z])`：左边必须是「非字母数字」，否则 `M1167` 这类期次号、
     *   `x1.2.3` 这类编号会被从中间截出一段版本号来；
     * - `[-_.]?`：允许 `1.2.0-rc1` / `1.2.0_beta` 这类分隔写法；
     * - 后缀只认 `[A-Za-z][A-Za-z0-9]*`：保证 `1.1.13 发布` 里的空格不被吃进后缀。
     */
    private val TOKEN = Regex(
        """(?<![0-9A-Za-z])[vV]?(\d{1,6})(?:\.(\d{1,6}))?(?:\.(\d{1,6}))?(?:[-_.]?([A-Za-z][A-Za-z0-9]*))?""",
    )

    /**
     * 把一个**整串**版本号解析成 [VersionTag]；解析不出返回 `null`。
     *
     * 用于 `tag_name`：它要么是 `v1.2.0`，要么是 `qingxin` 这种纯粹的代号。
     * 整串匹配可以避免把 `v1.2.0-hotfix-2` 里的某一段当成版本号。
     */
    fun parse(raw: String?): VersionTag? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val match = TOKEN.matchEntire(text) ?: return null
        return toTag(match)
    }

    /**
     * 从一段文本里取**最后一个**版本号片段。
     *
     * 用于 Release 标题（如 `清新签到1.1.13`）：tag 是 `qingxin` 这种代号时，
     * 版本号只出现在标题里。取最后一个而不是第一个，是因为标题的固定前缀
     * （应用名）排在前面，版本号总在末尾。
     */
    fun lastInText(raw: String?): VersionTag? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val match = TOKEN.findAll(text).lastOrNull() ?: return null
        return toTag(match)
    }

    private fun toTag(match: MatchResult): VersionTag {
        fun group(index: Int): Int = match.groupValues.getOrNull(index)?.toIntOrNull() ?: 0
        return VersionTag(
            major = group(1),
            minor = group(2),
            patch = group(3),
            suffix = match.groupValues.getOrNull(4)?.lowercase().orEmpty(),
        )
    }
}
