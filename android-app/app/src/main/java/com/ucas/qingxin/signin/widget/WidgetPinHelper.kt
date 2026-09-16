package com.ucas.qingxin.signin.widget

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import com.ucas.qingxin.signin.R

/**
 * 跨厂商「固定小部件到桌面」引导。
 *
 * ColorOS 16 特别说明：
 * - 系统 `ACTION_APPWIDGET_PICK` 选择器已坏（OnePage / 社区确认）；
 * - 应用内 `requestPinAppWidget` 是主路径，并带成功回调以便确认是否落地；
 * - 若无弹窗，通常是「创建桌面快捷方式」未开，或 Shelf 被停用。
 */
object WidgetPinHelper {

    enum class Spec(
        val providerClass: Class<out AppWidgetProvider>,
        val labelRes: Int,
        val sizeLabel: String,
    ) {
        COMPACT(TodayCourseCompactWidgetReceiver::class.java, R.string.widget_description_compact, "2×2"),
        WIDE(TodayCourseWidgetReceiver::class.java, R.string.widget_description, "4×2"),
        TALL(TodayCourseTallWidgetReceiver::class.java, R.string.widget_description_tall, "4×4"),
    }

    /** 已知会「返回成功但不落地」的启动器。 */
    private val PIN_BROKEN_LAUNCHERS = setOf(
        "com.huawei.android.launcher",
        "com.hihonor.android.launcher",
        "com.bbk.launcher2",
        "com.vivo.launcher",
    )

    fun launcherPackage(context: Context): String = VendorRom.launcherPackage(context)

    fun launcherLabel(context: Context): String = VendorRom.launcherLabel(context)

    fun isPinSupported(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        if (launcherPackage(context) in PIN_BROKEN_LAUNCHERS) return false
        return runCatching {
            AppWidgetManager.getInstance(context)?.isRequestPinAppWidgetSupported == true
        }.getOrDefault(false)
    }

    /**
     * 请求把指定规格固定到桌面。
     * 返回 true 表示请求已交给系统（用户还需在弹窗中确认）。
     */
    fun requestPin(activity: Activity, spec: Spec): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            showToast(activity, R.string.widget_pin_manual_hint)
            return false
        }
        if (!isPinSupported(activity)) {
            showToast(
                activity,
                if (launcherPackage(activity) in PIN_BROKEN_LAUNCHERS) {
                    R.string.widget_pin_launcher_unsupported
                } else {
                    R.string.widget_pin_manual_hint
                },
            )
            return false
        }
        val manager = AppWidgetManager.getInstance(activity) ?: run {
            showToast(activity, R.string.widget_pin_manual_hint)
            return false
        }
        return try {
            // 成功回调：部分 ColorOS 版本不弹确认框却直接添加，靠回调确认落地。
            val success = PendingIntent.getBroadcast(
                activity,
                7000 + spec.ordinal,
                Intent(activity, WidgetPinnedReceiver::class.java)
                    .setAction(WidgetPinnedReceiver.ACTION_PINNED)
                    .putExtra(WidgetPinnedReceiver.EXTRA_SPEC, spec.name),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val ok = manager.requestPinAppWidget(
                ComponentName(activity, spec.providerClass),
                null,
                success,
            )
            when {
                ok && VendorRom.isColorOs(activity) ->
                    showToast(activity, R.string.widget_pin_confirm_hint_coloros)
                ok -> showToast(activity, R.string.widget_pin_confirm_hint)
                else -> showToast(activity, R.string.widget_pin_need_shortcut_permission)
            }
            ok
        } catch (_: SecurityException) {
            showToast(activity, R.string.widget_pin_need_shortcut_permission)
            false
        } catch (_: Exception) {
            showToast(activity, R.string.widget_pin_manual_hint)
            false
        }
    }

    fun openAppDetails(activity: Activity) {
        if (!VendorRom.openAppDetails(activity)) {
            showToast(activity, R.string.widget_open_settings_failed)
        }
    }

    /** 打开厂商「自启动 / 后台运行」页，避免小部件进程被杀后空白。 */
    fun openAutoStart(activity: Activity) {
        if (!VendorRom.openAutoStartSettings(activity)) {
            showToast(activity, R.string.widget_open_settings_failed)
        }
    }

    /** ColorOS：打开 Shelf 应用详情，方便重新启用小部件选择器。 */
    fun openColorOsShelf(activity: Activity) {
        if (!VendorRom.openColorOsShelfDetails(activity)) {
            showToast(activity, R.string.widget_open_settings_failed)
        }
    }

    fun manualGuide(context: Context): String {
        val family = VendorRom.family(context)
        val steps = when (family) {
            VendorRom.Family.HYPER_OS -> context.getString(R.string.widget_guide_xiaomi)
            VendorRom.Family.COLOR_OS -> context.getString(R.string.widget_guide_coloros)
            VendorRom.Family.ORIGIN_OS -> context.getString(R.string.widget_guide_originos)
            VendorRom.Family.HARMONY -> context.getString(R.string.widget_guide_harmony)
            VendorRom.Family.ONE_UI -> context.getString(R.string.widget_guide_samsung)
            VendorRom.Family.AOSP -> context.getString(R.string.widget_guide_aosp)
        }
        val permission = context.getString(R.string.widget_pin_permission_hint)
        val keepalive = context.getString(R.string.widget_guide_keepalive)
        return "$permission\n\n$steps\n\n$keepalive"
    }

    /** 设置页顶部的 ROM 提示（ColorOS 额外强调 Shelf）。 */
    fun romHint(context: Context): String = when (VendorRom.family(context)) {
        VendorRom.Family.COLOR_OS -> context.getString(R.string.widget_rom_hint_coloros)
        VendorRom.Family.HYPER_OS -> context.getString(R.string.widget_rom_hint_xiaomi)
        VendorRom.Family.ORIGIN_OS -> context.getString(R.string.widget_rom_hint_vivo)
        VendorRom.Family.HARMONY -> context.getString(R.string.widget_rom_hint_huawei)
        else -> context.getString(R.string.widget_rom_hint_generic)
    }

    private fun showToast(activity: Activity, resId: Int) {
        runCatching { Toast.makeText(activity, resId, Toast.LENGTH_LONG).show() }
    }
}

/**
 * `requestPinAppWidget` 成功回调。
 * ColorOS 部分版本不弹确认框就直接添加，靠这里确认并触发首次刷新。
 */
class WidgetPinnedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_PINNED) return
        runCatching { WidgetRefreshScheduler.sync(context) }
        runCatching { WidgetRefreshScheduler.refreshData(context, force = true) }
        runCatching {
            Toast.makeText(context, R.string.widget_pin_success, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val ACTION_PINNED = "com.ucas.qingxin.signin.action.WIDGET_PINNED"
        const val EXTRA_SPEC = "spec"
    }
}
