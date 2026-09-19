package com.ucas.qingxin.signin

import com.ucas.qingxin.signin.lecture.LectureEventKind
import com.ucas.qingxin.signin.lecture.LecturePortal
import com.ucas.qingxin.signin.lecture.LectureSessionBootstrap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 对齐 UCAS-Desktop `lecture-portal.ts` / `establishLectureSession` 的离线用例。
 */
class LecturePortalTest {

    private val sepLogin = "https://sso.example.edu/?loginFrom=/appStore"
    private val xkgoMain = "https://course.example.edu:3000/courseManage/main"
    private val xkctsBase = "https://lecture.example.edu:9999"
    private val humanity = "$xkctsBase/subject/humanityLecture"
    private val science = "$xkctsBase/subject/lecture"
    private val hosts = LecturePortal.allowedHosts(sepLogin, xkgoMain, xkctsBase)

    @Test
    fun `工作台与选课主页表面判定`() {
        assertEquals(
            LecturePortal.Surface.SEP_LOGIN,
            LecturePortal.surfaceOf("https://sso.example.edu/", humanity, sepLogin, xkgoMain, xkctsBase),
        )
        assertEquals(
            LecturePortal.Surface.SEP_WORKBENCH,
            LecturePortal.surfaceOf("https://sso.example.edu/sepCard/card", humanity, sepLogin, xkgoMain, xkctsBase),
        )
        assertEquals(
            LecturePortal.Surface.SEP_WORKBENCH,
            LecturePortal.surfaceOf("https://sso.example.edu/appStore", humanity, sepLogin, xkgoMain, xkctsBase),
        )
        assertEquals(
            LecturePortal.Surface.XKGO_MAIN,
            LecturePortal.surfaceOf(xkgoMain, humanity, sepLogin, xkgoMain, xkctsBase),
        )
        assertEquals(
            LecturePortal.Surface.SCHEDULE,
            LecturePortal.surfaceOf(humanity, humanity, sepLogin, xkgoMain, xkctsBase),
        )
        assertEquals(
            LecturePortal.Surface.XKCTS_REDIRECT,
            LecturePortal.surfaceOf("$xkctsBase/redirect", humanity, sepLogin, xkgoMain, xkctsBase),
        )
    }

    @Test
    fun `门户href解析拒绝越权并升级http`() {
        val https = LecturePortal.resolvePortalHref(
            "/portal/site/226/demo",
            "https://sso.example.edu/sepCard/card",
            hosts,
        )
        assertEquals("https://sso.example.edu/portal/site/226/demo", https)

        val upgraded = LecturePortal.resolvePortalHref(
            "http://sso.example.edu/portal/site/226/demo",
            xkgoMain,
            hosts,
        )
        assertEquals("https://sso.example.edu/portal/site/226/demo", upgraded)

        assertNull(LecturePortal.resolvePortalHref("javascript:alert(1)", sepLogin, hosts))
        assertNull(LecturePortal.resolvePortalHref("https://evil.example/a", sepLogin, hosts))
        assertNull(LecturePortal.resolvePortalHref("#", sepLogin, hosts))
    }

    @Test
    fun `脚本返回值解析`() {
        assertEquals(
            "/portal/course",
            LecturePortal.parseHrefResult("{\"href\":\"/portal/course\"}"),
        )
        assertEquals("", LecturePortal.parseHrefResult("{\"href\":\"\"}"))
        assertTrue(LecturePortal.parseOkResult("{\"ok\":true}"))
        assertFalse(LecturePortal.parseOkResult("{\"ok\":false}"))
    }

    @Test
    fun `状态机禁止直开栏目而从工作台找门户`() {
        val boot = LectureSessionBootstrap()
        val endpoints = LectureSessionBootstrap.Endpoints(
            sepLoginUrl = sepLogin,
            xkgoMainUrl = xkgoMain,
            xkctsBase = xkctsBase,
            humanityUrl = humanity,
            scienceUrl = science,
        )
        // 已在工作台：应去找门户，而不是 Navigate(humanity)
        val step = boot.begin(LectureEventKind.HUMANITY, "https://sso.example.edu/sepCard/card", endpoints)
        assertTrue(step is LectureSessionBootstrap.Step.Evaluate)
        val eval = step as LectureSessionBootstrap.Step.Evaluate
        assertEquals(LectureSessionBootstrap.Expect.PORTAL_HREF, eval.expect)

        // 未登录：先去 SEP
        val boot2 = LectureSessionBootstrap()
        val login = boot2.begin(LectureEventKind.HUMANITY, "about:blank", endpoints)
        assertTrue(login is LectureSessionBootstrap.Step.Navigate)
        assertEquals(sepLogin, (login as LectureSessionBootstrap.Step.Navigate).url)
    }

    @Test
    fun `桥接找到后进入确认表头再读表`() {
        val boot = LectureSessionBootstrap()
        val endpoints = LectureSessionBootstrap.Endpoints(
            sepLoginUrl = sepLogin,
            xkgoMainUrl = xkgoMain,
            xkctsBase = xkctsBase,
            humanityUrl = humanity,
            scienceUrl = science,
        )
        boot.begin(LectureEventKind.HUMANITY, xkgoMain, endpoints)
        val nav = boot.onScriptResult(
            LectureSessionBootstrap.Expect.BRIDGE_HREF,
            "{\"href\":\"https://sso.example.edu/portal/humanity/abc\"}",
            xkgoMain,
            endpoints,
        )
        assertTrue(nav is LectureSessionBootstrap.Step.Navigate)

        // 假装已落到栏目页且表头齐全
        boot.onPageFinished(humanity, endpoints)
        val read = boot.onScriptResult(
            LectureSessionBootstrap.Expect.TABLE_OK,
            "{\"ok\":true}",
            humanity,
            endpoints,
        )
        assertTrue(read is LectureSessionBootstrap.Step.ReadSchedule)
        assertEquals(LectureEventKind.HUMANITY, (read as LectureSessionBootstrap.Step.ReadSchedule).kind)
    }
}
