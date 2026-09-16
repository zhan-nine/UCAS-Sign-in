package com.ucas.qingxin.signin.attendance

import android.app.Activity
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.ucas.qingxin.signin.R
import com.ucas.qingxin.signin.widget.VendorRom

/**
 * 保活相关的**只读系统状态查询**与**系统设置页跳转**。
 *
 * 自动签到要在后台准时执行，绕不开三件事：省电豁免、自启动、锁定后台。
 * 本类负责把这三件事变成「可点击、可核对状态」的入口 ——
 * 只给文字说明的引导，用户几乎不会去做。
 *
 * 注意：Android **没有**公开 API 能查询「自启动是否已允许」「后台是否已锁定」，
 * 因此这两项由 [AttendanceScheduler] 用可观测信号代替：
 * - 自启动：`BOOT_COMPLETED` 广播是否至少收到过一次（收到即证明允许）；
 * - 锁定后台：用户手动确认。
 */
internal object KeepAliveHelper {

    /** 是否已加入电池优化白名单（即「不受省电策略限制」）。 */
    fun isBatteryExempt(context: Context): Boolean = runCatching {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return@runCatching false
        pm.isIgnoringBatteryOptimizations(context.packageName)
    }.getOrDefault(false)

    /**
     * 直接弹出「忽略电池优化」系统授权框（效果最好、一步到位）。
     * 若厂商把该页面改坏，则回退到电池优化列表页，再回退到应用详情页。
     *
     * @return true 表示弹出了**直接授权框**；false 表示只打开了设置列表页。
     */
    fun requestBatteryExemption(activity: Activity): Boolean {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${activity.packageName}"))
        if (runCatching { activity.startActivity(direct) }.isSuccess) return true
        val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        if (runCatching { activity.startActivity(list) }.isSuccess) return false
        runCatching { VendorRom.openAppDetails(activity) }
        return false
    }

    /** 是否允许排精确闹钟（Android 12+ 需要 `SCHEDULE_EXACT_ALARM`，用户可撤销）。 */
    fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return runCatching {
            (context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager)
                ?.canScheduleExactAlarms() == true
        }.getOrDefault(false)
    }

    fun openExactAlarmSettings(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val app = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            .setData(Uri.parse("package:${activity.packageName}"))
        if (runCatching { activity.startActivity(app) }.isSuccess) return
        openAppDetails(activity)
    }

    /** 通知总开关是否打开（Android 13+ 的运行时权限关闭后这里也会是 false）。 */
    fun notificationsEnabled(context: Context): Boolean =
        runCatching { NotificationManagerCompat.from(context).areNotificationsEnabled() }
            .getOrDefault(false)

    fun openNotificationSettings(activity: Activity) {
        val appNotifications = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
        if (runCatching { activity.startActivity(appNotifications) }.isSuccess) return
        openAppDetails(activity)
    }

    /** 打开「自启动 / 后台运行」系统页（复用厂商 ROM 分支）。 */
    fun openAutoStart(activity: Activity) {
        runCatching { VendorRom.openAutoStartSettings(activity) }
    }

    fun openAppDetails(activity: Activity) {
        runCatching { VendorRom.openAppDetails(activity) }
    }

    /** 按 ROM 给出「最近任务里锁定后台」的分步说明。 */
    fun lockInRecentsGuide(context: Context): String = when (VendorRom.family(context)) {
        VendorRom.Family.COLOR_OS -> context.getString(R.string.keepalive_lock_coloros)
        VendorRom.Family.HYPER_OS -> context.getString(R.string.keepalive_lock_miui)
        VendorRom.Family.ORIGIN_OS -> context.getString(R.string.keepalive_lock_vivo)
        VendorRom.Family.HARMONY -> context.getString(R.string.keepalive_lock_harmony)
        VendorRom.Family.ONE_UI -> context.getString(R.string.keepalive_lock_samsung)
        VendorRom.Family.AOSP -> context.getString(R.string.keepalive_lock_aosp)
    }
}
