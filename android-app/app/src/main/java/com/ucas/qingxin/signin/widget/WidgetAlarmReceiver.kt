package com.ucas.qingxin.signin.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 边界闹钟接收器。
 *
 * 由 [WidgetRefreshScheduler] 用 `AlarmManager.setAndAllowWhileIdle` 排定，
 * 触发点是「课时语义发生变化」的时刻（开课前 25 分钟 / 上课 / 下课 / 跨天）
 * 或数据兜底上限。因此它必须做到两件事：
 *
 * 1. **先本地重绘**：即使断网、未登录，也能立刻把「当前课 / 可签到」切到正确状态；
 * 2. **再抓数据**：交给 WorkManager 执行（10 秒接收器限制内不做网络请求）。
 *
 * 注意不清空闹钟标记的话，[WidgetRefreshScheduler.ensureAlarm] 会一直认为
 * 「已经排过了」而不再续排，心跳就会停。所以这里第一步先清标记。
 */
class WidgetAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_WIDGET_ALARM) return
        if (WidgetHostCompat.widgetCount(context) == 0) {
            WidgetRefreshScheduler.cancelAll(context)
            return
        }
        WidgetRefreshScheduler.clearAlarmMark(context)
        runCatching { WidgetTicker.startForGrace(context) }
        runCatching { TodayCourseWidgetReceiver.requestUpdate(context) }
        runCatching { WidgetRefreshScheduler.refreshData(context, force = true) }
        // 立刻续排下一个边界，保证心跳不依赖下一次数据抓取是否成功。
        runCatching { WidgetRefreshScheduler.ensureAlarm(context) }
    }

    companion object {
        const val ACTION_WIDGET_ALARM = "com.ucas.qingxin.signin.action.WIDGET_ALARM"
    }
}
