package com.ucas.qingxin.signin

import com.ucas.qingxin.signin.attendance.PowerProfile
import com.ucas.qingxin.signin.attendance.PowerProfiles
import com.ucas.qingxin.signin.data.UserSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 两档省电设计的取值锁定。
 *
 * 这些断言的作用不是「测试代码有没有写错」，而是**把产品承诺钉住**：
 * 「省电模式比普通模式更省」这句话必须能在数值上被验证，否则很容易在后续
 * 改动里悄悄退化（1.2.0 的 `lowPowerMode` 就是这样变成只管一个开关的）。
 */
class PowerProfileTest {

    private val normal = PowerProfiles.resolve(UserSettings(lowPowerMode = false), systemPowerSave = false)
    private val saver = PowerProfiles.resolve(UserSettings(lowPowerMode = true), systemPowerSave = false)

    @Test
    fun `普通档保留常驻服务，省电档不使用`() {
        assertTrue(normal.daemonEnabled)
        assertFalse(saver.daemonEnabled)
    }

    @Test
    fun `省电档的每一个周期都不短于普通档`() {
        assertTrue("小部件数据兜底", saver.widgetFallbackMs >= normal.widgetFallbackMs)
        assertTrue("小部件周期任务", saver.widgetPeriodicMinutes >= normal.widgetPeriodicMinutes)
        assertTrue("讲座巡检", saver.lectureCheckHours >= normal.lectureCheckHours)
        assertTrue("窗口内重试上限", saver.maxAttempts <= normal.maxAttempts)
        assertTrue("秒级重绘宽限", saver.widgetGraceMs <= normal.widgetGraceMs)
    }

    @Test
    fun `省电档关闭秒级重绘`() {
        assertEquals(0L, saver.widgetGraceMs)
    }

    @Test
    fun `普通档的取值`() {
        assertEquals(8, normal.maxAttempts)
        assertEquals(60L * 60_000L, normal.widgetFallbackMs)
        assertEquals(60L, normal.widgetPeriodicMinutes)
        assertEquals(60_000L, normal.widgetGraceMs)
        assertEquals(24L, normal.lectureCheckHours)
        assertEquals(24L * 60L * 60_000L, normal.updateCheckIntervalMs)
    }

    @Test
    fun `省电档的取值`() {
        assertEquals(3, saver.maxAttempts)
        assertEquals(120L * 60_000L, saver.widgetFallbackMs)
        assertEquals(120L, saver.widgetPeriodicMinutes)
        assertEquals(48L, saver.lectureCheckHours)
        // 更新检查只有一次 GET，成本远低于讲座巡检的几十个请求，两档都保留 24 小时。
        assertEquals(normal.updateCheckIntervalMs, saver.updateCheckIntervalMs)
    }

    @Test
    fun `默认档是普通档`() {
        assertEquals(PowerProfile.NORMAL, PowerProfile.DEFAULT)
    }

    @Test
    fun `系统省电模式把重试上限收緊到 3 次并关掉秒级重绘`() {
        val tightened = PowerProfiles.resolve(UserSettings(lowPowerMode = false), systemPowerSave = true)
        assertEquals(3, tightened.maxAttempts)
        assertEquals(0L, tightened.widgetGraceMs)
        assertTrue(tightened.systemPowerSave)
    }

    @Test
    fun `系统省电模式不改变用户选择的档位`() {
        // 关键取舍：用户在系统里因为低电量进入省电模式时，不该连「上课前 15 分钟提醒」
        // 与按需的守护服务一起消失 —— 那是用户在应用内显式开启的能力。
        val tightened = PowerProfiles.resolve(UserSettings(lowPowerMode = false), systemPowerSave = true)
        assertEquals(PowerProfile.NORMAL, tightened.profile)
        assertTrue(tightened.daemonEnabled)
        // 其余间隔也跟随用户档位，不被系统省电改写。
        assertEquals(normal.widgetFallbackMs, tightened.widgetFallbackMs)
        assertEquals(normal.lectureCheckHours, tightened.lectureCheckHours)
    }

    @Test
    fun `系统省电模式不会把省电档的重试上限抬高`() {
        val tightened = PowerProfiles.resolve(UserSettings(lowPowerMode = true), systemPowerSave = true)
        assertEquals(3, tightened.maxAttempts)
        assertFalse(tightened.daemonEnabled)
    }

    @Test
    fun `宿主推送周期固定为 2 小时且不参与档位`() {
        // AppWidgetProviderInfo.updatePeriodMillis 由宿主读取，Android 不给运行时修改的接口。
        // 这条断言是为了阻止「把档位一改就以为宿主推送也变了」的误解。
        assertEquals(2L * 60L * 60_000L, PowerProfile.WIDGET_UPDATE_PERIOD_XML_MILLIS)
    }
}
