package com.ucas.qingxin.signin

import com.ucas.qingxin.signin.util.DateInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * 「输入日期」解析的单元测试。
 *
 * 这层唯一的价值就是把「用户随手打的东西」翻译成一个确定的日期，
 * 因此用例刻意围绕**真实输入形态**（含中文输入法的全角字符、从别处粘贴的
 * 日期时间、只写月日）而不是围绕正则本身来写。
 */
class DateInputTest {

    private val today: LocalDate = LocalDate.of(2026, 9, 17)

    // ------------------------------------------------------------------ 完整日期

    @Test
    fun `四种常见分隔符都认`() {
        val expected = LocalDate.of(2026, 9, 20)
        assertEquals(expected, DateInput.parse("2026-09-20", today))
        assertEquals(expected, DateInput.parse("2026/09/20", today))
        assertEquals(expected, DateInput.parse("2026.09.20", today))
        assertEquals(expected, DateInput.parse("2026年9月20日", today))
    }

    @Test
    fun `月日不补零也认`() {
        assertEquals(LocalDate.of(2026, 9, 2), DateInput.parse("2026-9-2", today))
        assertEquals(LocalDate.of(2026, 9, 2), DateInput.parse("2026年9月2日", today))
    }

    @Test
    fun `紧凑八位数字被当成日期而不是月日组合`() {
        // 顺序错了就会被误读：`20260920` 若先按「缺年份」规则解析，
        // 会变成 20 年 9 月 20 日（甚至直接失败）。
        assertEquals(LocalDate.of(2026, 9, 20), DateInput.parse("20260920", today))
    }

    // ------------------------------------------------------------------ 缺年份

    @Test
    fun `只写月日时补当年`() {
        assertEquals(LocalDate.of(2026, 9, 20), DateInput.parse("9-20", today))
        assertEquals(LocalDate.of(2026, 9, 20), DateInput.parse("9/20", today))
        assertEquals(LocalDate.of(2026, 9, 20), DateInput.parse("9月20日", today))
    }

    @Test
    fun `一月一日这类跨年边界仍补当年`() {
        assertEquals(LocalDate.of(2026, 1, 1), DateInput.parse("1月1日", today))
    }

    // ------------------------------------------------------------------ 噪声容忍

    @Test
    fun `中文输入法的全角字符被归一化`() {
        assertEquals(
            LocalDate.of(2026, 9, 20),
            DateInput.parse("２０２６－０９－２０", today),
        )
        assertEquals(LocalDate.of(2026, 9, 20), DateInput.parse("２０２６／９／２０", today))
    }

    @Test
    fun `分隔符两侧的空格与首尾空白都被忽略`() {
        assertEquals(LocalDate.of(2026, 9, 20), DateInput.parse("  2026 - 09 - 20 ", today))
    }

    @Test
    fun `粘贴进来的日期时间只取日期部分`() {
        assertEquals(LocalDate.of(2026, 9, 20), DateInput.parse("2026-09-20 08:00", today))
        assertEquals(LocalDate.of(2026, 9, 20), DateInput.parse("2026-09-20T08:00:00", today))
        assertEquals(LocalDate.of(2026, 9, 20), DateInput.parse("2026-09-20T08:00:00.000", today))
    }

    @Test
    fun `末尾的中文句读被忽略`() {
        assertEquals(LocalDate.of(2026, 9, 20), DateInput.parse("2026-09-20。", today))
    }

    // ------------------------------------------------------------------ 拒绝

    @Test
    fun `不存在的日期被拒绝而不是顺延`() {
        // 若用「宽容」的解析（例如 java.time 的 SMART 模式），2 月 30 日会被
        // 顺延成 3 月 2 日 —— 那会让用户看到完全不相干的一天的课表。
        assertNull(DateInput.parse("2026-02-30", today))
        assertNull(DateInput.parse("2026-13-01", today))
        assertNull(DateInput.parse("2026-00-10", today))
    }

    @Test
    fun `年份越界被拒绝`() {
        assertNull(DateInput.parse("1999-09-20", today))
        assertNull(DateInput.parse("2101-09-20", today))
    }

    @Test
    fun `夹杂无关字符时整串判为非法`() {
        // 从一长串文字里抠出日期的代价是「用户以为查对了、其实查的是别的一天」，
        // 宁可让他重输一次。
        assertNull(DateInput.parse("abc2026-09-20xyz", today))
        assertNull(DateInput.parse("第2026-09-20周", today))
    }

    @Test
    fun `空输入与不认识的形态返回 null`() {
        assertNull(DateInput.parse("", today))
        assertNull(DateInput.parse("   ", today))
        assertNull(DateInput.parse("明天", today))
        assertNull(DateInput.parse("0920", today))
        assertNull(DateInput.parse("2026-09", today))
    }

    // ------------------------------------------------------------------ 展示

    @Test
    fun `展示格式固定为 ISO 并给出中文星期`() {
        val date = LocalDate.of(2026, 9, 20)
        assertEquals("2026-09-20", DateInput.display(date))
        assertEquals("9月20日 周日", DateInput.prettyLabel(date))
        assertEquals("周日", DateInput.weekdayLabel(date))
    }

    @Test
    fun `星期换算覆盖整周且周一在前`() {
        // 2026-09-14 是周一。
        val monday = LocalDate.of(2026, 9, 14)
        val labels = (0L..6L).map { DateInput.weekdayLabel(monday.plusDays(it)) }
        assertEquals(listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日"), labels)
    }

    @Test
    fun `只写月日时补的是输入当天的年份_不猜跨年`() {
        // 12 月 31 日输入 `1-1` 得到的是当年 1 月 1 日（即过去），而不是次年。
        // 这是刻意的：界面本身支持前后一天与直接选日期，与其让程序猜「用户是不是
        // 想看明年」，不如保持「补当年」这一条可预测的规则。
        val endOfYear = LocalDate.of(2026, 12, 31)
        assertEquals(LocalDate.of(2026, 1, 1), DateInput.parse("1-1", endOfYear))
    }
}
