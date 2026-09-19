package com.ucas.qingxin.signin

import com.ucas.qingxin.signin.attendance.SignCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [SignCode] 的单测。
 *
 * 这个判定是**唯一**决定「用户输入会不会被送去签到接口」的地方，
 * 因此测试的重点不是「正常输入能过」，而是**异常输入必须被拦下**：
 * 一旦放宽，输入框就变成了「可以向任意编号发签到请求」的通道，
 * 而且失败原因还不可辨（服务端对格式不符只回一个含糊的失败）。
 */
class SignCodeTest {

    @Test
    fun `七位数字识别为节次编号`() {
        val code = SignCode.parse("1234567")
        assertNotNull(code)
        assertEquals("1234567", code!!.courseSchedId)
        assertEquals("", code.timeTableId)
        assertEquals("7 位节次编号", code.label)
    }

    @Test
    fun `三十二位十六进制识别为二维码标识`() {
        val hex = "0123456789abcdef0123456789abcdef"
        val code = SignCode.parse(hex)
        assertNotNull(code)
        assertEquals("", code!!.courseSchedId)
        assertEquals(hex, code.timeTableId)
        assertEquals("32 位二维码标识", code.label)
    }

    @Test
    fun `带连字符的 UUID 形态会被压成 32 位`() {
        // 界面与文档里常按 UUID 习惯展示成 8-4-4-4-12，而签到通道要的是无连字符的 32 位，
        // 因此这一步压缩是必需的，不是宽容。
        val dashed = "01234567-89ab-cdef-0123-456789abcdef"
        val code = SignCode.parse(dashed)
        assertNotNull(code)
        assertEquals("0123456789abcdef0123456789abcdef", code!!.timeTableId)
    }

    @Test
    fun `首尾空白会被忽略`() {
        // 从班牌照片、聊天记录复制出来的编号几乎必然带空白或换行。
        assertEquals("1234567", SignCode.parse("  1234567 \n")!!.courseSchedId)
        assertEquals(
            "0123456789abcdef0123456789abcdef",
            SignCode.parse("\t0123456789abcdef0123456789abcdef ")!!.timeTableId,
        )
    }

    @Test
    fun `大写十六进制也认`() {
        val upper = "0123456789ABCDEF0123456789ABCDEF"
        assertEquals(upper, SignCode.parse(upper)!!.timeTableId)
    }

    @Test
    fun `位数不对一律拦下`() {
        // 尾随 / 缺失一位是最常见的输入错误，必须挡住而不是「顺手截断」：
        // 截断会静默地给另一个编号打卡，这比报错严重得多。
        assertNull("6 位", SignCode.parse("123456"))
        assertNull("8 位", SignCode.parse("12345678"))
        assertNull("7 位但含字母", SignCode.parse("123456a"))
        assertNull("32 位里混了非十六进制字符", SignCode.parse("0123456789abcdef0123456789abcdeg"))
        assertNull("31 位十六进制", SignCode.parse("0123456789abcdef0123456789abcde"))
        assertNull("33 位十六进制", SignCode.parse("0123456789abcdef0123456789abcdef0"))
    }

    @Test
    fun `空输入与纯空白返回空`() {
        assertNull(SignCode.parse(""))
        assertNull(SignCode.parse("   "))
        assertNull(SignCode.parse("\n"))
    }

    @Test
    fun `中文与全角数字不会被当成编号`() {
        // 全角数字在视觉上与半角几乎一致，若被放行就会变成一个「看起来对、就是签不上」的编号。
        assertNull(SignCode.parse("１２３４５６７"))
        assertNull(SignCode.parse("一二三四五六七"))
        assertNull(SignCode.parse("课程 1234567"))
    }

    @Test
    fun `连字符不能把非法输入洗成合法`() {
        // 只删除 `-`，不做任何补位或截断：因此拆开也凑不出 32 位就必须拦下。
        assertNull(SignCode.parse("01234567-89ab-cdef-0123-456789abcde"))
        // 反过来，连字符加在 7 位数字里也不该被当成 32 位。
        assertNull(SignCode.parse("123-4567"))
    }

    @Test
    fun `七位与三十二位互斥且优先按七位判定`() {
        // 两种形态不可能同时成立（7 位纯数字 vs 32 位十六进制），
        // 因此判定顺序不会产生歧义 —— 这条测试是为了把这个不变量钉住。
        val code = SignCode.parse("1234567")!!
        assertEquals("", code.timeTableId)
        assertEquals("1234567", code.courseSchedId)
    }
}
