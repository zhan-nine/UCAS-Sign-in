package com.ucas.qingxin.signin.auth

import com.ucas.qingxin.signin.data.SchoolSession
import com.ucas.qingxin.signin.network.ApiException
import com.ucas.qingxin.signin.network.QingxinApiService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AuthRepository(
    private val api: QingxinApiService,
    private val store: SecureCredentialStore,
) {
    private val mutex = Mutex()
    private val _session = MutableStateFlow(store.getSession())
    val session: StateFlow<SchoolSession?> = _session.asStateFlow()

    fun isLoggedIn(): Boolean = _session.value != null

    /** Login identity is 学号; API field name remains `phone` as confirmed in original app. */
    suspend fun login(studentNo: String, password: String): SchoolSession = mutex.withLock {
        val id = studentNo.trim()
        require(id.isNotEmpty() && password.isNotEmpty()) { "请输入学号/邮箱和密码" }
        require(password.length <= 80) { "请输入有效的学校账号密码" }
        val session = api.login(id, password)
        store.saveLoginId(id)
        store.savePasswordForReauth(password)
        store.saveSession(session)
        _session.value = session
        session
    }

    suspend fun restore(): SchoolSession? = mutex.withLock {
        val existing = store.getSession()
        val loginId = store.getLoginId()
        if (existing != null) {
            try {
                val resumed = api.resumeSession(existing.studentNo)
                store.saveSession(resumed)
                _session.value = resumed
                return resumed
            } catch (_: Exception) {
            }
            if (!loginId.isNullOrBlank()) {
                try {
                    val resumed = api.resumeSession(loginId)
                    store.saveSession(resumed)
                    _session.value = resumed
                    return resumed
                } catch (_: Exception) {
                }
            }
        }
        val password = store.getPasswordForReauth()
        if (!loginId.isNullOrBlank() && !password.isNullOrBlank()) {
            return try {
                val session = api.login(loginId, password)
                store.saveSession(session)
                _session.value = session
                session
            } catch (e: ApiException) {
                if (e.code == "LOGIN_REJECTED") {
                    store.clearAll()
                    _session.value = null
                }
                null
            } catch (_: Exception) {
                null
            }
        }
        _session.value = existing
        existing
    }

    suspend fun requireSession(): SchoolSession {
        return session.value ?: restore()
            ?: throw ApiException("LOGIN_EXPIRED", "登录已失效，请重新登录")
    }

    suspend fun refreshSession(): SchoolSession = mutex.withLock {
        val loginId = store.getLoginId()
        val password = store.getPasswordForReauth()
        val current = store.getSession()
        if (current != null) {
            try {
                val resumed = api.resumeSession(current.studentNo)
                store.saveSession(resumed)
                _session.value = resumed
                return resumed
            } catch (_: Exception) {
            }
        }
        if (!loginId.isNullOrBlank() && !password.isNullOrBlank()) {
            val session = api.login(loginId, password)
            store.saveSession(session)
            _session.value = session
            return session
        }
        throw ApiException("LOGIN_EXPIRED", "登录已失效，请重新登录")
    }

    fun logout() {
        store.clearAll()
        _session.value = null
    }
}
