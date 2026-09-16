package com.ucas.qingxin.signin.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.ucas.qingxin.signin.data.SchoolSession
import org.json.JSONObject

/**
 * Persists session preferentially; password only when re-auth is required by backend.
 *
 * 说明（跨厂商健壮性）：
 * Android Keystore 在部分 ROM 上并不可靠——系统升级、备份恢复、双开/分身、
 * 开发者选项里重置密钥后，`EncryptedSharedPreferences` 的读写都会抛异常。
 * 旧实现里该异常会直接冒泡到 `Application.onCreate()`，导致**整个进程启动失败**：
 * 桌面小部件被宿主拉起时看到的就是「无法加载小部件」。
 *
 * 现在的策略是优雅降级：
 * - 加密存储可用 → 行为与原来完全一致；
 * - 不可用 → 会话写入明文备份，**但绝不落盘密码**（宁可让用户重新输入）。
 */
class SecureCredentialStore(context: Context) {

    private val appContext = context.applicationContext

    /** 加密存储；创建失败时为 null（表示当前设备 Keystore 不可用）。 */
    private val encrypted: SharedPreferences? = runCatching {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            "qingxin_secure_store",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrNull()

    private val plain: SharedPreferences by lazy {
        appContext.getSharedPreferences("qingxin_credential_plain", Context.MODE_PRIVATE)
    }

    private fun readPrefs(): SharedPreferences = encrypted ?: plain

    private fun writePrefs(): SharedPreferences? = encrypted ?: plain

    /** 加密存储是否可用（设置页 / 诊断用）。 */
    val isEncrypted: Boolean get() = encrypted != null

    fun saveSession(session: SchoolSession) {
        val prefs = writePrefs() ?: return
        runCatching {
            prefs.edit()
                .putString(KEY_USER_ID, session.userId)
                .putString(KEY_SESSION_ID, session.sessionId)
                .putString(KEY_STUDENT_NO, session.studentNo)
                .apply()
        }
    }

    fun getSession(): SchoolSession? = readSession(readPrefs())
        ?: runCatching { if (encrypted != null) readSession(plain) else null }.getOrNull()

    private fun readSession(prefs: SharedPreferences): SchoolSession? = runCatching {
        val userId = prefs.getString(KEY_USER_ID, null) ?: return null
        val sessionId = prefs.getString(KEY_SESSION_ID, null) ?: return null
        val studentNo = prefs.getString(KEY_STUDENT_NO, null) ?: return null
        if (userId.isBlank() || sessionId.isBlank() || studentNo.isBlank()) return null
        SchoolSession(userId, sessionId, studentNo)
    }.getOrNull()

    fun saveLoginId(studentNo: String) {
        val prefs = writePrefs() ?: return
        runCatching { prefs.edit().putString(KEY_LOGIN_ID, studentNo.trim()).apply() }
    }

    fun getLoginId(): String? = runCatching {
        readPrefs().getString(KEY_LOGIN_ID, null)
            ?: readPrefs().getString(KEY_LEGACY_EMAIL, null)
    }.getOrNull()

    /**
     * 密码只写入加密存储；Keystore 不可用时直接跳过，
     * 以免明文落盘。届时用户需要重新输入密码，功能仍可用。
     */
    fun savePasswordForReauth(password: String) {
        val prefs = encrypted ?: return
        runCatching { prefs.edit().putString(KEY_PASSWORD, password).apply() }
    }

    fun getPasswordForReauth(): String? = runCatching {
        encrypted?.getString(KEY_PASSWORD, null)
    }.getOrNull()

    fun clearAll() {
        runCatching { encrypted?.edit()?.clear()?.commit() }
        runCatching { plain.edit().clear().commit() }
    }

    fun exportRedactedDebug(): String {
        val o = JSONObject()
        o.put("encrypted", isEncrypted)
        o.put("hasSession", getSession() != null)
        o.put("hasLoginId", !getLoginId().isNullOrBlank())
        o.put("hasPassword", !getPasswordForReauth().isNullOrBlank())
        return o.toString()
    }

    companion object {
        private const val KEY_USER_ID = "user_id"
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_STUDENT_NO = "student_no"
        private const val KEY_LOGIN_ID = "login_student_no"
        private const val KEY_LEGACY_EMAIL = "email"
        private const val KEY_PASSWORD = "password_reauth"
    }
}
