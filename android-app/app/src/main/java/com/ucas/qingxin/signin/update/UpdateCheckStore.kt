package com.ucas.qingxin.signin.update

import android.content.Context
import android.content.SharedPreferences

/**
 * 更新检查的本地状态。
 *
 * ## 为什么单独一份 SharedPreferences，而不是并进 `UserSettings`
 * `UserSettings` 由 `AttendanceScheduler.saveSettings()` 持久化，而那个方法
 * **会重启守护服务并清空运行态**（见该类的注释）。「关掉自动检查更新」
 * 与自动签到的运行链路毫无关系，把它塞进去会让一次无关的开关操作
 * 顺带把整条签到链路推倒重来 —— 那种副作用极难排查。
 *
 * ## 为什么连检查结果也要落盘
 * 自动检查有节流（默认 24 小时，见 `PowerProfile.updateCheckIntervalMs`）：
 * 用户忽略一次检查后重启应用，若只把结果放在内存里，
 * 那么这一次启动**既不会重新检查、也没有结果可显示**，横幅会凭空消失，
 * 直到节流期满才又冒出来。落盘后，冷启动时就能立刻把上次的结论摆出来，
 * 是否提示由用户当时的「稍后 / 不再提示」决定。
 */
class UpdateCheckStore(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ 开关

    /**
     * 是否自动检查更新。默认**开**。
     *
     * 默认开是刻意的：这个功能的价值全在「用户没主动去查，也能知道自己落后了」，
     * 默认关等于绝大多数用户永远收不到更新提示。检查只是对 GitHub 的一次只读 GET，
     * 且带节流（默认 24 小时）。
     */
    fun isAutoCheckEnabled(): Boolean = prefs.getBoolean(K_AUTO_CHECK, true)

    fun setAutoCheckEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(K_AUTO_CHECK, enabled).apply()
    }

    // ------------------------------------------------------------------ 已忽略的版本

    /**
     * 用户点过「不再提示」的版本 tag（未忽略时为空串）。
     *
     * 存 **tag** 而不是版本号本身：将来同一个版本重新发一次（tag 不变、内容变了）
     * 不应重新打扰用户；而出现更高的版本时必须重新提示 —— 这个语义由调用方
     * 用「tag 相等」而不是「版本号相等」来判定，简单且不会误伤。
     */
    fun ignoredTag(): String = prefs.getString(K_IGNORED_TAG, "").orEmpty().trim()

    fun setIgnoredTag(tag: String) {
        prefs.edit().putString(K_IGNORED_TAG, tag.trim()).apply()
    }

    fun clearIgnoredTag() {
        prefs.edit().remove(K_IGNORED_TAG).apply()
    }

    // ------------------------------------------------------------------ 上次检查

    /** 上次检查完成的本地时刻；从未检查过为 0。用于自动检查的节流。 */
    fun lastCheckedAtMs(): Long = prefs.getLong(K_LAST_CHECKED_AT, 0L)

    fun markChecked(nowMs: Long) {
        prefs.edit().putLong(K_LAST_CHECKED_AT, nowMs).apply()
    }

    // ------------------------------------------------------------------ 检查结论

    /**
     * 最近一次**成功**检查所发现的、版本号最高的 Release；从未成功检查过时为 `null`。
     *
     * ## 「远端最新是什么」与「有没有新版本」是两件事
     * 这里存的是前者，**不判断它是否比当前安装的版本新** —— 那个判断放在
     * `MainViewModel`，只有它知道当前装的版本。分开存是因为两者用途不同：
     *
     * - 这个值用于**让用户看见检查确实跑过**：设置页常显
     *   「最新版本 1.2.1（已是最新）」，即使没有更新也有内容可看；
     * - 「是否有更新」只用于决定要不要在主页弹横幅。
     *
     * 1.2.1 之前这里只存「比当前新的那一版」，于是用户把应用升到最新之后，
     * 设置页就再没有任何可显示的内容 —— 看起来就像功能没实现。
     */
    fun cachedLatest(): UpdateRelease? {
        // 首选新键；旧键是 1.2.1 之前的写法（只存「比当前新」的那一版），
        // 继续可读以免升级后卡片先空一段时间。
        fun pick(newKey: String, oldKey: String): String =
            (prefs.getString(newKey, null) ?: prefs.getString(oldKey, null)).orEmpty().trim()

        val tag = pick(K_LATEST_TAG, K_AVAIL_TAG)
        if (tag.isEmpty()) return null
        val version = VersionTags.parse(tag) ?: return null
        return UpdateRelease(
            tag = tag,
            version = version,
            title = pick(K_LATEST_TITLE, K_AVAIL_TITLE),
            notes = pick(K_LATEST_NOTES, K_AVAIL_NOTES),
            pageUrl = pick(K_LATEST_URL, K_AVAIL_URL),
        )
    }

    /**
     * 记录本次检查发现的最新 Release；`null`（响应里没有任何可解析的条目）会清掉结论。
     *
     * 传进来的应当是**远端最高版本**，而不是「筛选后有更新的那一版」——
     * 后者会让「已是最新」时没有内容可显示（见 [cachedLatest] 的说明）。
     */
    fun saveLatest(release: UpdateRelease?) {
        val editor = prefs.edit()
        if (release == null) {
            editor.clearLatest()
        } else {
            editor.putString(K_LATEST_TAG, release.tag)
                .putString(K_LATEST_TITLE, release.title)
                .putString(K_LATEST_NOTES, release.notes)
                .putString(K_LATEST_URL, release.pageUrl)
                // 旧键一并清掉，避免两条记录长期并存后互相矛盾。
                .removeLegacy()
        }
        editor.apply()
    }

    private fun SharedPreferences.Editor.clearLatest(): SharedPreferences.Editor =
        remove(K_LATEST_TAG).remove(K_LATEST_TITLE).remove(K_LATEST_NOTES).remove(K_LATEST_URL)
            .removeLegacy()

    private fun SharedPreferences.Editor.removeLegacy(): SharedPreferences.Editor =
        remove(K_AVAIL_TAG).remove(K_AVAIL_TITLE).remove(K_AVAIL_NOTES).remove(K_AVAIL_URL)

    private companion object {
        const val PREFS_NAME = "update_check_v1"
        const val K_AUTO_CHECK = "auto_check"
        const val K_IGNORED_TAG = "ignored_tag"
        const val K_LAST_CHECKED_AT = "last_checked_at"

        /** 最近一次成功检查发现的最高版本（**无论**是否比当前安装的版本新）。 */
        const val K_LATEST_TAG = "latest_tag"
        const val K_LATEST_TITLE = "latest_title"
        const val K_LATEST_NOTES = "latest_notes"
        const val K_LATEST_URL = "latest_url"

        /** 1.2.1 之前的键：当时只存「比当前新」的那一版。仅保留读取以兼容升级。 */
        const val K_AVAIL_TAG = "avail_tag"
        const val K_AVAIL_TITLE = "avail_title"
        const val K_AVAIL_NOTES = "avail_notes"
        const val K_AVAIL_URL = "avail_url"
    }
}
