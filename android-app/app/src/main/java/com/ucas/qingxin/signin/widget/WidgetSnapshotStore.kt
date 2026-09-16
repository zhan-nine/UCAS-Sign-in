package com.ucas.qingxin.signin.widget

import android.content.Context
import android.content.SharedPreferences

/**
 * 小部件渲染快照（纯明文缓存，读写同步且不依赖 Keystore）。
 *
 * 存在的意义：
 * 1. **冷启动出图**：多数国产 ROM 会在后台清理应用进程，宿主重新拉取小部件时
 *    应用是「冷进程」。若此时才去网络/数据库取数据，小部件会长时间空白或卡在
 *    「加载中…」。先读快照即可在毫秒级渲染上一次内容。
 * 2. **密钥库异常降级**：部分机型（恢复备份、系统升级后）Keystore 失效会导致
 *    加密存储读取抛异常。快照是明文，仍能渲染，避免整块小部件变成
 *    「无法加载小部件」。
 * 3. **离网可用**：无网络时保留上一次课表。
 *
 * 只保存渲染所需的最终文本，不含任何账号凭据。
 */
internal class WidgetSnapshotStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class Snapshot(
        val loggedIn: Boolean = false,
        val dateText: String = "",
        val coursesText: String = "",
        val focusText: String = "",
        val statusText: String = "",
        val canSign: Boolean = false,
        val signed: Boolean = false,
        val savedAt: Long = 0L,
    )

    fun read(sizeKey: String): Snapshot? {
        if (!prefs.contains(key(sizeKey, K_SAVED_AT))) return null
        return runCatching {
            Snapshot(
                loggedIn = prefs.getBoolean(key(sizeKey, K_LOGGED_IN), false),
                dateText = prefs.getString(key(sizeKey, K_DATE), "").orEmpty(),
                coursesText = prefs.getString(key(sizeKey, K_COURSES), "").orEmpty(),
                focusText = prefs.getString(key(sizeKey, K_FOCUS), "").orEmpty(),
                statusText = prefs.getString(key(sizeKey, K_STATUS), "").orEmpty(),
                canSign = prefs.getBoolean(key(sizeKey, K_CAN_SIGN), false),
                signed = prefs.getBoolean(key(sizeKey, K_SIGNED), false),
                savedAt = prefs.getLong(key(sizeKey, K_SAVED_AT), 0L),
            )
        }.getOrNull()
    }

    fun write(sizeKey: String, snapshot: Snapshot) {
        runCatching {
            prefs.edit()
                .putBoolean(key(sizeKey, K_LOGGED_IN), snapshot.loggedIn)
                .putString(key(sizeKey, K_DATE), snapshot.dateText)
                .putString(key(sizeKey, K_COURSES), snapshot.coursesText)
                .putString(key(sizeKey, K_FOCUS), snapshot.focusText)
                .putString(key(sizeKey, K_STATUS), snapshot.statusText)
                .putBoolean(key(sizeKey, K_CAN_SIGN), snapshot.canSign)
                .putBoolean(key(sizeKey, K_SIGNED), snapshot.signed)
                .putLong(key(sizeKey, K_SAVED_AT), System.currentTimeMillis())
                .apply()
        }
    }

    /** 账号切换/退出登录时清空，避免泄露上一位用户的课表。 */
    fun clearAll() {
        runCatching { prefs.edit().clear().apply() }
    }

    private fun key(sizeKey: String, field: String) = "w_${sizeKey}_$field"

    companion object {
        private const val PREFS_NAME = "widget_snapshot"
        private const val K_LOGGED_IN = "logged_in"
        private const val K_DATE = "date"
        private const val K_COURSES = "courses"
        private const val K_FOCUS = "focus"
        private const val K_STATUS = "status"
        private const val K_CAN_SIGN = "can_sign"
        private const val K_SIGNED = "signed"
        private const val K_SAVED_AT = "saved_at"

        fun of(context: Context): WidgetSnapshotStore = WidgetSnapshotStore(context)
    }
}
