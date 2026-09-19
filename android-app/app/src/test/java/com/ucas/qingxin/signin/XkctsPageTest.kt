package com.ucas.qingxin.signin

import com.ucas.qingxin.signin.lecture.LectureEventKind
import com.ucas.qingxin.signin.lecture.XkctsPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「WebView 现在停在哪一页」的判定用例。
 *
 * ## 为什么这个纯函数值得单独一组用例
 * 它是内嵌登录流程里**唯一能被离线验证**的一环，而其余部分（真实登录、
 * 验证码、邮箱验证）只能靠真机手点。判错的两种表现都很隐蔽：
 * - 把登录页误判成栏目页 ⇒ 自动读取读到空表，用户看到「没有读到讲座时间表」，
 *   却完全不知道原因是自己还没登录；
 * - 把栏目页误判成登录页 ⇒ 明明登录成功了，读取按钮却一直不可用。
 *
 * 因此这里把「登录流程可能落地到的各种地址形态」全部走一遍。
 * 用例里的主机名与**端口都是编的**：两者都在本项目的隐私红线内
 * （见 `docs/lecture-signin-research.md`），不能出现在公开仓库里 ——
 * 而这组用例本来也不需要真实地址，它验证的是**路径比较规则**。
 */
class XkctsPageTest {

    private val humanity = "https://lecture.example.edu:9999/subject/humanityLecture"
    private val science = "https://lecture.example.edu:9999/subject/lecture"

    // ---------- 栏目页判定 ----------

    @Test
    fun `完全相同的地址判为栏目页`() {
        assertTrue(XkctsPage.isSchedulePage(humanity, humanity))
    }

    /** 带一次性票据的落地地址必须判成栏目页 —— 否则「登录成功后读不了」会非常费解。 */
    @Test
    fun `带查询串的落地地址仍判为栏目页`() {
        assertTrue(
            XkctsPage.isSchedulePage(
                "$humanity?ticket=ST-12345-abcdef",
                humanity,
            ),
        )
    }

    /** 锚点同样忽略。 */
    @Test
    fun `带锚点的地址仍判为栏目页`() {
        assertTrue(XkctsPage.isSchedulePage("$humanity#main", humanity))
    }

    /** 末尾斜杠的差异必须容忍：服务端加了重定向就会多一个 `/`。 */
    @Test
    fun `末尾斜杠差异不影响判定`() {
        assertTrue(XkctsPage.isSchedulePage("$humanity/", humanity))
        assertTrue(XkctsPage.isSchedulePage(humanity, "$humanity/"))
    }

    @Test
    fun `首尾空白被忽略`() {
        assertTrue(XkctsPage.isSchedulePage("  $humanity  ", humanity))
    }

    /** 两个栏目是**不同**的页面：人文讲座不能被判成科学前沿讲座（否则数据会标错栏目）。 */
    @Test
    fun `两个栏目互不误判`() {
        assertFalse(XkctsPage.isSchedulePage(science, humanity))
        assertFalse(XkctsPage.isSchedulePage(humanity, science))
        assertEquals(LectureEventKind.HUMANITY, XkctsPage.kindOf(humanity, humanity, science))
        assertEquals(LectureEventKind.SCIENCE, XkctsPage.kindOf(science, humanity, science))
    }

    /** 栏目页下的子路径**不**算栏目页：那是另一个页面，内容不保证是那张表。 */
    @Test
    fun `子路径不算栏目页`() {
        assertFalse(XkctsPage.isSchedulePage("$humanity/detail", humanity))
    }

    // ---------- 非栏目页 ----------

    /** 登录页、工作台、二次验证页都必须判成「不是栏目页」，界面据此提示需要登录。 */
    @Test
    fun `登录与跳转页面判为非栏目页`() {
        val others = listOf(
            "https://sso.example.edu/login?service=xxx",
            "https://lecture.example.edu:9999/redirect",
            "https://lecture.example.edu:9999/",
            "https://lecture.example.edu:9999/subject/humanityStudent",
            "https://lecture.example.edu:9999/subject/humanityNotice",
        )
        for (url in others) {
            assertFalse("$url 不该被判成人文栏目页", XkctsPage.isSchedulePage(url, humanity))
            assertNull(XkctsPage.kindOf(url, humanity, science))
        }
    }

    /**
     * 解析不出主机的地址一律判「不是」。
     *
     * 这是刻意的保守选择：拿一个 `about:blank` 去和栏目地址做字符串比较，
     * 只会碰巧相等或碰巧不等，两种结果都不可靠 —— 不如明确判否。
     */
    @Test
    fun `空地址与伪协议判为非栏目页`() {
        assertFalse(XkctsPage.isSchedulePage("", humanity))
        assertFalse(XkctsPage.isSchedulePage("about:blank", humanity))
        assertFalse(XkctsPage.isSchedulePage("data:text/html,<html></html>", humanity))
        assertFalse(XkctsPage.isSchedulePage(humanity, ""))
    }

    /** 大小写敏感：路径在服务端通常敏感，不做大小写归一化以免误判。 */
    @Test
    fun `路径大小写不同不算同一页`() {
        assertFalse(
            XkctsPage.isSchedulePage(
                "https://lecture.example.edu:9999/subject/humanitylecture",
                humanity,
            ),
        )
    }

    /** 未配置端点（本地配置缺失、BuildConfig 注入为空）时，任何地址都不该被判成栏目页。 */
    @Test
    fun `未配置目标地址时一律判否`() {
        assertFalse(XkctsPage.isSchedulePage(humanity, ""))
        assertNull(XkctsPage.kindOf(humanity, "", ""))
    }

    // ---------- 会话失效与 401 JSON ----------

    /** `/redirect` 是未建会话时的固定落点，必须被识别以便立刻拉回 SEP。 */
    @Test
    fun `预约系统的会话跳转页能被识别`() {
        val base = "https://lecture.example.edu:9999"
        assertTrue(XkctsPage.isSessionRedirect("$base/redirect", base))
        assertTrue(XkctsPage.isSessionRedirect("$base/redirect?x=1", base))
        assertFalse(XkctsPage.isSessionRedirect(humanity, base))
        assertFalse(XkctsPage.isSessionRedirect("$base/redirect", ""))
    }

    /** SEP 在 API 协商下返回的裸 401 JSON —— WebView 一旦渲染它就进不了登录表单。 */
    @Test
    fun `未登录的401JSON能被识别`() {
        assertTrue(
            XkctsPage.isAuthFailurePayload(
                """{"code":401,"msg":"未登录或会话已过期","toUrl":"/"}""",
            ),
        )
        assertTrue(
            XkctsPage.isAuthFailurePayload(
                """  {"code":401,"msg":"会话已失效","toUrl":"/appStore"}  """,
            ),
        )
        assertFalse(XkctsPage.isAuthFailurePayload("<html>登录</html>"))
        assertFalse(XkctsPage.isAuthFailurePayload("""{"code":200,"msg":"ok"}"""))
        assertFalse(XkctsPage.isAuthFailurePayload(""))
    }
}
