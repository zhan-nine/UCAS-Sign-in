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
 * ## 为什么连「上次找到的版本」也要落盘
 * 自动检查有 12 小时节流：用户忽略一次检查后重启应用，若只把结果放在内存里，
 * 那么这一次启动**既不会重新检查、也没有结果可显示**，横幅会凭空消失，
 * 直到 12 小时后才又冒出来。落盘后，冷启动时就能立刻把上次的结论摆出来，
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
     * 且带 12 小时节流。
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

    /** 上次检查的结论：有更高版本时是那一版的描述，否则为 `null`。 */
    fun cachedAvailable(): UpdateRelease? {
        val tag = prefs.getString(K_AVAIL_TAG, "").orEmpty().trim()
        if (tag.isEmpty()) return null
        val version = VersionTags.parse(tag) ?: return null
        return UpdateRelease(
            tag = tag,
            version = version,
            title = prefs.getString(K_AVAIL_TITLE, "").orEmpty(),
            notes = prefs.getString(K_AVAIL_NOTES, "").orEmpty(),
            pageUrl = prefs.getString(K_AVAIL_URL, "").orEmpty(),
        )
    }

    /** 记录这次检查的结论；`null`（已是最新）会清掉上一次的结论。 */
    fun saveAvailable(release: UpdateRelease?) {
        val editor = prefs.edit()
        if (release == null) {
            editor.remove(K_AVAIL_TAG)
                .remove(K_AVAIL_TITLE)
                .remove(K_AVAIL_NOTES)
                .remove(K_AVAIL_URL)
        } else {
            editor.putString(K_AVAIL_TAG, release.tag)
                .putString(K_AVAIL_TITLE, release.title)
                .putString(K_AVAIL_NOTES, release.notes)
                .putString(K_AVAIL_URL, release.pageUrl)
        }
        editor.apply()
    }

    private companion object {
        const val PREFS_NAME = "update_check_v1"
        const val K_AUTO_CHECK = "auto_check"
        const val K_IGNORED_TAG = "ignored_tag"
        const val K_LAST_CHECKED_AT = "last_checked_at"
        const val K_AVAIL_TAG = "avail_tag"
        const val K_AVAIL_TITLE = "avail_title"
        const val K_AVAIL_NOTES = "avail_notes"
        const val K_AVAIL_URL = "avail_url"
    }
}
