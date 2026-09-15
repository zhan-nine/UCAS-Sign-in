package com.ucas.qingxin.signin.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.ucas.qingxin.signin.data.SchoolSession
import org.json.JSONObject

/**
 * Persists session preferentially; password only when re-auth is required by backend.
 * Login identity is student number (学号), not email.
 */
class SecureCredentialStore(context: Context) {
    private val appContext = context.applicationContext

    private val prefs: SharedPreferences by lazy {
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
    }

    fun saveSession(session: SchoolSession) {
        prefs.edit()
            .putString(KEY_USER_ID, session.userId)
            .putString(KEY_SESSION_ID, session.sessionId)
            .putString(KEY_STUDENT_NO, session.studentNo)
            .apply()
    }

    fun getSession(): SchoolSession? {
        val userId = prefs.getString(KEY_USER_ID, null) ?: return null
        val sessionId = prefs.getString(KEY_SESSION_ID, null) ?: return null
        val studentNo = prefs.getString(KEY_STUDENT_NO, null) ?: return null
        if (userId.isBlank() || sessionId.isBlank() || studentNo.isBlank()) return null
        return SchoolSession(userId, sessionId, studentNo)
    }

    fun saveLoginId(studentNo: String) {
        prefs.edit().putString(KEY_LOGIN_ID, studentNo.trim()).apply()
    }

    fun getLoginId(): String? =
        prefs.getString(KEY_LOGIN_ID, null)
            ?: prefs.getString(KEY_LEGACY_EMAIL, null)

    fun savePasswordForReauth(password: String) {
        prefs.edit().putString(KEY_PASSWORD, password).apply()
    }

    fun getPasswordForReauth(): String? = prefs.getString(KEY_PASSWORD, null)

    fun clearAll() {
        prefs.edit().clear().commit()
    }

    fun exportRedactedDebug(): String {
        val o = JSONObject()
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
