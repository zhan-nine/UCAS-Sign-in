package com.ucas.qingxin.signin

import com.ucas.qingxin.signin.network.QingxinApiService
import com.ucas.qingxin.signin.qr.QrTimelineManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QrTimelineLogicTest {
    @Test
    fun refreshCapFollowsBackendConstants() {
        assertEquals(30_000L, QingxinApiService.TIME_SYNC_TTL_MS)
        assertEquals(5_000L, QingxinApiService.QR_REFRESH_CAP_MS)
        val age = 2_000L
        val refreshIn = minOf(QingxinApiService.QR_REFRESH_CAP_MS, QingxinApiService.TIME_SYNC_TTL_MS - age)
        assertEquals(5_000L, refreshIn)
        val ageNearExpiry = 27_000L
        val refreshNear = minOf(
            QingxinApiService.QR_REFRESH_CAP_MS,
            QingxinApiService.TIME_SYNC_TTL_MS - ageNearExpiry,
        )
        assertEquals(3_000L, refreshNear)
    }

    @Test
    fun buildSignQrUrlUsesCourseSchedIdAndTimestamp() {
        val api = QingxinApiService()
        val url = api.buildSignQrUrl("1234567", 1_700_000_000_000L)
        assertTrue(url.contains("courseSchedId=1234567"))
        assertTrue(url.contains("timestamp=1700000000000"))
        assertTrue(url.startsWith("https://iclass.ucas.edu.cn:8181/app/course/stu_scan_sign.action"))
    }

    @Test
    fun qrManagerStartsEmpty() {
        val mgr = QrTimelineManager(QingxinApiService())
        assertTrue(mgr.snapshot.value == null)
        assertTrue(mgr.isExpired())
    }
}
