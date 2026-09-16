package com.ucas.qingxin.signin.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context

/**
 * 宿主（启动器 / 负一屏）识别与厂商广播适配。
 *
 * 尺寸自适应的部分见 `WidgetSizeCompat.kt`（该文件含有改编自 Breezy Weather
 * 的代码，按 LGPL-3.0 授权）；本文件为本项目原创代码，按 AGPL-3.0 授权。
 */
internal object WidgetHostCompat {

    /** 小米 / HyperOS 专用刷新广播；负一屏与桌面拖动后由该广播触发重绘。 */
    const val ACTION_MIUI_APPWIDGET_UPDATE = "miui.appwidget.action.APPWIDGET_UPDATE"

    /** 部分 ROM 的“快速开机”（HTC 引入，后被小米等沿用）。 */
    const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
    const val ACTION_HTC_QUICKBOOT_POWERON = "com.htc.intent.action.QUICKBOOT_POWERON"

    /** 收集本应用全部小部件实例（含 2×2 / 4×2 / 4×4）。 */
    fun allWidgetComponents(context: Context): List<ComponentName> = listOf(
        ComponentName(context, TodayCourseCompactWidgetReceiver::class.java),
        ComponentName(context, TodayCourseWidgetReceiver::class.java),
        ComponentName(context, TodayCourseTallWidgetReceiver::class.java),
    )

    /** 当前桌面上的小部件实例总数；为 0 时可回收后台刷新任务。 */
    fun widgetCount(context: Context): Int {
        val manager = AppWidgetManager.getInstance(context) ?: return 0
        return allWidgetComponents(context)
            .sumOf { runCatching { manager.getAppWidgetIds(it).size }.getOrDefault(0) }
    }
}
