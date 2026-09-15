package com.ucas.qingxin.signin.qr

import com.ucas.qingxin.signin.data.QrSnapshot
import com.ucas.qingxin.signin.network.ApiException
import com.ucas.qingxin.signin.network.QingxinApiService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * QR timeline follows original RememberSignIn / iClass backend:
 * 1) POST common/get_timestamp.do?id=0 for server time
 * 2) NTP-style offset using RTT/2
 * 3) Sample TTL = 30_000ms (TIME_SYNC_TTL_MS)
 * 4) QR refresh at min(5_000ms, remaining sync TTL)
 *
 * Server Timeline > Local Assumption. Do not hardcode an independent 5s clock.
 */
class QrTimelineManager(
    private val api: QingxinApiService,
) {
    data class ClockReading(
        val schoolNowMs: Long,
        val ageMs: Long,
        val rttMs: Long,
        val sampledAtLocalMs: Long,
    )

    private val mutex = Mutex()
    private var sampleSchoolMs: Long? = null
    private var sampleLocalMs: Long? = null
    private var sampleRttMs: Long = 0

    private val _snapshot = MutableStateFlow<QrSnapshot?>(null)
    val snapshot: StateFlow<QrSnapshot?> = _snapshot.asStateFlow()

    suspend fun syncClock(force: Boolean = false): ClockReading = mutex.withLock {
        val now = System.currentTimeMillis()
        val existingSchool = sampleSchoolMs
        val existingLocal = sampleLocalMs
        if (!force && existingSchool != null && existingLocal != null) {
            val age = now - existingLocal
            if (age in 0 until QingxinApiService.TIME_SYNC_TTL_MS) {
                return ClockReading(
                    schoolNowMs = existingSchool + age,
                    ageMs = age,
                    rttMs = sampleRttMs,
                    sampledAtLocalMs = existingLocal,
                )
            }
        }
        val t0 = System.currentTimeMillis()
        val serverTs = api.getServerTimestampMs()
        val t1 = System.currentTimeMillis()
        if (t1 < t0) throw ApiException("TIME_SYNC_EXPIRED", "学校校时已过期，请重试")
        val rtt = t1 - t0
        val schoolAtReceive = serverTs + rtt / 2
        sampleSchoolMs = schoolAtReceive
        sampleLocalMs = t1
        sampleRttMs = rtt
        ClockReading(
            schoolNowMs = schoolAtReceive,
            ageMs = 0,
            rttMs = rtt,
            sampledAtLocalMs = t1,
        )
    }

    fun currentSchoolTimeOrNull(): Long? {
        val school = sampleSchoolMs ?: return null
        val local = sampleLocalMs ?: return null
        val age = System.currentTimeMillis() - local
        if (age < 0 || age >= QingxinApiService.TIME_SYNC_TTL_MS) return null
        return school + age
    }

    /**
     * Build QR for a course id/uuid using aligned school timestamp.
     * expiresAtLocalMs = nowLocal + min(5000, 30000 - age) — confirmed from original td1.java.
     */
    suspend fun refreshQr(courseIdOrUuid: String): QrSnapshot {
        val reading = syncClock(force = false)
        val localNow = System.currentTimeMillis()
        val age = reading.ageMs
        val ttlRemain = QingxinApiService.TIME_SYNC_TTL_MS - age
        if (ttlRemain <= 0) {
            throw ApiException("TIME_SYNC_EXPIRED", "学校校时已过期，请重试")
        }
        val refreshIn = minOf(QingxinApiService.QR_REFRESH_CAP_MS, ttlRemain)
        val expiresAt = localNow + refreshIn
        val url = api.buildSignQrUrl(courseIdOrUuid, reading.schoolNowMs)
        val remaining = ((expiresAt - System.currentTimeMillis()).coerceAtLeast(0)).toDouble() / 1000.0
        val snap = QrSnapshot(
            url = url,
            schoolTimestampMs = reading.schoolNowMs,
            expiresAtLocalMs = expiresAt,
            validityDurationMs = refreshIn,
            remainingSeconds = remaining,
        )
        _snapshot.value = snap
        return snap
    }

    fun remainingSeconds(nowLocalMs: Long = System.currentTimeMillis()): Double {
        val snap = _snapshot.value ?: return 0.0
        return ((snap.expiresAtLocalMs - nowLocalMs).coerceAtLeast(0)).toDouble() / 1000.0
    }

    fun isExpired(nowLocalMs: Long = System.currentTimeMillis()): Boolean {
        val snap = _snapshot.value ?: return true
        return nowLocalMs >= snap.expiresAtLocalMs
    }

    fun clear() {
        _snapshot.value = null
        sampleSchoolMs = null
        sampleLocalMs = null
    }
}
